# Kernel Integration Seams

This is the primary engineering reference for how Exeris kernel SPI interfaces
map to Spring integration points. Engineers implementing features in this
repository must read this document first.

**Kernel version:** 0.5.0-SNAPSHOT  
**Kernel package root:** `eu.exeris.kernel.spi.*`

---

## Overview

The Exeris kernel exposes integration through nine primary SPI categories
(Seams 1–9 below). Each one has a corresponding integration strategy in this repository.

```
Kernel SPI               →    Integration Layer                  →    Spring Side
─────────────────────────────────────────────────────────────────────────────────────
HttpHandler              →    ExerisHttpDispatcher               →    Spring bean handler
HttpExchange             →    ExerisServerRequest/Response       →    HandlerMethod args (Phase 2)
ConfigProvider           →    ExerisSpringConfigProvider         →    Spring Environment
SubsystemProvider        →    SpringSubsystemBridge              →    ApplicationContext lifecycle
KernelProviders          →    ExerisContextHolder                →    ScopedValue propagation
TelemetryProvider        →    ExerisActuatorTelemetryBridge      →    Micrometer MeterRegistry
EventEngine / EventBus   →    ExerisEventPublisher/Registrar     →    @ExerisEventListener beans
FlowEngine               →    ExerisFlowTemplate/Registrar       →    @ExerisFlowDefinition beans
FlowChoreographyMapper   →    ExerisFlowChoreographyBridge       →    ExerisFlowChoreographyMapper beans
GraphEngine / GraphSession →  ExerisGraphTemplate                →    @ExerisGraphQuery beans
PersistenceProvider      →    ExerisPersistenceAdapter           →    DataSource / @Transactional
```

---

## Seam 1: HTTP Handler Bridge

**Kernel SPI:** `eu.exeris.kernel.spi.http.HttpHandler`

```java
@FunctionalInterface
public interface HttpHandler {
    void handle(HttpExchange exchange) throws HttpException;
}
```

**Contract:**
- Called on a virtual thread (one per request).
- Must use exactly one completion path: either call `exchange.respond(response)` once and return, or throw `HttpException` and let the engine respond.
- Must never both respond and throw for the same request.
- Must not use `ThreadLocal`. Context propagation via `ScopedValue`.
- Throwing `HttpException` → engine sends error response.

**Integration strategy (Pure Mode):**

`ExerisHttpDispatcher` implements `HttpHandler` and is registered with the kernel's
`HttpServerEngine`. On each invocation it:

1. Reads `exchange.request()` to get `HttpRequest` (header map, method, path, body `LoanedBuffer`).
2. Resolves the matching Spring-managed handler bean via `ExerisRouteRegistry`.
3. Invokes the handler bean's designated method.
4. Completes exactly once: writes result/error to `exchange.respond(response)` and returns, or throws `HttpException` for engine-owned error response.

No `DispatcherServlet`, no `HandlerMapping`, no servlet API involved in pure mode.

```
HttpExchange arrives on VT
    → ExerisHttpDispatcher.handle(exchange)
        → ExerisRouteRegistry.resolve(path, method)
        → handler.invoke(ExerisServerRequest)
        → ExerisResponseBuilder → HttpResponse
        → exchange.respond(response)       ← ownership of LoanedBuffer transferred to engine
```

**Integration strategy (Compatibility Mode, Phase 2):**

`ExerisCompatDispatcher` wraps the above and additionally:

1. Uses Spring handler infrastructure directly (`RequestMappingHandlerMapping` + `RequestMappingHandlerAdapter`) without `DispatcherServlet` as a canonical runtime component.
2. Adapts `HttpRequest`/`HttpResponse` through compatibility request/response adapters for controller invocation.
3. Maps supported return types (for Phase 2 scope, including `ResponseEntity`) back to `HttpResponse`.

This path is in `eu.exeris.spring.runtime.web.compat.*` and activates only when
`exeris.runtime.web.mode=compatibility` is set.

---

## Seam 2: HTTP Exchange — Request and Response

**Kernel SPI:**
- `eu.exeris.kernel.spi.http.HttpExchange`
- `eu.exeris.kernel.spi.http.HttpRequest`
- `eu.exeris.kernel.spi.http.HttpResponse`
- `eu.exeris.kernel.spi.memory.LoanedBuffer` (body carrier)

**Key facts about `HttpRequest`:**
- Fully parsed before `handle()` is called.
- `body()` returns a `LoanedBuffer` — an off-heap memory reference.
- The handler must release or transfer ownership of the body buffer.

