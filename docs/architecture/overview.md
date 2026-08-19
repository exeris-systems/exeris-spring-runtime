# Architecture Overview

**Repository:** `exeris-spring-runtime`  
**Version:** see the root `pom.xml` `<version>` for the current development coordinate. Release train: `0.7.0` (Phase 4C graph seam is the highest landed train; see [`CHANGELOG.md`](../../CHANGELOG.md)).  
**Status:** Phases 0 / 1 / 2 / 3 (3A + 3C) / 3B-α / 4A / 4B / 4C all closed, and the Spring Boot 4 dual matrix has landed (ADR-028; it merged into the untagged 0.7.0 rather than the 0.8.0 train the ADR estimated). Outstanding for 1.0: Phase 5 edge gateway (0.9.0 train, ADR-021), Phase 3B-β/γ (kernel-gated, ADR-031), and per-path route authorization (ADR-063 — accepted, unblocked by the kernel 0.11.0 pin, not yet implemented). See `docs/roadmap-1.0-trl9.md` for the full release-train view.  
**Kernel target:** `exeris-kernel` 0.11.0 — the GA line of the kernel's two-track model, not the `0.11.0-preview` coordinate. **JDK 25 (LTS), no preview flag** anywhere in the chain: not at compile time, not on the test JVM, not on a consumer's.

---

## The Fundamental Model

```
┌─────────────────────────────────────────────────────────────────────┐
│  Application JVM Process                                            │
│                                                                     │
│  ┌──────────────────────────────────┐                               │
│  │  Spring Application Layer        │ ← DI, config, bean lifecycle  │
│  │  @Component, @Configuration,     │   developer ergonomics        │
│  │  @ConfigurationProperties        │                               │
│  └──────────────────┬───────────────┘                               │
│                     │ delegates business logic invocation            │
│  ┌──────────────────▼───────────────┐                               │
│  │  exeris-spring-runtime-web       │ ← HttpHandler bridge          │
│  │  ExerisHttpDispatcher            │   (pure mode request path)    │
│  └──────────────────┬───────────────┘                               │
│                     │ implements HttpHandler (kernel SPI)            │
│  ┌──────────────────▼───────────────┐                               │
│  │  exeris-kernel-core              │ ← owns transport lifecycle    │
│  │  + exeris-kernel-community       │   request scheduling          │
│  │    (or enterprise)               │   off-heap memory             │
│  │                                  │   backpressure + PAQS         │
│  │  HttpServerEngine                │   TLS                         │
│  │  PAQS Scheduler                  │                               │
│  └──────────────────────────────────┘                               │
│         ▲ TCP / QUIC (Exeris-owned ingress)                         │
└─────────────────────────────────────────────────────────────────────┘
```

**Spring is the application framework. Exeris is the runtime owner.**

This is not a thin starter. It is a host-runtime integration layer.

---

## What This Means in Practice

| Concern | Owner |
|:--------|:------|
| DI container | Spring |
| Configuration binding | Spring (`@ConfigurationProperties`) |
| Bean lifecycle | Spring (`SmartLifecycle`) |
| **Transport ingress** | **Exeris** |
| **Request lifecycle** | **Exeris** |
| **Backpressure / PAQS** | **Exeris** |
| **Off-heap memory** | **Exeris** |
| **Provider discovery** | **Exeris** (ServiceLoader) |
| **Telemetry hot path** | **Exeris** (JFR / GlassBox) |
| Business handler invocation | Exeris bridge → Spring bean |
| Response serialisation | Exeris bridge (codec integration) |
| Health / metrics exposure | Spring Boot Actuator + Exeris TelemetrySink |

---

## The Two Modes

Every feature in this repository must declare which mode it belongs to.

### Pure Mode (Default)

- Exeris-native request path with no servlet or reactive runtime involvement.
- Handlers are Spring beans, but the invocation model is defined by this layer, not Spring MVC.
- Minimal compatibility surface. Performance contract preserved.
- No `HttpServletRequest`, no `ServerWebExchange`, no Tomcat, no Netty.

