# ADR-010: Exeris as Host Runtime for Spring Applications

| Attribute | Value                                                                 |
|:----------|:----------------------------------------------------------------------|
| **Status** | Accepted                                                              |
| **Date** | 2026-03-13                                                            |
| **Authors** | Arkadiusz Przychocki                                                  |
| **Related** | ADR-007 Next-Gen Runtime Architecture, The Wall, Performance Contract |
| **Repo** | `exeris-spring-runtime`                                               |

---

## 1. Context

Exeris Kernel was designed as a zero-copy, off-heap, Loom-first runtime with strict separation
of concerns:

- **SPI** — pure contracts, zero implementation
- **Core** — bootstrap, orchestration, lifecycle
- **Providers** — Community/Enterprise execution drivers
- **TCK** — contract verification

The Spring ecosystem remains the dominant application programming model on the JVM. The integration
objective is not to run Exeris as a helper library inside standard Spring Boot, but to enable a
model in which:

> **Spring provides the application model and DI ergonomics. Exeris owns the data-plane and infra-plane.**

In practical terms, this means targeting the replacement of:

- Tomcat / Jetty / Undertow / Netty as the HTTP transport
- Conventional JDBC pool + request I/O lifecycle management
- Potentially parts of the execution and context-propagation model

Exeris becomes the host runtime; Spring becomes the application guest.

---

## 2. Problem Statement

A thin Spring Boot Starter solves autoconfiguration and property binding. It does not solve:

- transport edge ownership,
- request lifecycle control,
- backpressure and load shedding ownership,
- memory lifecycle and off-heap semantics,
- zero-copy I/O on the request path,
- execution model (virtual thread per request vs reactive vs thread pool),
- telemetry hot path.

Without owning these, Exeris is merely a Spring library — not the host runtime. This ADR
formalises the decision to build a runtime integration layer, not a starter.

---

## 3. Decision

This repository (`exeris-spring-runtime`) is the **Exeris host-runtime integration layer for
Spring applications**.

It operates outside `exeris-kernel`, outside `exeris-kernel-spi`, and outside `exeris-kernel-core`.
It bridges Spring application model semantics to Exeris runtime ownership.

### 3.1 Ownership Model

**Spring owns:**

- Dependency injection and bean lifecycle
- Configuration binding
- Application logic composition
- Optionally: management/control-plane (actuator)

**Exeris owns:**

- HTTP transport ingress/egress
- Request execution lifecycle (1 VT per request)
- Backpressure and PAQS scheduling
- Off-heap memory lifecycle
- Provider discovery (via ServiceLoader — Spring does not replace this)
- Runtime telemetry hot path

### 3.2 Location of Integration

Integration is developed in this repository only. The following repositories must remain
unchanged by this integration work:

- `exeris-kernel-spi` — must remain Spring-free
- `exeris-kernel-core` — must remain Spring-agnostic
- `exeris-kernel-community` — must remain driver-only, no framework assumptions
- `exeris-kernel-enterprise` — same as community

If a change to the kernel appears necessary to enable the integration, it must be proposed
as a kernel ADR first and must not introduce Spring types into SPI or Core.

### 3.3 The Wall Remains Non-Negotiable

- No Spring annotations or types in `eu.exeris.kernel.spi.*`
- No Spring annotations or types in `eu.exeris.kernel.core.*`
- No Spring IoC replacing `ServiceLoader` as the canonical mechanism for provider discovery

### 3.4 Exeris Owns the Hot Path

In host-runtime mode:

- Request ingress must flow through Exeris, not Tomcat/Jetty/Undertow/Netty
- The `HttpHandler` SPI is the only entry point into application logic
- Body handling must target zero heap copy in pure mode
- Virtual threads are the execution model, not reactive pipelines

### 3.5 Compatibility Is Phased

Full Spring MVC / WebFlux / JPA compatibility is not the entry goal. Compatibility
features are introduced incrementally and explicitly. Each compatibility feature must:

- be opt-in (not default)
- document its trade-offs against pure mode
- live in a dedicated `*.compat.*` package
- not degrade the pure mode path

---

## 4. Architectural Consequences

### 4.1 What This Enables

- Running Spring applications without Tomcat, Jetty, Undertow, or Netty as the transport
- Preserving Spring DI / config / lifecycle ergonomics while Exeris owns execution
- Incremental adoption path: pure handler model first, compatibility surface later
- Honest performance measurements (Exeris overhead is measurable and attributable)

### 4.2 What This Excludes

- Spring types in kernel SPI or Core — permanently banned
- Servlet stack as canonical ingress — banned in pure mode
- WebFlux / Project Reactor as the execution model — banned in all modes
- Uncontrolled compatibility surface growth

### 4.3 What This Complicates

- Integration with `@RestController` and standard Spring MVC dispatch pipeline
- Request scoping and context propagation (`ThreadLocal`-heavy Spring assumptions)
- `@Transactional` behavior (ScopedValue vs ThreadLocal tension)
- Security context propagation (`SecurityContextHolder` is ThreadLocal by default)
- JPA/Hibernate compatibility (deliberately deferred to Phase 3)

---

## 5. Non-Goals (Current Phase)

- Full servlet API emulation
- Full Spring MVC annotation parity
- Full WebFlux compatibility
- Full JPA/Hibernate support
- Drop-in replacement for spring-boot-starter-web
- ThreadLocal-safe compatibility without explicit bridging isolation

---

## 6. Integration Shape

Modules in this repository:

| Module | Role | Phase |
|:-------|:-----|:------|
| `exeris-spring-boot-autoconfigure` | Config, lifecycle, bootstrap hooks | 0 |
| `exeris-spring-runtime-web` | HTTP bridge, handler dispatch, codecs | 1 |
| `exeris-spring-runtime-actuator` | Health, metrics, diagnostics | 1 |
| `exeris-spring-runtime-tx` | Transaction boundary bridge | 3 |
| `exeris-spring-runtime-data` | Persistence integration (optional) | 3 |

---

## 7. Delivery Order

1. Architecture spike — bootstrap POC, lifecycle bridge, Wall validation
2. Exeris HTTP ingress — pure mode handler model, no servlet API
3. Spring Web compatibility bridge — `@RestController` (opt-in, Phase 2)
4. Transaction and context bridge — ScopedValue-safe implementation
5. Persistence integration — native Exeris repo model, optional JDBC bridge

The order is non-negotiable. Starting with MVC/JPA compatibility before proving the
host-runtime model is the highest strategic risk in this project.

---

## 8. Risks Accepted

- Limited ecosystem compatibility at launch
- Manual registration model (no `@RestController` auto-discovery in Phase 1)
- Higher maintenance burden than a thin starter
- Potential developer friction for users expecting drop-in compatibility

---

## 9. Risk Mitigations

- Separate repository from kernel (no kernel contamination risk)
- Small compatibility surface at each phase boundary
- Hard performance gates for each phase — pure mode must not regress
- Architecture guard tests (ArchUnit rules enforced in CI)
- Explicit mode system: pure mode and compatibility mode never blur

---

## 10. Success Criteria

This ADR's decision is validated when:

1. A Spring application starts without `spring-boot-starter-web`, Tomcat, Netty, or Jetty.
2. HTTP ingress is owned and observable by Exeris.
3. A Spring-managed handler bean receives and processes an HTTP request.
4. The response exits through Exeris transport.
5. Kernel SPI and Core remain Spring-free (verified by architecture guard tests).
6. Shutdown is clean and deterministically ordered.

---

## 11. Review Triggers

This ADR should be revisited if:

- The Spring compatibility requirements mandate a kernel SPI change that introduces Spring types.
- Phase 1 pure mode cannot be delivered without servlet emulation as a prerequisite.
- Performance gates show that the integration bridge overhead eliminates Exeris runtime benefits.
- The persistence integration irreversibly collapses the architecture into JDBC/JPA-first ownership.