**Key facts about `HttpResponse`:**
- `body()` returns a `LoanedBuffer` — the engine takes ownership on respond.
- Caller must NOT release the buffer after calling `exchange.respond(response)`.

**Integration rule: no body copy in pure mode.**

The `ExerisServerRequest` thin view must provide body access via `LoanedBuffer.segment()`
without copying to `byte[]` or `InputStream`. Codec integration must operate on
`MemorySegment` directly.

Body copy to heap is only acceptable in:
- Compatibility mode (explicitly documented per usage).
- Mulipart handling where byte[] is structurally unavoidable (with allocation counted).

---

## Seam 3: Configuration Bridge

**Kernel SPI:** `eu.exeris.kernel.spi.config.ConfigProvider`

Loaded via `ServiceLoader` by `KernelBootstrap` in `exeris-kernel-core`.
The provider with the highest `priority()` value wins.

**Integration strategy:**

`ExerisSpringConfigProvider implements ConfigProvider` maps Spring `Environment`
property sources to the kernel config model.

```
ServiceLoader.load(ConfigProvider.class)
    → finds ExerisSpringConfigProvider (priority = 150)
    → ExerisSpringConfigProvider.kernelSettings()
        → reads exeris.runtime.* from Spring Environment
        → returns KernelSettings record
```

Priority convention:
- Community `ConfigProvider`: priority 100
- `ExerisSpringConfigProvider`: priority 150 (takes precedence over community defaults)

The `ExerisSpringConfigProvider` is registered in:
`META-INF/services/eu.exeris.kernel.spi.config.ConfigProvider`

This makes Spring the configuration source for the kernel — without making the kernel
aware of Spring. The kernel only knows about `ConfigProvider`.

---

## Seam 4: Lifecycle / Subsystem Bridge

**Kernel SPI:** `eu.exeris.kernel.spi.bootstrap.SubsystemProvider` / `Subsystem`

**Two complementary strategies:**

### Strategy A — Spring coordinates Exeris bootstrap (preferred for Phase 0/1)

Spring `ApplicationContext` starts first. `ExerisRuntimeLifecycle implements SmartLifecycle`
triggers `KernelBootstrap.start()` at the appropriate Spring lifecycle phase.

```
Spring refresh() completes
    → SmartLifecycle.start() ordered after bean wiring
    → ExerisRuntimeLifecycle.start()
        → KernelBootstrap.bootstrap(configProvider)
        → ServiceLoader discovers SubsystemProviders
        → all subsystems initialise in DAG order
        → KERNEL READY
    → ExerisHttpDispatcher registered with HttpServerEngine
    → HttpServerEngine.start() binds port
```

Shutdown:
```
JVM shutdown hook / Spring close()
    → SmartLifecycle.stop() in reverse order
    → HttpServerEngine.stop() (no new connections)
    → in-flight requests drain (timeout)
    → KernelBootstrap.shutdown()
    → all subsystems cleaned up in reverse DAG order
```

### Strategy B — Exeris as a SubsystemProvider (advanced, Phase 3+)

A Spring-facing subsystem can implement `SubsystemProvider` to register itself into the
kernel DAG (e.g., a Spring-managed event bus as an `EventProvider`). This uses
`ServiceLoader` canonically — Spring does not replace discovery but may supply beans
that implement kernel SPI contracts.

### Sub-seam 4a: Subsystem selection (`BootstrapSelector`)

**Kernel SPI:** `eu.exeris.kernel.spi.bootstrap.BootstrapSelector`

**Mode:** MIXED — selector applies at bootstrap, upstream of web-mode selection. The
same selector value is honoured whether the application runs in Pure Mode or
Compatibility Mode.

The kernel exposes `BootstrapSelector` — a record with `all()`, `none()`, and
`forNames(String...)` factories — for restricting the active subsystem set. The
Spring property `exeris.runtime.subsystems` is bound to a `List<String>` in
`ExerisRuntimeProperties` and propagated to `KernelBootstrap.Builder.selector(...)` by
`ExerisRuntimeLifecycle`:

| Property value | Selector effect | Kernel behaviour |
|:---|:---|:---|
| Unset or empty list (default) | `.selector(...)` not called → builder default | All community subsystems boot (memory, crypto, persistence, events, graph, transport, http, flow) |
| Non-empty list (`memory,crypto,persistence,events,flow`) | `BootstrapSelector.forNames(...)` | Only the named subsystems boot; unlisted subsystems are not started |