### Compatibility Mode (Opt-In)

- Selected Spring Web programming model conveniences (`@RestController`, `@RequestMapping`, etc.).
- Explicit registration required. Never activates automatically when pure mode is running.
- Documented trade-off: increased heap churn, larger compatibility surface, reduced performance headroom.
- Delivered in Phase 2 (closed). Measured cost: Pure ≈ 176 B/dispatch vs Compat ≈ 5095 B/dispatch
  (`ExerisCompatAllocationCostReportTest`). Activated by `exeris.runtime.web.mode=compatibility`.

---

## Documentation Precedence

When documentation differs, use this source-of-truth hierarchy:

1. **Strategic architecture truth**
    - ADRs in `docs/adr/`
    - `docs/architecture/module-boundaries.md`
    - `docs/architecture/kernel-integration-seams.md`
2. **Delivery truth**
    - phase plans in `docs/phases/phase-*.md`
3. **Review behavior**
    - `.github/copilot-instructions.md`

Interpretation rules:
- ADRs define long-lived architecture intent.
- module boundaries and integration seams define structural contracts.
- phase docs define current implementation scope and roadmap sequence unless an ADR supersedes them.

---

## Canonical Roadmap Semantics

- **Phase 0** proves bootstrap coexistence and Wall integrity.
- **Phase 1** proves host-runtime legitimacy (Exeris-owned ingress path).
- **Phase 2** adds explicitly scoped, opt-in Spring compatibility.
- **Phase 3** expands into high-risk tx/context/persistence concerns (3A tx, 3C JDBC bridge per ADR-017).
- **Phase 3B-α** adds request scope + structured concurrency on `ScopedValue` (ADR-029). Kernel-independent.
- **Phase 3B-β/γ** add W3C `traceparent` propagation and OTel emission (ADR-031). **Kernel-gated** — waits
  on the kernel `TraceContext`/`ScopedValue` slot and `PrometheusOtlpTelemetrySink`; neither shipped as of
  the pinned kernel 0.11.0. The kernel places the `TraceContext` carrier in its consolidated 1.0 GA
  roadmap around Sprint 0.12, so this is not near-term.
- **Phase 4A / 4B / 4C** add the events, flow/saga, and graph seams — each a separate opt-in module,
  each default-off (ADR-027, ADR-030).
- **Phase 5** adds the edge gateway (ADR-021). Not started; it is **not** a Spring Cloud Gateway bridge.

Phases 4A–4C and 5 ship as **preview, default-off**. Graduation to bounded GA needs the phase invariants
green **and** ≥1 downstream service running the module in production for a representative period —
landing the code is not graduation.

---

## Module Dependency Graph

```
exeris-spring-boot-autoconfigure
    └── exeris-kernel-spi
    └── exeris-kernel-core
    └── spring-boot-autoconfigure
    └── spring-context

exeris-spring-runtime-web
    └── exeris-kernel-spi
    └── exeris-kernel-core
    └── exeris-spring-boot-autoconfigure
    └── exeris-spring-runtime-actuator          [optional]
    └── spring-web (programming model only — NOT spring-webmvc;
                    jakarta.servlet-api explicitly excluded)
    └── spring-boot-starter-oauth2-resource-server  [optional — compat-mode security only]

exeris-spring-runtime-tx
    └── exeris-kernel-spi
    └── spring-tx
    └── spring-context

exeris-spring-runtime-data  [Phase 3C — compat JDBC bridge, ADR-017]
    └── exeris-kernel-spi
    └── exeris-kernel-community
    └── exeris-spring-runtime-tx
    └── spring-tx
    (NOT spring-jdbc — see note below)

exeris-spring-runtime-actuator
    └── exeris-kernel-spi
    └── exeris-spring-boot-autoconfigure
    └── spring-boot-actuator-autoconfigure
    └── micrometer-core (optional)

exeris-spring-runtime-events  [Phase 4A]
    └── exeris-kernel-spi
    └── exeris-spring-boot-autoconfigure

exeris-spring-runtime-flow  [Phase 4B]
    └── exeris-kernel-spi
    └── exeris-spring-boot-autoconfigure
    └── exeris-spring-runtime-events            [choreography bridge consumes the events seam]

exeris-spring-runtime-graph  [Phase 4C]
    └── exeris-kernel-spi
    └── exeris-spring-boot-autoconfigure
```

