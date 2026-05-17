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

### `exeris-spring-runtime-events`

**Role:** Compatibility-friendly bridge between Spring beans and the kernel `EventBus`
for publish/subscribe — never the canonical event owner. (Phase 4A — 1.0 preview, default-off
via `exeris.runtime.events.enabled`.)

Allowed:
- `EventEngine`, `EventBus`, `EventDescriptor`, `EventPayload`, `EventHandler`,
  `EventRegistry`, `EventTypeSpec`, `SubscriptionToken` (kernel SPI types)
- `@ExerisEventListener` annotation
- `ExerisEventTypeRegistry`, `ExerisEventPublisher`, `ExerisEventListenerRegistrar`
- `ExerisEventAutoConfiguration`
- `EventEngineSupplier` lazy seam over the engine reference captured by
  `ExerisRuntimeLifecycle` during bootstrap

Forbidden:
- Wiring Spring `ApplicationEventPublisher` (or `org.springframework.context.event.*`)
  into the Exeris `EventBus` — kernel/Spring buses must stay separate
- Implicitly bridging Spring `@EventListener` beans to the Exeris bus
- Kafka / outbox / queue infrastructure (belongs in `data` or a future outbox module)
- HTTP / request-path logic
- Transaction or persistence semantics

Activation: explicit opt-in only. `matchIfMissing = false`. Promotion to 1.0 preview does not
weaken this rule — applications must set `exeris.runtime.events.enabled=true`.

---

### `exeris-spring-runtime-flow`

**Role:** Compatibility-friendly bridge between Spring beans and the kernel `FlowEngine`
for structured-execution flows (schedule, park, wake, lookup) and event-driven choreography
mappers — never an alternative flow scheduler. (Phase 4B — 1.0 preview, default-off via
`exeris.runtime.flow.enabled`.)

Allowed:
- `FlowEngine`, `FlowExecutionPlan`, `FlowStepAction`, `FlowChoreographyMapper`,
  `ChoreographyDecision`, `FlowEngineCapabilities`, `SubscriptionToken` (kernel SPI types)
- `ExerisFlowDefinition` interface and `ExerisFlowChoreographyMapper` interface
- `ExerisFlowTemplate`, `ExerisFlowDefinitionRegistrar`, `ExerisFlowChoreographyBridge`
- `ExerisFlowAutoConfiguration`, `ExerisFlowProperties`
- `FlowEngineSupplier` lazy seam over the engine reference captured by
  `ExerisRuntimeLifecycle` during bootstrap
- `EventEngineSupplier` consumed from `events` (one-way `flow → events` dependency only —
  needed for choreography to read the kernel `EventBus`)

Forbidden:
- Wiring Spring `ApplicationEventPublisher` / `org.springframework.context.event.*` into
  the kernel `FlowEngine` — choreography reads from the kernel `EventBus` via the events
  module bridge, never from Spring's bus
- Implementing an alternative flow scheduler in Spring (`@Async`, `TaskExecutor`,
  `ThreadPoolTaskScheduler`) — kernel `FlowScheduler` owns flow execution; `@Async` is
  explicitly NOT a workaround for a missing flow capability
- Direct JDBC, JPA, or `DataSource` access — flow persistence (when re-enabled in a later
  step) lands via the kernel's `FlowSnapshotStore` SPI, not via this module
- HTTP / request-path types — flows are not request handlers
- Transaction-package types (`org.springframework.transaction.*`, `java.sql.*`) — tx
  belongs in `tx`; flow steps interact with persistence only through constructor-injected
  ports
- Choreography mapper bodies pulling request-path / tx / persistence types directly
  (enforced by `FlowModuleBoundaryTest.choreographyMapperImplementorsDoNotImportRequestPathOrTxPackages`)

Activation: explicit opt-in only. `matchIfMissing = false`. The choreography sub-bridge
(Step 3) additionally requires `exeris.runtime.flow.choreography-enabled=true` AND an
active events module AND kernel `FlowEngineCapabilities.choreographySupport() = true` —
the capability gate fails loud (never silently skips) because the user has explicitly
opted in.

---

### `exeris-spring-runtime-graph`

**Role:** Spring-side seam over the kernel `GraphEngine` / `GraphSession` SPI for
graph-shaped queries — never a re-implementation of graph algorithms in Spring. (Phase 4C —
1.0 preview, default-off via `exeris.runtime.graph.enabled`; ADR-030.)

Allowed:
- `GraphEngine`, `GraphSession`, `GraphTraversal`, `GraphNodeDescriptor`, `GraphEdgeDescriptor`,
  `GraphDialect`, `PathResult`, `LoanedBuffer` (kernel SPI types)
- `ExerisGraphTemplate` (JdbcTemplate-shaped facade), `ExerisGraphSessionCallback`
- `@ExerisGraphQuery` annotation + `ExerisGraphQueryProcessor` (`BeanPostProcessor`)
- `ExerisGraphAutoConfiguration`, `ExerisGraphProperties` (`enabled` + `require-engine`)
- `GraphEngineSupplier` lazy seam (mirrors `EventEngineSupplier` / `FlowEngineSupplier`) over
  the engine reference captured by `ExerisRuntimeLifecycle` during bootstrap

Forbidden:
- Spring Data Neo4j / Spring Data repository abstractions — `@ExerisGraphQuery` is a thin
  declarative wrapper, not a repository surface (`org.springframework.data..` banned by
  `GraphModuleBoundaryTest`)