Property binding (Spring Boot relaxed): both `exeris.runtime.subsystems=memory,crypto`
and indexed `exeris.runtime.subsystems[0]=memory` / `[1]=crypto` are honoured. Names
are trimmed and blank entries are dropped at binding time; ordering is preserved so
startup logs read predictably. The kernel fails-fast on unknown names — the Spring
layer is a transparent pass-through and does not validate against the community
subsystem name set.

When a non-empty list is bound, `ExerisRuntimeLifecycle` logs an INFO line at startup
naming the requested subset, so operators can correlate the deployment shape with the
actual boot output (`SubsystemOrchestrator` already echoes `Selector=[...]` in its
startup log line as a second corroborating signal).

Typical deployment shapes:
- **Headless batch worker:** `subsystems=memory,crypto,persistence,events,flow` (no
  `transport`/`http` → no listening port; the lifecycle still boots cleanly).
- **HTTP-only worker:** `subsystems=memory,crypto,transport,http` (no `persistence`,
  no `events`/`flow` → no DB connection, no kernel event bus; the Spring
  events/flow bridges' tolerant posture handles the absent engines via their existing
  supplier seams).
- **Full default:** unset → all community subsystems.

Depending Spring beans (the events bridge, the flow bridge, the choreography bridge)
must already tolerate engine absence via their `EventEngineSupplier` /
`FlowEngineSupplier` seams; selecting a restricted set is the use case these tolerant
postures exist for. Strict-posture beans (`require-engine=true`) will fail loud at
startup if their underlying subsystem is not in the selection — exactly the same
behaviour as a missing provider on the classpath.

---

## Seam 5: Context Propagation

**Kernel SPI:** `eu.exeris.kernel.spi.context.KernelProviders`

`KernelProviders` contains `ScopedValue` slots for all runtime providers. They are bound
once during bootstrap and inherited by all virtual threads in scope.

**Key slots relevant to this integration:**

| Slot | Type | Spring integration |
|:-----|:-----|:------------------|
| `KernelProviders.TELEMETRY_SINKS` | `List<TelemetrySink>` | Read by actuator bridge |
| `KernelProviders.PERSISTENCE_ENGINE` | `PersistenceEngine` | Read by tx/data modules |
| `KernelProviders.HTTP_PROVIDER` | `HttpProvider` | Accessed by web module for engine ref |

**ScopedValue vs ThreadLocal rule:**

This integration layer must NEVER use `ThreadLocal` on any path that runs on virtual threads.
Violation: Thread pinning + context leakage across VT continuations.

Spring's `SecurityContextHolder`, `TransactionSynchronizationManager`, and `RequestContextHolder`
all default to `ThreadLocal`. These must be bridged only in compatibility mode and must be
explicitly isolated per request scope.

**Compatibility mode context bridge (Phase 2/3):**

`ExerisThreadLocalBridge` wraps handler invocations in compatibility mode to:
1. Bind `SecurityContext` to `ThreadLocal` for the duration of the handler call.
2. Clear it deterministically after handler return or exception.
3. This is isolated in `eu.exeris.spring.runtime.web.compat.context.*`.

---

## Seam 6: Telemetry Bridge

**Kernel SPI:**
- `eu.exeris.kernel.spi.telemetry.TelemetryProvider`
- `eu.exeris.kernel.spi.telemetry.TelemetrySink`
- `eu.exeris.kernel.spi.telemetry.KernelEvent`

**Integration strategy:**

`ExerisActuatorTelemetryBridge implements MeterBinder` reads from the `TelemetrySink`
available via `KernelProviders.TELEMETRY_SINKS` and bridges kernel metrics (counters,
gauges, latencies) to Micrometer `MeterRegistry`.

```
Micrometer MeterRegistry (Actuator)
    ← ExerisActuatorTelemetryBridge.bindTo(registry)
        ← KernelProviders.TELEMETRY_SINKS.get()
            → TelemetrySink.counters() etc.
```

The `TelemetrySink` is the Exeris telemetry model. The actuator module must not replace it,
redefine event envelopes, or bypass `KernelEvent` encoding. It may only read and translate.

---

## Seam 7: Events Bridge (Phase 4A)