Key constraints:
- `web` must NOT depend on `data`
- `data` depends on `tx` (not the reverse), and `data` must NOT depend on `web`
- `actuator` observes all, but must not own any execution path
- `events` / `flow` / `graph` depend on SPI + autoconfigure only — never on `web`, `tx`, or `data`.
  The single cross-runtime-module edge is `flow → events`, which exists so the choreography bridge
  can subscribe to the kernel `EventBus` seam; it is not a Spring event bridge (ADR-027).
- **`data` must NOT put `spring-jdbc` on its compile classpath.** The dependency is present but
  commented out in `exeris-spring-runtime-data/pom.xml`, and `ExerisDataAutoConfiguration` orders itself
  ahead of Spring Boot's `DataSourceAutoConfiguration` using `@AutoConfiguration(beforeName = "…")` with
  the FQN as a *string* rather than a class literal, specifically so the compile classpath stays clean.
  Adding `spring-jdbc` back is an architectural decision under ADR-017, not a convenience.
- No module may import Spring types into `eu.exeris.kernel.*` packages

---

## Integration with the Exeris Bootstrap DAG

The Exeris kernel initialises subsystems in a strict DAG:

```
FOUNDATION:  Memory (sequential)
                ↓
SERVICES:    Crypto + Persistence + Graph + Transport   (parallel, StructuredTaskScope)
                ↓
RUNTIME:     Events + Flow + HTTP                       (parallel)
                ↓
             KERNEL READY
```

Two things this DAG deliberately does **not** contain:

- **`Config` is not a DAG node.** It is resolved by `KernelBootstrap` via `ServiceLoader<ConfigProvider>`
  *before* the orchestrator runs. This repo contributes `ExerisSpringConfigProvider` at priority 150.
- **`Security` is not a boot node** — it is an L1 Citadel concept (ADR-012). `Exceptions` is not a
  subsystem layer either.

The canonical source is `exeris-kernel/docs/subsystems/bootstrap.md`; if this diagram and the kernel doc
disagree, the kernel doc wins.

The Spring `ApplicationContext` starts **after** the kernel reaches `READY` state (or in parallel for
config-only phases). The lifecycle sequencing is managed by `ExerisRuntimeLifecycle` (in this repo)
which implements `SmartLifecycle` and coordinates with `KernelBootstrap` in core.

Bootstrap order invariant:

1. Spring `ApplicationContext` refreshes (beans wire, properties bind).
2. `ExerisRuntimeLifecycle.start()` triggers kernel bootstrap via `KernelBootstrap`.
3. Kernel loads providers via `ServiceLoader`, initialises all subsystems.
4. On `KERNEL READY`: `exeris-spring-runtime-web` registers Spring handlers as `HttpHandler` instances.
5. Exeris `HttpServerEngine` starts accepting ingress.

Shutdown order is the exact reverse.

---

## The Wall: What Cannot Cross The Boundary

```
┌─────────────────────────────────────────────────────────────┐
│  exeris-kernel-spi     ← SPRING-FREE. The Wall starts here. │
│  exeris-kernel-core    ← SPRING-FREE.                       │
├─────────────────────────────────────────────────────────────┤
│  exeris-spring-runtime-*  ← Spring depends on kernel SPI.  │
│                              NOT the reverse.               │
└─────────────────────────────────────────────────────────────┘
```

Forbidden crossings:
- Any Spring annotation or type inside `eu.exeris.kernel.spi.*`
- Any Spring annotation or type inside `eu.exeris.kernel.core.*`
- Any `ServiceLoader` replacement by Spring IoC for provider discovery
- Any servlet or reactive type on the hot path in pure mode

