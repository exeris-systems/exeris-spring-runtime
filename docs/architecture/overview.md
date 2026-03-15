# Architecture Overview

**Repository:** `exeris-spring-runtime`  
**Version:** 0.1.0-SNAPSHOT  
**Status:** Phase 0 — Architecture Spike  
**Kernel target:** `exeris-kernel` 0.5.0-SNAPSHOT (TRL-3, Java 26)

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
- Implementation deferred to Phase 2.

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
- **Phase 3** expands into high-risk tx/context/persistence concerns.

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
    └── spring-web (programming model only — NOT spring-webmvc)

exeris-spring-runtime-tx
    └── exeris-kernel-spi
    └── spring-tx
    └── spring-context

exeris-spring-runtime-data  [Phase 3 placeholder]
    └── exeris-kernel-spi
    └── spring-tx

exeris-spring-runtime-actuator
    └── exeris-kernel-spi
    └── exeris-spring-boot-autoconfigure
    └── spring-boot-actuator-autoconfigure
    └── micrometer-core (optional)
```

Key constraints:
- `web` must NOT depend on `data`
- `tx` may depend on `data`, but `data` must NOT depend on `web`
- `actuator` observes all, but must not own any execution path
- No module may import Spring types into `eu.exeris.kernel.*` packages

---

## Integration with the Exeris Bootstrap DAG

The Exeris kernel initialises subsystems in a strict DAG:

```
Config → Memory → Exceptions
                → Security + Persistence (parallel)
                → Graph + Transport (parallel)
                → Events + Flow (parallel)
                → KERNEL READY
```

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
eu.exeris.spring.runtime.tx.*           — Transaction abstraction bridge
eu.exeris.spring.runtime.data.*         — Persistence integration (Phase 3)
eu.exeris.spring.runtime.actuator.*     — Health, metrics, diagnostics
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
| Phase 0 | In Progress | Maven skeleton + ADR + bootstrap POC |
| Phase 1 | Not Started | Exeris HTTP ingress + Spring bean handler |
| Phase 2 | Not Started | Spring MVC compatibility bridge (opt-in) |
| Phase 3 | Not Started | Transactions + context + persistence bridge |

See `docs/phases/` for detailed delivery plans per phase.