**Kernel SPI:**
- `eu.exeris.kernel.spi.events.EventEngine`
- `eu.exeris.kernel.spi.events.EventBus`
- `eu.exeris.kernel.spi.events.EventDescriptor`
- `eu.exeris.kernel.spi.events.EventPayload`
- `eu.exeris.kernel.spi.events.EventHandler`
- `eu.exeris.kernel.spi.events.EventTypeSpec`
- `eu.exeris.kernel.spi.context.KernelProviders.EVENT_ENGINE`

**Integration strategy:**

The events module bridges Spring beans to the kernel `EventBus` *without* wiring Spring's
`ApplicationEventPublisher` into it. The two buses stay separate by architectural rule —
`@ExerisEventListener`-annotated methods subscribe to kernel types, `@EventListener`-annotated
methods receive Spring application events; a single bean may carry both annotations on
different methods, but the two pipes never join.

```
Spring beans
    ← ExerisEventListenerRegistrar (SmartLifecycle, phase MAX_VALUE - 99)
        → EventBus.subscribe(typeName, handler) — handlers invoked on kernel dispatch path
    ← ExerisEventPublisher
        → EventBus.publish(descriptor, payload) — payload ownership transferred to bus
```

`EventEngineSupplier` (a deferred `ScopedValue` accessor wrapping
`KernelProviders.EVENT_ENGINE`) is the single seam through which Spring beans reach the
engine — beans never read the `ScopedValue` slot directly.

---

## Seam 8: Flow / Saga Bridge (Phase 4B)

**Kernel SPI:**
- `eu.exeris.kernel.spi.flow.FlowEngine`
- `eu.exeris.kernel.spi.flow.FlowExecutionPlanFactory`
- `eu.exeris.kernel.spi.flow.FlowScheduler`
- `eu.exeris.kernel.spi.flow.FlowDefinitionBuilder`
- `eu.exeris.kernel.spi.flow.model.FlowDefinition` / `FlowExecutionPlan` / `FlowContext` / `FlowOutcome`
- `eu.exeris.kernel.spi.flow.FlowEngineCapabilities`
- `eu.exeris.kernel.spi.flow.FlowChoreographyMapper` / `ChoreographyDecision` *(Step 3)*
- `eu.exeris.kernel.spi.context.KernelProviders.FLOW_ENGINE`

**Integration strategy:**

The flow module exposes the kernel `FlowEngine` as a structured asynchronous / saga
programming model — the recommended replacement for Spring `@Async` whenever a unit of
work needs compensation, park/wake, or event-driven invocation. Two surfaces, both opt-in
behind `exeris.runtime.flow.enabled=true`:

```
Spring beans declaring saga shapes
    ← ExerisFlowDefinitionRegistrar (SmartLifecycle, phase MAX_VALUE - 99)
        → engine.plans().newDefinition(name) → bean.define(builder) → engine.plans().compile(def)
        → ExerisFlowTemplate.registerPlan(name, plan)

Imperative invocation
    ← ExerisFlowTemplate
        → engine.scheduler().schedule(plan, ctx) | park | wake | lookupParked
```

`FlowEngineSupplier` (a deferred `ScopedValue` accessor wrapping
`KernelProviders.FLOW_ENGINE`) is the single seam — same pattern as `EventEngineSupplier`.
Step lambdas execute on kernel-owned virtual threads; closing over a Spring bean is fine,
but step bodies must call collaborators by interface, not couple to HTTP / transaction /
JDBC packages directly (architecture guard enforces this).

### Sub-seam 8a: Choreography Ladder (Step 3)

When `exeris.runtime.flow.choreography-enabled=true` and the events module is active,
`ExerisFlowChoreographyBridge` discovers `ExerisFlowChoreographyMapper` beans and registers
each with the kernel via `FlowEngine.registerChoreographyMapper(mapper, eventTypeNames, bus)`.
The kernel owns the resulting subscriptions and cancels them on `engine.close()`.

```
Spring choreography mapper beans (ExerisFlowChoreographyMapper)
    ← ExerisFlowChoreographyBridge (SmartLifecycle, phase MAX_VALUE - 98)
        → flowEngine.registerChoreographyMapper(mapper, eventTypeNames, eventBus)
            → for each name: bus.subscribe(name, kernel-internal handler that calls mapper.map())
                → mapper.map(EventDescriptor) returns
                    ChoreographyDecision.Ignore | Wake(uuid) | Start(plan, uuid)
```

Phase ordering: registrars sit at `MAX_VALUE - 99` (flow definitions, event listeners) so
that plans are compiled and listeners subscribed before the choreography bridge wires
mappers at `MAX_VALUE - 98`. The kernel lifecycle itself sits at `MAX_VALUE - 100`, so
both `FlowEngine` and `EventEngine` references are captured by the time any of these
`SmartLifecycle.start()` calls fire.

