# Module Boundaries

This document is the enforcement reference for module responsibility and dependency rules
in `exeris-spring-runtime`. Violations of these rules are architectural defects, not
style suggestions.

---

## Module Responsibility Matrix

### `exeris-spring-boot-autoconfigure`

**Role:** Boot integration only — the thinnest possible layer.

Allowed:
- `@ConfigurationProperties` classes for all runtime config
- `@Configuration` classes for bean wiring
- `SmartLifecycle` implementations for lifecycle coordination
- `@Conditional*` guards for classpath-driven activation
- Property metadata (`spring-configuration-metadata.json`)
- Bootstrap sequencing hooks

Forbidden:
- Transport binding or connection accept logic
- Request processing or dispatch logic
- Transaction ownership
- Persistence access
- Heavy compatibility bridging (`DispatcherServlet`, `HandlerMapping`)
- Cross-module utility dumping ground behaviour

Rule: If a class in this module exceeds 100 lines of logic (excluding properties), it is a smell.
Rule: `HttpExchange` imports belong in `web`. `HttpHandler` imports are allowed in
autoconfigure only for conditional activation and optional lifecycle wiring, not for
request-path processing.

---

### `exeris-spring-runtime-web`

**Role:** The primary integration boundary between Exeris transport and Spring application logic.

Allowed (Pure Mode):
- `HttpHandler` implementation: receives `HttpExchange`, invokes Spring handler bean, writes response
- `ExerisRouteRegistry`: maps URI patterns to Spring-managed handler beans
- `ExerisServerRequest` / `ExerisServerResponse`: thin, non-copying request/response view
- Codec integration: body read/write against `LoanedBuffer` without heap copy in primary path
- Error mapping: `ExerisKernelException` → HTTP status + error body
- `HttpServerEngine` lifecycle start/stop coordination
- Mode guard: `@ConditionalOnPureMode` / `@ConditionalOnCompatibilityMode`

Allowed (Compatibility Mode, Phase 2 only):
- `ExerisDispatcherBridge`: bridges Exeris `HttpExchange` to a reduced Spring MVC dispatch path
- Argument resolver adaptation (scoped subset only)
- Exception handler mapping to `@ExceptionHandler`

Forbidden:
- `jakarta.servlet.*` imports in pure mode code paths
- `DispatcherServlet` activation in pure mode
- `ServerWebExchange` or Project Reactor imports (even in compatibility mode)
- ThreadLocal as context carrier
- Body copy from `LoanedBuffer` to `byte[]` in the primary response path
- Transaction logic (belongs in `tx`)
- Persistence access (belongs in `data`)
- Actuator concerns (belongs in `actuator`)

Rule: Compatibility mode classes must be in a dedicated sub-package `*.compat.*` and must carry
a `@CompatibilityMode` marker annotation. Pure mode paths must not touch compat packages.

---

### `exeris-spring-runtime-tx`

**Role:** Transaction and resource-boundary bridge.

Allowed:
- `PlatformTransactionManager` implementation backed by `PersistenceConnection` lifecycle
- `TransactionSynchronization` hooks for resource cleanup
- ScopedValue-based transaction context propagation
- `@Transactional` support via Spring AOP (only after architectural decision in Phase 3)
- Resource coordinator logic between Exeris `StorageContext` and Spring transaction state

Forbidden:
- Web handler logic
- Codec or serialisation logic
- Actuator or diagnostics concerns
- `ThreadLocal` as transaction context carrier
- `DataSource` / `HikariCP` ownership (that belongs in `data` if justified)
- Full JPA/Hibernate transaction model emulation without explicit ADR approval

---

### `exeris-spring-runtime-data`

**Role:** Optional, high-risk persistence integration. Treat every addition here with maximum scrutiny.

Allowed (with explicit ADR approval per feature):
- `DataSource` adapter backed by `PersistenceEngine` (thin bridge only)
- JDBC compatibility surface for frameworks that cannot be adapted otherwise
- Exeris-native repository implementation against `PersistenceEngine` + `QueryExecutor`
- Connection lifecycle management delegated to Exeris `ConnectionFactory`

Forbidden:
- HikariCP as the canonical connection pool (Exeris owns connection lifecycle)
- JPA/Hibernate as a supported first-class path (see Phase 3 risk analysis)
- Any design that makes the system orbit around `DataSource.getConnection()` as the primary
  persistence paradigm while Exeris is claimed to be the runtime owner