- Fluent `GraphQueryBuilder` DSL — kernel ships `GraphTraversal` record only; the Spring
  side does not double the maintenance surface with a transpiling builder (ADR-030
  §"What is NOT in scope")
- `GraphCursor` / unbounded traversal API — kernel "Planned — not yet implemented" per
  `exeris-kernel/docs/subsystems/graph.md:149`; the Spring seam will expose it
  automatically when the kernel ships it, but does not ship a placeholder ahead of the
  kernel surface
- Multi-engine fan-out — one `GraphEngine` per `ApplicationContext`; multi-tenant fan-out
  via Spring proxies is a 1.0.x candidate at earliest
- Cross-resource transactions (`@Transactional` spanning kernel graph + JDBC) — the
  `GraphSession` transaction is a kernel-local resource distinct from
  `ExerisPlatformTransactionManager`; cross-resource composition is an ADR-NNN follow-up
- HTTP / request-path types — graph operations are independent of the HTTP request path
  (handlers may inject `@Autowired ExerisGraphTemplate`, but the template carries no
  request-context coupling itself)
- Direct community-driver imports on production scope — `eu.exeris.kernel.community..` is
  test-scope only; concrete drivers (PostgreSQL PGQ, Neo4j Bolt, Memgraph Bolt) stay
  black-box per `feedback_community_kernel_public_surface.md`
- `@RequiresRole` (ADR-014) integration on `@ExerisGraphQuery` methods — RBAC on graph
  queries is a kernel-side concern, not the Spring-side seam

Activation: explicit opt-in only. `matchIfMissing = false`. The two-property matrix
distinguishes "feature unused" (`enabled=false`, default) from "feature configured but
kernel absent" (`enabled=true` + `require-engine=true`, fail-loud at first template use)
from the test/dev escape hatch (`enabled=true` + `require-engine=false`, template still
constructed but every method throws until an engine becomes available). GA graduation is
**kernel-gated** on `GraphChurnRatioTck` Community binding green in CI (kernel Sprint 7).

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

events         ─────────────────────────────► kernel-spi
events         ─────────────────────────────► autoconfigure
events         ─────────────────────────────► spring-boot-autoconfigure
events         ─────────────────────────────► spring-context

flow           ─────────────────────────────► kernel-spi
flow           ─────────────────────────────► autoconfigure
flow           ─────────────────────────────► events            (one-way; choreography reads EventBus)
flow           ─────────────────────────────► spring-boot-autoconfigure
flow           ─────────────────────────────► spring-context

graph          ─────────────────────────────► kernel-spi
graph          ─────────────────────────────► autoconfigure
graph          ─────────────────────────────► spring-boot-autoconfigure
graph          ─────────────────────────────► spring-context
graph          ─────────────────────────────► kernel-community  (test scope only)
```

### Prohibited edges (architectural violations)

| From | To | Reason |
|:-----|:---|:-------|
| ANY | `eu.exeris.kernel.spi.*` (Spring types) | The Wall — kernel SPI must remain Spring-free |
| ANY | `eu.exeris.kernel.core.*` (Spring types) | The Wall — kernel Core must remain Spring-free |
| `autoconfigure` | `web` / `tx` / `data` / `events` | Boot layer must not own runtime logic |
| `web` | `data` | Web must not own persistence |
| `data` | `web` | Persistence must not own transport |
| `actuator` | `web` (data-plane) | Actuator must not become a runtime execution path |
| `tx` | `web` | Transaction bridge must not own transport |
| `events` | `org.springframework.context.event..` | Kernel/Spring buses stay separate |
| `events` | `org.springframework.context.ApplicationEventPublisher` | Same — never wire Spring's bus into the Exeris bus |
| `events` | `web` / `tx` / `data` / `actuator` | Events module must not pull in unrelated module concerns |
| `events` | `flow` | Dependency direction is one-way (`flow → events`); reversing it creates a cycle |
| `flow` | `org.springframework.context.event..` | Choreography reads kernel `EventBus` via the events module, never Spring's bus |
| `flow` | `org.springframework.context.ApplicationEventPublisher` | Same — never wire Spring's bus into the kernel `FlowEngine` |
| `flow` | `org.springframework.scheduling..` / `org.springframework.core.task..` | Kernel `FlowScheduler` owns execution; `@Async` is not a flow workaround |
| `flow` | `web` / `tx` / `data` / `actuator` | Flow module must not pull in unrelated module concerns |
| `flow` | `java.sql..` / `javax.sql..` / `jakarta.persistence..` / `org.hibernate..` | Flow persistence lands via kernel `FlowSnapshotStore` SPI, not direct JDBC/JPA |
| `graph` | `org.springframework.data..` | Spring Data Neo4j compatibility is explicitly out of scope (ADR-030); `@ExerisGraphQuery` is a thin declarative wrapper, not a repository surface |
| `graph` | `org.springframework.web..` | Graph operations are not request-path-bound; handlers inject the template but the template carries no request-context coupling |
| `graph` | `eu.exeris.kernel.community..` (production scope) | Concrete drivers stay test-scope only; production classpath consumes only kernel SPI |
| `graph` | `web` / `tx` / `data` / `actuator` | Graph module must not pull in unrelated module concerns |

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