---

## Package Naming Convention

```
eu.exeris.spring.boot.autoconfigure.*   — Boot config, conditions, lifecycle wiring
eu.exeris.spring.runtime.web.*          — Transport/request bridge, handlers, codecs
eu.exeris.spring.runtime.web.scope.*    — Request scope + structured concurrency (Phase 3B-α)
eu.exeris.spring.runtime.tx.*           — Transaction abstraction bridge
eu.exeris.spring.runtime.data.*         — Persistence integration (Phase 3C, compat JDBC)
eu.exeris.spring.runtime.actuator.*     — Health, metrics, diagnostics
eu.exeris.spring.runtime.events.*       — EventBus seam (Phase 4A)
eu.exeris.spring.runtime.flow.*         — Flow/saga seam (Phase 4B)
eu.exeris.spring.runtime.graph.*        — GraphEngine seam (Phase 4C)
```

These packages must never appear inside `eu.exeris.kernel.*`.

---

## Performance Invariants for This Layer

All integration code is adjacent to the kernel hot path. The following invariants apply:

| Concern | Rule |
|:--------|:-----|
| Per-request heap allocation | MUST be documented if non-zero. Zero is the target in pure mode. |
| Body copying | MUST NOT copy request/response body to a new heap buffer in the primary path. |
| Handler invocation | MUST NOT allocate wrapper DTOs on each invocation in pure mode. |
| Context propagation | MUST use `ScopedValue`, never `ThreadLocal`. |
| Compatibility mode overhead | MUST be measured. Must not silently apply to the pure mode path. |

---

## Status Reference by Phase

| Phase | Status | Milestone |
|:------|:-------|:----------|
| Phase 0 | Closed (2026-05-09) | Maven skeleton + ADR + bootstrap coexistence + Wall integrity |
| Phase 1 | Closed (2026-05-09) | Exeris-owned HTTP ingress, proven wire-level (`ExerisWireLevelRuntimeIntegrationTest`) |
| Phase 2 | Closed (2026-05-09) | Compatibility-mode `@RestController` bridge, no `DispatcherServlet` |
| Phase 3A / 3C | Closed (2026-05-09) | `ExerisPlatformTransactionManager`; ADR-017-bounded JDBC bridge |
| Phase 3B-α | Closed (2026-05-17) | Request scope + structured concurrency (ADR-029) |
| Phase 3B-β / γ | **Kernel-gated** | Waits on kernel `TraceContext` slot / OTLP sink (ADR-031) — still absent in the pinned kernel 0.11.0 |
| Phase 4A | Closed (2026-05-09) | Events seam, preview default-off |
| Phase 4B | Closed (2026-05-11) | Flow/saga seam + durable snapshots, preview default-off |
| Phase 4C | Closed (2026-05-17) | Graph seam, preview default-off, GA kernel-gated (ADR-030) |
| SB4 matrix | Landed | Spring Boot 4 dual matrix (ADR-028) — both lines build and test in CI on every push, plus an ADR-067 `binary-neutrality` job comparing Spring call-site descriptors across the two lines. Ships in 0.7.0, not the 0.8.0 train the ADR estimated. **Nominal compatibility, not a support commitment**: Spring Security 7 is a separate axis (ADR-028 obligation 6) |
| ADR-063 route authorization | Accepted, not implemented | `ExerisHttpSecurity` compiles per-path rules onto the kernel `HttpRoutePolicy`. Was blocked on the kernel pin; unblocked by 0.11.0. Until it lands, a migrating application has no per-path rule surface — and kernel 0.11.0 also removed the Community driver's `/secure` prefix convention |
| Phase 5 | Not started | Edge gateway (ADR-021), 0.9.0 train |

"Closed" means the phase invariants are captured and green — **not** that the module is GA. Phases 4A–4C
ship as preview default-off; see *Canonical Roadmap Semantics* above for the graduation criterion.

See `docs/phases/` for detailed delivery plans per phase.