- Web logic
- Transaction manager logic (belongs in `tx`)
- Actuator concerns

Rule: Every public class added to this module requires a comment referencing the ADR or Phase 3
plan entry that justifies its existence.

---

### `exeris-spring-runtime-actuator`

**Role:** Operational visibility — health, metrics, diagnostics, graceful shutdown.

Allowed:
- `HealthIndicator` implementations reading from `KernelProviders` ScopedValue slots
- `InfoContributor` exposing runtime mode, provider names, and kernel version
- `MeterBinder` bridging `TelemetrySink` counters/timers to Micrometer `MeterRegistry`
- `@Endpoint` for runtime diagnostics (watermark state, memory pressure, PAQS stats)
- `SmartLifecycle` coordination for graceful shutdown ordering

Forbidden:
- Redefining the Exeris telemetry model or replacing `TelemetrySink`
- Owning any data-plane execution path
- Acting as a runtime configuration control plane (config changes do not go through actuator)
- Cross-module shared utility code with no actuator-specific purpose
- Transport logic

---

## Cross-Module Dependency Rules

```
                          depends on
autoconfigure  ─────────────────────────────► kernel-spi
autoconfigure  ─────────────────────────────► kernel-core
autoconfigure  ─────────────────────────────► spring-boot-autoconfigure
autoconfigure  ─────────────────────────────► spring-context

web            ─────────────────────────────► kernel-spi
web            ─────────────────────────────► kernel-core
web            ─────────────────────────────► autoconfigure
web            ─────────────────────────────► spring-web  (model only, not MVC)

tx             ─────────────────────────────► kernel-spi
tx             ─────────────────────────────► spring-tx
tx             ─────────────────────────────► spring-context

data           ─────────────────────────────► kernel-spi
data           ─────────────────────────────► spring-tx

actuator       ─────────────────────────────► kernel-spi
actuator       ─────────────────────────────► autoconfigure
actuator       ─────────────────────────────► spring-boot-actuator-autoconfigure
actuator       ─────────────────────────────► micrometer-core (optional)
```

### Prohibited edges (architectural violations)

| From | To | Reason |
|:-----|:---|:-------|
| ANY | `eu.exeris.kernel.spi.*` (Spring types) | The Wall — kernel SPI must remain Spring-free |
| ANY | `eu.exeris.kernel.core.*` (Spring types) | The Wall — kernel Core must remain Spring-free |
| `autoconfigure` | `web` / `tx` / `data` | Boot layer must not own runtime logic |
| `web` | `data` | Web must not own persistence |
| `data` | `web` | Persistence must not own transport |
| `actuator` | `web` (data-plane) | Actuator must not become a runtime execution path |
| `tx` | `web` | Transaction bridge must not own transport |

---

## Classpath Exclusion Enforcements

The following dependencies must NEVER appear on the effective compile or runtime classpath
in any module of this project when operating in pure mode:

| Banned dependency | Why |
|:-----------------|:----|
| `org.apache.tomcat.embed:*` | Tomcat is not the ingress in Exeris host-runtime mode |
| `org.eclipse.jetty:*` | Same |
| `io.undertow:*` | Same |
| `io.netty:*` | Netty is not the runtime in Exeris host-runtime mode |
| `io.projectreactor:*` | Reactive programming model is not the execution model |
| `jakarta.servlet:jakarta.servlet-api` | Servlet API is not the request model in pure mode |
| `com.zaxxer:HikariCP` | Exeris owns connection lifecycle, not HikariCP |

CI enforcement via `maven-enforcer-plugin` banned-dependencies rules is planned/phase-gated
and should be treated as an explicit delivery target until configured in the root reactor.

---

## Architecture Guard Tests

Each module should maintain at least one `ArchitectureTest` class (using ArchUnit or equivalent)
that verifies the following rules at test time:

- No class in `eu.exeris.spring.*` imports any type from `eu.exeris.kernel.core.*` 
  internal packages (only public SPI types allowed).
- No class in `eu.exeris.spring.runtime.web` imports `jakarta.servlet.*` (pure mode packages).
- No class in `eu.exeris.spring.runtime.web` imports `io.projectreactor.*`.
- No class in `eu.exeris.spring.boot.autoconfigure` imports `eu.exeris.spring.runtime.web.*`.
- Compatibility mode classes are isolated in `*.compat.*` sub-packages and annotated.