Three gates guard activation, in order:
1. `exeris.runtime.flow.choreography-enabled=true` (opt-in property; default false).
2. `ExerisEventPublisher` bean present (events module active).
3. `flowEngine.capabilities().choreographySupport()` returns true at `start()` (capability
   gate — checked at lifecycle start, not at bean construction, because capabilities cannot
   be probed until the kernel has booted). Failure here is always loud: the user explicitly
   opted in and a tier without the capability cannot honour the contract.

Mappers receive only the `EventDescriptor` (routing metadata) — no payload, no scheduler
reference. Payload-based logic belongs in `@ExerisEventListener` methods on the events
module side. This keeps choreography mappers implementation-blind to broker types and
makes the choreography decision a routing decision, not an event-handling decision.

---

## Seam 9: Persistence Bridge (Phase 3)

**Kernel SPI:**
- `eu.exeris.kernel.spi.persistence.PersistenceProvider`
- `eu.exeris.kernel.spi.persistence.PersistenceEngine`
- `eu.exeris.kernel.spi.persistence.ConnectionFactory`
- `eu.exeris.kernel.spi.persistence.PersistenceConnection`
- `eu.exeris.kernel.spi.persistence.QueryExecutor`
- `eu.exeris.kernel.spi.persistence.QueryResult`
- `eu.exeris.kernel.spi.security.StorageContext`

**Integration strategy (Phase 3):**

Two-level approach:

**Level 1 — Native Exeris Repositories (preferred):**

Application code implements repository interfaces against `PersistenceEngine` + `QueryExecutor`
directly. No `DataSource`. No `JdbcTemplate`. Spring provides the bean lifecycle; Exeris
provides the execution semantics.

**Level 2 — JDBC Compatibility Bridge (last resort, ADR required):**

`ExerisDataSource implements javax.sql.DataSource` wraps `ConnectionFactory` to provide
JDBC compatibility for frameworks that cannot otherwise adapt. This is explicitly the
compatibility layer and must never be presented as the canonical persistence path.

**Transaction integration:**

`ExerisPlatformTransactionManager implements PlatformTransactionManager` binds
`PersistenceConnection` to the current virtual thread scope for the duration of a
Spring `@Transactional` method, using `ScopedValue` rather than `TransactionSynchronizationManager`
`ThreadLocal` where possible. See `exeris-spring-runtime-tx` for the implementation plan.

---

## Summary Table

| Kernel SPI | Spring concept | Integration class | Module | Phase |
|:-----------|:--------------|:-----------------|:-------|:------|
| `HttpHandler` | Handler bean invocation | `ExerisHttpDispatcher` | `web` | 1 |
| `HttpExchange` | Request/Response model | `ExerisServerRequest`, `ExerisServerResponse` | `web` | 1 |
| `ConfigProvider` | `Environment` / `@ConfigurationProperties` | `ExerisSpringConfigProvider` | `autoconfigure` | 0 |
| `SubsystemProvider` | `SmartLifecycle` | `ExerisRuntimeLifecycle` | `autoconfigure` | 0/1 |
| `KernelProviders` | Context propagation | `ExerisContextHolder` (ScopedValue) | `web` | 1 |
| `TelemetryProvider` / `TelemetrySink` | Micrometer / Actuator | `ExerisActuatorTelemetryBridge` | `actuator` | 1/2 |
| `EventEngine` / `EventBus` | Spring beans publish/subscribe | `ExerisEventPublisher`, `ExerisEventListenerRegistrar`, `ExerisEventTypeRegistry` | `events` | 4A |
| `FlowEngine` / `FlowScheduler` | Saga / structured async | `ExerisFlowTemplate`, `ExerisFlowDefinitionRegistrar` | `flow` | 4B Steps 1–2 |
| `FlowChoreographyMapper` / `ChoreographyDecision` | Event-driven flow trigger | `ExerisFlowChoreographyBridge`, `ExerisFlowChoreographyMapper` | `flow` | 4B Step 3 |
| `PersistenceEngine` / `ConnectionFactory` | Repository / DataSource | `ExerisPersistenceAdapter` | `data` | 3 |
| `PlatformTransactionManager` (Spring) | `StorageContext` | `ExerisPlatformTransactionManager` | `tx` | 3 |
