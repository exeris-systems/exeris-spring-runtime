# Phase 4: Events, Flow/Saga, and Graph Integration

**Status:**
- **4A (Events) — Closed (2026-05-09)** as `0.5.0-preview`-track preview module (default-off). Implementation landed in PR #11; closure docs (this revision + [`phase-4a-events-invariants.md`](phase-4a-events-invariants.md)) ship in the Phase 4A closure PR. Module: `exeris-spring-runtime-events` (646 LOC main / 7 test files / **30/30 tests green** — the runtime integration test's port-bind flake was fixed in the closure PR by binding to an ephemeral port).
- **4B (Flow/Saga) — Planned (1.0 preview)**, target `0.5.0-preview` release train. No scaffolds yet.
- **4C (Graph) — Post-1.0**, candidate for the `1.1.x` train

**Depends on:**
- 4A: only `KernelProviders.EVENT_ENGINE` slot binding required; persistence is not on the critical path
- 4B: Phase 3 substantially complete (tx context propagation operational). **Durable snapshot persistence is NOT enabled by default in `0.5.0-preview`.** 4B preview ships with `persistenceEnabled=false` — flows live in process memory and are lost on restart. Kernel 0.7.0 added a Community `JdbcFlowSnapshotStore` (with `exeris_saga_state` DDL and auto-DDL bootstrap), so the kernel-side prerequisite is satisfied. The Spring-side bridge that binds it through `KernelProviders.FLOW_SNAPSHOT_STORE` is sequenced for **Phase 4B Step 4 closure** and depends on the Pure Mode persistence autoconfiguration ordering being settled. The activation flag is `exeris.runtime.flow.persistence-enabled` (kebab-cased; record field `persistenceEnabled` on `ExerisFlowProperties`); applications that need durable parked-flow recovery must wait for Step 4.
- 4C: kernel Graph SPI lab tests, ADR for graph backend dialect selection

**Milestone:** M4 (split into M4-A/B for 1.0 preview, M4-C for post-1.0)
**Mode:** Pure Mode primary; event-driven choreography available in both modes
**Activation:** all three modules ship default-off behind property flags
(`exeris.runtime.events.enabled`, `exeris.runtime.flow.enabled`, `exeris.runtime.graph.enabled` — `matchIfMissing = false`).
Promotion to 1.0 preview does **not** weaken this rule; activation remains explicit.

> **Why 4A/4B were promoted to 1.0 preview:** downstream Spring services migrating onto the runtime
> need event-driven choreography and saga semantics during their migration, not after it. Shipping
> these as preview default-off lets those services depend on the runtime without forcing 1.0 to wait
> for Phase 4 to finish, while keeping the GA promise narrow until verification gates clear.

> **ADR follow-up:** the implementation PR for `exeris-spring-runtime-events` must reference (or
> create) an ADR pinning the EventBus ↔ Spring `ApplicationEventPublisher` boundary — that is where
> ownership-inversion regressions are most likely to surface. This roadmap shift is timing-only and
> does not by itself invalidate or replace any existing ADR.

---

## Goal

Extend the Exeris host-runtime model into higher-level application concerns:

1. **Events bridge** — `EventEngine` / `EventBus` accessible to Spring beans for publish/subscribe
   without ever making the Spring `ApplicationContext` the canonical event owner.
2. **Flow / Saga bridge** — `FlowEngine` exposed as a first-class Spring programming model;
   stateful, compensatable, park/wake-capable flows declared by Spring beans.
3. **Graph integration** — `GraphEngine` (MATCH DSL) accessible to Spring beans via a thin
   template — committed Phase 4C.

This phase requires three new Maven modules:

| New module | Purpose |
|:-----------|:--------|
| `exeris-spring-runtime-events` | EventEngine / EventBus bridge + autoconfiguration |
| `exeris-spring-runtime-flow` | FlowEngine / FlowScheduler bridge + autoconfiguration |
| `exeris-spring-runtime-graph` | GraphEngine / GraphSession facade + autoconfiguration |

---

## Why Flow/Saga Instead of `@Async`

Spring `@Async` is a fire-and-forget escape hatch. Exeris `FlowEngine` is a structured
execution model. The following comparison is the reason Phase 4 provides a `FlowEngine`
bridge as the recommended asynchronous/background execution primitive for Exeris-hosted
Spring applications, rather than delegating back to `@Async` with a `ThreadPoolTaskExecutor`.

| Capability | Spring `@Async` | Exeris `FlowEngine` |
|:-----------|:----------------|:--------------------|
| Backward compensation (saga rollback) | ❌ none | ✅ per-step `FlowStepAction` |
| Explicit state machine | ❌ fire-and-forget | ✅ `CREATED → RUNNING → PARKED → COMPENSATING → COMPLETED / FAILED_ROLLEDBACK` |
| Park/Wake (suspend without pinning a thread) | ❌ | ✅ `FlowOutcome.PARK` + `FlowScheduler.wake()` |
| Idempotency guard | ❌ | ✅ `IdempotencyGuard` SPI |
| Snapshot persistence for parked flows | ❌ | ✅ `FlowSnapshotStore` (optional) |
| Event-driven choreography | ❌ | ✅ `FlowChoreographyMapper` + `FlowEngine.registerChoreographyMapper()` |
| VT-native scheduling (no thread pool sizing) | Partial (VT executor needed) | ✅ native VT scheduler |
| Zero-GC after start (Enterprise tier) | ❌ | ✅ lock-free ring buffer, Flyweight `FlowContext` |
| Observable state timeline via telemetry | ❌ | ✅ via `TelemetryProvider` JFR events |
| Explicit timeout per flow instance | ❌ | ✅ `FlowDefinitionBuilder.timeoutDuration()` |

`@Async` remains usable for trivially fire-and-forget work. For any flow that:
- can fail mid-sequence,
- must compensate partial work,
- parks waiting for an external signal,
- or triggers on an event,

the `FlowEngine` bridge is the correct tool and `@Async` MUST NOT be introduced as a workaround.

---

## Deliverables

| # | Deliverable | Module | Priority |
|:-:|:------------|:-------|:--------|
| 1 | `ExerisEventTypeRegistry` — maps Java names to `EventDescriptor` ordinals | `events` | 4A |
| 2 | `ExerisEventPublisher` — Spring bean to publish events to Exeris `EventBus` | `events` | 4A |
| 3 | `ExerisEventListenerRegistrar` — scans `@ExerisEventListener` beans, registers `EventHandler` subscriptions | `events` | 4A |
| 4 | `ExerisEventAutoConfiguration` — wires the above; conditional on `EventEngine` slot bound | `events` | 4A |
| 5 | Architecture guard: no `ApplicationContext` or Spring event types in kernel bus path | `events` (test) | 4A |
| 6 | `ExerisFlowDefinition` interface — Spring beans implement to declare flow steps | `flow` | 4B |
| 7 | `ExerisFlowTemplate` — programmatic schedule/wake API wrapping `FlowEngine` | `flow` | 4B |
| 8 | `ExerisFlowDefinitionRegistrar` — scans `ExerisFlowDefinition` beans, compiles plans at boot | `flow` | 4B |
| 9 | `ExerisFlowChoreographyMapper` interface (extends kernel `FlowChoreographyMapper`, adds `eventTypeNames()`) + `ExerisFlowChoreographyBridge` — registers each mapper bean with `FlowEngine.registerChoreographyMapper()` | `flow` | 4B |
| 10 | `ExerisFlowAutoConfiguration` — wires the above; conditional on `FlowEngine` slot bound | `flow` | 4B |
| 11 | Runtime integration test: full flow lifecycle (schedule → park → wake → complete) | `flow` (test) | 4B |
| 12 | Runtime integration test: event-triggered choreography (publish event → flow started/woken) | `flow` + `events` (test) | 4B |
| 13 | Architecture guard: no Spring types in `FlowStepAction` / `FlowContext` usage paths | `flow` (test) | 4B |
| 14 | `ExerisGraphNodeRegistrar` — scans `@ExerisGraphNode` beans, registers `GraphNodeDescriptor` and `GraphEdgeDescriptor` metadata with `GraphEngine` | `graph` (new) | 4C |
| 15 | `ExerisGraphTemplate` — Spring-injectable facade: `openSession()`, traversal helpers, node/edge CRUD delegating to `GraphSession` | `graph` (new) | 4C |
| 16 | `ExerisGraphAutoConfiguration` — wires the above; conditional on `KernelProviders.GRAPH_ENGINE.isBound()` | `graph` (new) | 4C |
| 17 | Unit tests: `ExerisGraphTemplateTest`, `ExerisGraphNodeRegistrarTest` | `graph` (test) | 4C |
| 18 | Architecture guard: `GraphModuleBoundaryTest` — no Spring Data / JPA / JDBC imports; no HTTP imports | `graph` (test) | 4C |

---

## 4A: Exeris Events Bridge — ✅ Closed (2026-05-09)

**Module:** `exeris-spring-runtime-events`
**Activation:** `exeris.runtime.events.enabled=true` (default off, `matchIfMissing=false`)
**Invariants:** [`phase-4a-events-invariants.md`](phase-4a-events-invariants.md)

### Delivered (PR #11)

| # | Class | LOC | Evidence |
|:-:|:------|:---:|:---------|
| 1 | `ExerisEventTypeRegistry` — `String name → eventTypeOrdinal` lookup; eager populate at `afterSingletonsInstantiated()` | 114 | `ExerisEventTypeRegistryTest` 4/4 |
| 2 | `ExerisEventPublisher` — Spring-injectable publisher with `descriptorFor(name)` convenience overload | 79 | `ExerisEventPublisherTest` 4/4 |
| 3 | `@ExerisEventListener` — `@Target(METHOD)` annotation; `String[] eventTypes()` | 47 | exercised in registrar tests |
| 4 | `ExerisEventListenerRegistrar implements SmartInitializingSingleton, SmartLifecycle` — scans + registers + cleans up subscriptions; tolerant/strict mode for engine unavailability | 240 | `ExerisEventListenerRegistrarTest` 7/7 |
| 5 | `ExerisEventAutoConfiguration` — `@ConditionalOnProperty("exeris.runtime.events.enabled", matchIfMissing=false)` | 77 | `ExerisEventAutoConfigurationTest` 4/4 |
| 6 | `ExerisEventProperties` | 43 | covered by autoconfig tests |
| 7 | `EventEngineSupplier` — deferred `ScopedValue` accessor (consistent with Phase 3 `PersistenceEngineProvider` pattern) | 46 | exercised across publisher + registrar tests |
| 8 | `EventModuleBoundaryTest` — 5 ArchUnit rules (no Spring `ApplicationEventPublisher`, no `spring-context.event`, no HTTP/servlet, no tx/persistence, no JPA) | (test) | 5/5 — module boundary mechanically enforced |
| 9 | `PureModeClasspathGuardTest` — module-local Pure-Mode classpath ban (servlet API, Netty/Reactor, WebFlux, `DispatcherServlet`) | (test) | 4/4 — replicated per Phase 1c invariant |

**Test sweep:** 30/30 green. `ExerisEventBridgeRuntimeIntegrationTest` was previously flaky (2 errors) when port 8080 was occupied on the runner; the closure PR fixes this by adding `exeris.runtime.network.port=0` (ephemeral) to the test's `MockEnvironment` — the events bridge does not depend on a fixed HTTP port, so binding to an ephemeral one keeps the test environment-robust without weakening the runtime startup proof.

### Goal

Spring beans can publish events to the Exeris `EventBus` and subscribe to events from it,
without the Spring `ApplicationContext` becoming the canonical event bus owner.

### Ownership Rule

> Exeris `EventEngine` owns the bus. Spring beans are producers and consumers via adapters.
> Spring `ApplicationEventPublisher` is NOT wired to the Exeris bus. Keeping the two models
> separate prevents ownership inversion.

If an application needs both Spring events and Exeris events in the same process, they remain
two separate buses. The Exeris bus is the runtime bus; Spring events are application-local
in-process callbacks.

### Architecture

```
Kernel Bootstrap
    → EventEngine.start() (owned by ExerisRuntimeLifecycle)
    → KernelProviders.EVENT_ENGINE bound in ScopedValue scope

Spring Boot startup
    → ExerisEventAutoConfiguration activated
        → ExerisEventTypeRegistry: type-name → eventTypeOrdinal mapping
        → ExerisEventListenerRegistrar.afterSingletonsInstantiated()
            → scans beans for @ExerisEventListener
            → registers EventHandler subscriptions on EventEngine.bus()
            → SubscriptionTokens owned by registrar; cancelled on SmartLifecycle.stop()
        → ExerisEventPublisher bean exposed for injection
```

### Key Classes

**`ExerisEventTypeRegistry`**
- Spring-managed `@Component`
- Reads `EventRegistry` from the `EventEngine` (via `KernelProviders.EVENT_ENGINE`)
- Maintains a `String name → int eventTypeOrdinal` map
- Bean method: `EventDescriptor descriptorFor(String typeName)` — builds `EventDescriptor`
  with the correct ordinal; remaining fields (`streamId`, `aggregateId`, etc.) passed by caller
- Populated eagerly at `afterSingletonsInstantiated()` before any publisher is invoked

**`ExerisEventPublisher`**
```java
// Injected into Spring beans
public class ExerisEventPublisher {
    EventDescriptor descriptor = registry.descriptorFor("payment.completed");
    try (EventPayload payload = buildPayload(event)) {
        publisher.publish(descriptor, payload);
    }
}
```
- Does NOT expose `EventDescriptor` construction details to application code
- Payload encoding is supplied by the caller (raw `MemorySegment` or JSON codec helper)
- NOT a replacement for Spring `ApplicationEventPublisher`

**`@ExerisEventListener`**
- `@Target(METHOD)` annotation
- `String[] eventTypes()` — event type names to subscribe to
- Applied on `void handle(EventDescriptor descriptor, EventPayload payload)` methods
- Handler method receives `EventPayload` via try-with-resources contract (documented)

**`ExerisEventListenerRegistrar implements SmartInitializingSingleton, SmartLifecycle`**
- Performs subscription scan after all beans are wired
- Wraps annotated methods as `EventHandler` lambdas
- Holds `SubscriptionToken` per registration; cancels all tokens on `stop()`
- Scoped entirely to `eu.exeris.spring.runtime.events` — no web/tx/data coupling

**`ExerisEventAutoConfiguration`**
- Condition: `@ConditionalOnProperty("exeris.runtime.events.enabled", matchIfMissing = false)`
- Exposes: `ExerisEventTypeRegistry`, `ExerisEventPublisher`, `ExerisEventListenerRegistrar`
- Does NOT touch `ApplicationEventPublisher`

### Payload Encoding Note

`EventPayload` in Community is heap-backed; in Enterprise it is off-heap slab-allocated.
Spring beans must always close payloads via `try (EventPayload p = ...) {}`.
Application code must treat payloads as RAII resources — never cache or share across
request scope boundaries.

An optional `ExerisJsonEventPayload` helper in the `events` module may wrap Jackson
`ObjectMapper` to serialize/deserialize into/from `EventPayload`. This helper is explicitly
in the compatibility surface with documented allocation cost. It does NOT exist in the pure
hot path.

### What Stays Out of Scope

- Spring `ApplicationEventPublisher` wiring to the Exeris bus — ownership inversion, banned
- `@EventListener` Spring annotation → Exeris bus automatic bridge — implicit, banned
- Kafka / PostgreSQL outbox integration — belongs in `exeris-spring-runtime-data` (Phase 3/4B+)

---

## 4B: Flow / Saga Bridge

**Status:**
- **Step 1 — Closed (PR #17, 2026-05-09):** module skeleton, `FlowEngineSupplier`, `ExerisFlowProperties`, `ExerisFlowAutoConfiguration` (engine supplier only), `FlowModuleBoundaryTest` (incl. mechanical `@Async` ban), `PureModeClasspathGuardTest`. Lifecycle seam — `ExerisRuntimeLifecycle.getFlowEngine()` — landed in the same PR.
- **Step 2 — Closed (PR #TBD, 2026-05-09):** declarative + imperative invocation surface. `ExerisFlowDefinition` interface, `ExerisFlowTemplate` (schedule / park / wake / lookupParked / stats / plan registry), `ExerisFlowDefinitionRegistrar` (boot-time compile + registry population, tolerant/strict posture mirroring events module via `exeris.runtime.flow.require-engine`). Runtime IT proves lifecycle capture + first `requireEngine()` consumer call site against the live community kernel. Step-closure architecture guard added to `FlowModuleBoundaryTest`.
- **Step 3 — Closed (PR #TBD, 2026-05-10):** `ExerisFlowChoreographyBridge` — opt-in event-driven flow trigger. New `ExerisFlowChoreographyMapper` interface (extends kernel `FlowChoreographyMapper`, adds `eventTypeNames()` for discovery). Bridge runs at `SmartLifecycle` phase `Integer.MAX_VALUE - 98` (one slot after the Step-2 registrars at -99) and registers each mapper with the kernel via `FlowEngine.registerChoreographyMapper(mapper, eventTypeNames, eventBus)`. Three activation gates, in order: opt-in property `exeris.runtime.flow.choreography-enabled=true`, presence of an `ExerisEventPublisher` bean (events module active), and capability gate `FlowEngineCapabilities.choreographySupport()=true` checked at lifecycle start (always-loud failure when violated — the user explicitly opted in). Tolerant/strict posture for missing engine mirrors the Step-2 registrar (`exeris.runtime.flow.require-engine`). Runtime IT exercises real event-bus dispatch into a registered mapper. Choreography ladder + summary-table rows added to `docs/architecture/kernel-integration-seams.md`.
- **Step 4 — Planned:** Phase 4B closure — invariants doc, master-doc closure section, full lifecycle (schedule → park → wake → complete) runtime IT.

### Goal

Spring beans declare `FlowEngine`-backed flows using a minimal interface contract.
`FlowEngine` remains the runtime owner; Spring provides the declaration model, DI, and boot wiring.

`@Async` is NOT the recommended model for stateful background work in Exeris-hosted
applications. `ExerisFlowTemplate` is.

### Ownership Rule

> `FlowEngine` owns scheduling, state transitions, park/wake, compensation, and idempotency.
> Spring beans declare step logic via `FlowStepAction` and supply compensation.
> The integration layer compiles definitions at boot and schedules instances at call time.

### Architecture

```
Spring Boot startup
    → ExerisFlowAutoConfiguration activated
        → ExerisFlowDefinitionRegistrar.afterSingletonsInstantiated()
            → discovers all ExerisFlowDefinition beans
            → for each: calls bean.define(FlowDefinitionBuilder)
            → compiles FlowExecutionPlan via FlowExecutionPlanFactory.compile()
            → stores compiled plan in ExerisFlowTemplate (by definition name)
        → ExerisFlowChoreographyBridge
            → discovers all FlowChoreographyMapper beans
            → registers each: FlowEngine.registerChoreographyMapper(mapper, eventTypeNames, bus)
              (guarded by FlowEngineCapabilities.choreographySupport() check)
        → ExerisFlowTemplate bean exposed for injection

Runtime
    → Spring bean injects ExerisFlowTemplate
    → flowTemplate.schedule("order-fulfillment", ctx)
        → FlowScheduler.schedule(plan, context)
    → step executes on VT: FlowStepAction.execute(FlowContext) → FlowOutcome.PARK
        → FlowScheduler.park(context) — VT releases, no pinning
    → external event arrives → ChoreographyMapper returns Wake(instanceIdMost, instanceIdLeast)
        → FlowScheduler.wake(context) — VT resumes
    → FlowOutcome.CONTINUE → next step
    → FlowOutcome.COMPLETE → FlowState.COMPLETED
```

### Key Classes

**`ExerisFlowDefinition`** — interface implemented by Spring `@Component` beans:
```java
public interface ExerisFlowDefinition {
    String name();
    FlowDefinition define(FlowDefinitionBuilder builder);
}
```
- Returns a built `FlowDefinition` using the kernel builder
- Step logic is expressed as `FlowStepAction` lambdas (pure SPI, no Spring types)
- Compensation is declared inline (second argument to `builder.step(...)`)
- This is the **only** approved programming model; annotation-driven step discovery is not provided
  (would require reflection post-processor, violating constructor-first discipline)

Example:
```java
@Component
public class OrderFulfillmentFlow implements ExerisFlowDefinition {

    private final InventoryPort inventory;  // injected Spring bean
    private final PaymentPort payment;

    @Override
    public String name() { return "order-fulfillment"; }

    @Override
    public FlowDefinition define(FlowDefinitionBuilder b) {
        return b
            .step("reserve-stock",  ctx -> inventory.reserve(ctx),  ctx -> inventory.release(ctx))
            .step("charge-payment", ctx -> payment.charge(ctx),     ctx -> payment.refund(ctx))
            .step("dispatch-order", ctx -> dispatch(ctx),           null)
            .transition(0, 1)
            .transition(1, 2)
            .timeoutDuration(300_000_000_000L)
            .maxRetries(3)
            .build();
    }
}
```

Step methods receive `FlowContext` (SPI type) and return `FlowOutcome` (SPI enum). Spring beans
are injected into the flow class via constructor injection — they do not appear in the SPI
method signatures.

**`ExerisFlowTemplate`** _(Step 2, delivered)_
- Holds the compiled `FlowExecutionPlan` map by definition name (populated by the registrar at lifecycle start; cleared at stop).
- Resolves the kernel `FlowEngine` via `FlowEngineSupplier.requireEngine()` per call — the engine is read from the current `ScopedValue` scope, never captured at construction.
- Exposes:
  - `FlowContext newContext(String definitionName)` — heap-backed seed implementing `FlowContext`: UUID-split instance id, `state=CREATED`, `currentStep=0`, plan-derived `timeoutNanos`.
  - `FlowContext schedule(String definitionName)` — convenience: `schedule(name, newContext(name))`; returns the seed for later park/wake.
  - `void schedule(String definitionName, FlowContext context)` — schedules an explicit context for execution.
  - `void park(FlowContext context)` / `void wake(FlowContext context)` — symmetric scheduler delegation.
  - `Optional<FlowContext> lookupParked(long, long)` — read-through to the kernel scheduler.
  - `FlowEngineStats stats()` — diagnostic stats.
  - `Set<String> registeredFlowNames()`, `boolean hasFlow(String)`, `Map<String, FlowExecutionPlan> registeredPlans()` — read-only diagnostic surface.
- Thin delegation only — owns no flow state beyond the compiled plan map.

**`ExerisFlowDefinitionRegistrar implements SmartInitializingSingleton, SmartLifecycle`** _(Step 2, delivered)_
- Collects all `ExerisFlowDefinition` beans at `afterSingletonsInstantiated()`; rejects duplicate `name()` and blank names at collection time.
- At lifecycle `start()` (phase `Integer.MAX_VALUE - 99`, immediately after the kernel lifecycle): for each binding, calls `engine.plans().newDefinition(name)` → `bean.define(builder)` → `engine.plans().compile(def)` → `template.registerPlan(name, plan)`.
- Validates `definition.name()` matches the supplied bean name — catches a bean that ignored the registrar-provided builder.
- Posture matches the events registrar: when no engine is bound and `exeris.runtime.flow.require-engine=true` (default) **with** definitions declared, fails the lifecycle start loud; when set to `false` (test/dev), logs a warning and transitions to running with no compiled plans. With zero definitions declared the registrar is always tolerant.
- `stop()` clears the template plan registry so a re-bootstrap starts from an empty state.

**`ExerisFlowChoreographyBridge implements SmartInitializingSingleton, SmartLifecycle`** _(Step 3, delivered)_
- `SmartLifecycle` phase `Integer.MAX_VALUE - 98` — one slot after the registrars at -99 so
  flow plans are compiled and event listeners are subscribed before mappers wire to the bus.
- Discovers all `ExerisFlowChoreographyMapper` beans (interface, not annotation; extends kernel
  `FlowChoreographyMapper` and adds `eventTypeNames()` for discovery — chosen over an
  annotation because mappers typically resolve plans through `ExerisFlowTemplate` and benefit
  from class-shaped beans + constructor injection).
- For each mapper bean: validates `eventTypeNames()` is non-empty (rejected at metadata
  collection time), then calls `FlowEngine.registerChoreographyMapper(mapper, eventTypeNames, eventBus)`.
  The kernel owns the resulting subscription tokens and cancels them on `engine.close()`.
- Tolerant/strict posture mirrors `ExerisFlowDefinitionRegistrar`: `exeris.runtime.flow.require-engine`
  controls whether a missing `FlowEngine` or `EventEngine` fails loud (default) or logs and
  continues (test/dev only).
- **Capability gate is always loud:** if mapper beans are declared but
  `flowEngine.capabilities().choreographySupport()` returns `false`, lifecycle start throws —
  the user explicitly opted in via `exeris.runtime.flow.choreography-enabled=true` and a tier
  without the capability cannot honour the contract. The capability is checked at `start()`
  rather than as a bean condition because capabilities cannot be probed until the kernel has
  booted.
- Activation: `@ConditionalOnProperty("exeris.runtime.flow.choreography-enabled", matchIfMissing = false)`
  + `@ConditionalOnBean(ExerisEventPublisher.class)` — no events module, no choreography wiring.
  `flow → events` is the documented dependency direction (see module pom).

**`ExerisFlowAutoConfiguration`**
- Condition: `@ConditionalOnProperty("exeris.runtime.flow.enabled", matchIfMissing = false)`
- Depends on: `ExerisRuntimeAutoConfiguration` (lifecycle/bootstrap already wired)
- Exposes: `ExerisFlowDefinitionRegistrar`, `ExerisFlowTemplate`, optionally `ExerisFlowChoreographyBridge`

### `FlowSnapshotStore` and `IdempotencyGuard` Integration

Both are optional kernel SPI components. The Spring integration layer does not implement either.

- Kernel 0.7.0 ships `JdbcFlowSnapshotStore` (Community implementation, `exeris_saga_state`
  DDL, auto-DDL bootstrap). When the kernel `FlowEngine` is configured with
  `persistenceEnabled=true`, the bootstrapper (in `ExerisRuntimeLifecycle`) must bind
  `KernelProviders.FLOW_SNAPSHOT_STORE` before `FlowEngine.start()`. The Spring-side
  wiring — sequenced for Phase 4B Step 4 closure — lives in `exeris-spring-runtime-tx`
  or `exeris-spring-runtime-data`, not in `exeris-spring-runtime-flow`, and depends on
  the Pure Mode persistence autoconfiguration ordering being settled so the kernel
  `PersistenceEngine` wins over Boot's `DataSourceAutoConfiguration`.
- `IdempotencyGuard` — if a custom guard is needed, Spring beans may implement `IdempotencyGuard`
  (SPI type) and be provided to the bootstrapper. The flow module exposes a
  `@ConditionalOnBean(IdempotencyGuard.class)` hook that binds a found bean into
  `KernelProviders.IDEMPOTENCY_GUARD` before `FlowEngine.start()`.

### Mode Clarity

Flow bridge is mode-agnostic: `FlowEngine` is independent of HTTP transport mode.
A Pure Mode application can use flows; a Compatibility Mode application can use flows.
Web mode does not affect `ExerisFlowAutoConfiguration` activation.

### Risks

| Risk | Mitigation |
|:-----|:-----------|
| `FlowEngine` may not be bound in all kernels (optional subsystem) | Guard with `KernelProviders.FLOW_ENGINE.isBound()` check at boot; fail-fast with clear message |
| `registerChoreographyMapper` throws `UnsupportedOperationException` on engines without choreography support | `ExerisFlowChoreographyBridge` checks `FlowEngineCapabilities.choreographySupport()` at lifecycle start. If mappers are declared and the gate is false, the bridge throws `IllegalStateException` (always-loud — opt-in flag was set, so silent skip is wrong) |
| Step lambdas closing over Spring beans create lifecycle coupling | Document clearly: Spring bean must outlive the flow engine; `SmartLifecycle` stop order must drain in-flight flows before Spring context closes |
| `FlowContext` is an SPI interface; Enterprise uses Flyweight pattern | Spring step beans must never cache `FlowContext` beyond the single `execute()` call; enforce via documentation and architecture guard test |
| Spring-side `FlowSnapshotStore` binding depends on Pure Mode persistence autoconfig winning over Boot's `DataSourceAutoConfiguration` | Keep `persistenceEnabled=false` default in `0.5.0-preview`; flip the default only after Phase 4B Step 4 closure verifies the binding under the autoconfig ordering and Spring-side wiring tests pass |

---

## 4C: Graph Integration

### Goal

Spring beans can traverse, mutate, and query graphs through the Exeris `GraphEngine` without
knowledge of the backend dialect (PostgreSQL PGQ or Neo4j Cypher). Exeris owns the session
lifecycle and memory; Spring provides DI and declarative node/edge metadata registration.

Kernel Graph SPI is at TRL-4 (local integration tests pass; lab test scope pending).
The Spring integration layer is committed for M4.

### Ownership Rule

> `GraphEngine` owns session allocation, connection lifecycle, and memory (Community: Arena
> per session; Enterprise: preallocated slab pool). Spring beans call `ExerisGraphTemplate`
> methods; they never obtain or cache `GraphSession` directly.

No Spring Data–style repository proxies. No JPA. No JDBC. The template is the API surface.

### Architecture

```
Kernel Bootstrap
    → GraphEngine created via GraphProvider.createEngine(GraphConfig)
    → KernelProviders.GRAPH_ENGINE bound in ScopedValue scope
    → GraphEngine.registerNodes(nodeDescriptors) — metadata registered before sessions open

Spring Boot startup
    → ExerisGraphAutoConfiguration activated
        → ExerisGraphNodeRegistrar.afterSingletonsInstantiated()
            → scans @ExerisGraphNode beans for GraphNodeDescriptor / GraphEdgeDescriptor metadata
            → calls GraphEngine.registerNodes(...) and GraphEngine.registerEdges(...)
        → ExerisGraphTemplate bean exposed for injection

Runtime
    → Spring bean injects ExerisGraphTemplate
    → graphTemplate.traverse("User", traversal)
        → GraphEngine.openSession()          ← session allocated (Community: Arena; Enterprise: slab)
        → session.traverseBreadthFirst(traversal)
        → session.close()                    ← memory returned to pool
```

### Key Classes

**`@ExerisGraphNode`** — `@Target(TYPE)` annotation on Spring beans or plain classes:
```java
@ExerisGraphNode(label = "User", sourceTable = "users", properties = {"name", "email"})
public class UserNode { ... }
```
- Drives `GraphNodeDescriptor` construction at boot
- `syncToGraph` defaults to `true`; override with `@ExerisGraphNode(syncToGraph = false)`

**`@ExerisGraphEdge`** — `@Target(TYPE)` annotation (companion to `@ExerisGraphNode`):
```java
@ExerisGraphEdge(label = "FOLLOWS", sourceNode = "User", targetNode = "User", table = "user_follows")
public class FollowsEdge { ... }
```
- Drives `GraphEdgeDescriptor` construction at boot

**`ExerisGraphNodeRegistrar implements SmartInitializingSingleton`**
- Performs annotation scan after all beans are wired
- Constructs `GraphNodeDescriptor` and `GraphEdgeDescriptor` records from annotations
- Calls `GraphEngine.registerNodes(List<GraphNodeDescriptor>)` and
  `GraphEngine.registerEdges(List<GraphEdgeDescriptor>)` exactly once at boot
- Metadata registration is idempotent and must complete before `ExerisGraphTemplate` is used

**`ExerisGraphTemplate`**
- Spring-managed singleton; holds a reference to `GraphEngine` (from `KernelProviders.GRAPH_ENGINE`)
- Session opens and closes per operation inside the template method — callers never hold sessions
- Exposed methods (delegating to `GraphSession`):

| Method | Delegates to |
|:-------|:------------|
| `List<UUID> traverseBfs(GraphTraversal traversal)` | `session.traverseBreadthFirst(traversal)` |
| `LoanedBuffer streamBfsJson(GraphTraversal traversal)` | `session.streamBfsJson(traversal)` — **caller-owned buffer; must close** |
| `void upsertNode(String label, UUID nodeId, LoanedBuffer props)` | `session.upsertNode(...)` |
| `void deleteNode(String label, UUID nodeId)` | `session.deleteNode(...)` |
| `void createEdge(GraphEdgeDescriptor edge, UUID src, UUID tgt, double weight, String props)` | `session.createEdge(...)` |
| `void upsertEdge(GraphEdgeDescriptor edge, UUID src, UUID tgt, double weight, String props)` | `session.upsertEdge(...)` |
| `void deleteEdge(GraphEdgeDescriptor edge, UUID src, UUID tgt)` | `session.deleteEdge(...)` |
| `PathResult findShortestPath(UUID source, UUID target)` | `session.findShortestPath(source, target)` |
| `UUID getRootNode()` | `session.getRootNode()` |

- `streamBfsJson` is the zero-copy hot-path. The returned `LoanedBuffer` is **caller-owned**
  and MUST be closed via try-with-resources. This is documented on the method Javadoc.
- No Spring Data repository proxy generation, no Hibernate, no JPA — direct template calls only.

**`ExerisGraphAutoConfiguration`**
- Condition: `@ConditionalOnProperty("exeris.runtime.graph.enabled", matchIfMissing = false)`
- Secondary condition: `KernelProviders.GRAPH_ENGINE.isBound()` check at bean initialization —
  logs warning and skips if unbound (graph subsystem not activated in this kernel configuration)
- Exposes: `ExerisGraphNodeRegistrar`, `ExerisGraphTemplate`

### Memory Contract

| Path | Community | Enterprise |
|:-----|:----------|:-----------|
| `traverseBfs` | Arena-per-session via `MemoryAllocator`; GC reclaims after session close | Slab checkout from preallocated pool; returned on close — zero GC |
| `streamBfsJson` | `LoanedBuffer` allocated via `MemoryAllocator`; caller closes | Off-heap slab slot; caller closes — zero GC |
| `upsertNode(props)` | Caller provides `LoanedBuffer`; session reads, does NOT close it | Same — buffer not owned by session |

Community allocation is bounded and documented. Enterprise is zero-allocation after
`GraphEngine.start()`.

### What Stays Out of Scope

- Spring Data–style `@Repository` or proxy generation — ownership inversion, banned
- JPA / Hibernate / any ORM — JDBC-gravity-well anti-pattern, banned
- Reactive graph streams (`Flux<UUID>`) — banned; use `streamBfsJson` + off-heap buffer
- Algorithm implementations — `PathFinder` / `EdgeWeightFunction` are SPI types; Spring beans
  may implement them and supply them to `GraphSession` operations, but the module does not
  provide implementations

### Risks

| Risk | Mitigation |
|:-----|:-----------|
| Graph SPI lab tests pending | Gate `exeris.runtime.graph.enabled` default to `false`; require explicit opt-in; warn at startup if Graph SPI version < expected |
| `LoanedBuffer` from `streamBfsJson` leaks if caller forgets close | Document clearly; add `LeakDetectionMode` guard in integration tests |
| Community allocates per-session; high-frequency graph calls may pressure GC | Document in Community allocation profile; recommend batching traversals per request |
| `GraphEngine.registerNodes()` called after sessions opened | `ExerisGraphNodeRegistrar` uses `SmartInitializingSingleton` (after all beans wired, before any request); fail-fast if engine already started |

---

## New Module Additions Required

The split scope means the three new modules land in two waves. Each wave updates the root `pom.xml` reactor and `docs/architecture/module-boundaries.md` before code lands in that module.

**Wave 1 — alongside 0.5.0-preview (M4-A/B, in 1.0 train):**

1. `exeris-spring-runtime-events` — new Maven module (4A)
2. `exeris-spring-runtime-flow` — new Maven module (4B)

**Wave 2 — post-1.0 (M4-C):**

3. `exeris-spring-runtime-graph` — new Maven module (4C)

Module boundary rules for the two new modules:

### `exeris-spring-runtime-events`

Allowed:
- `EventEngine` / `EventBus` / `EventRegistry` / `EventDescriptor` / `EventPayload` / `SubscriptionToken` (SPI types)
- `@ExerisEventListener` annotation
- `ExerisEventTypeRegistry`, `ExerisEventPublisher`, `ExerisEventListenerRegistrar`
- `ExerisEventAutoConfiguration`

Forbidden:
- `ApplicationEventPublisher` wiring to Exeris bus
- Kafka / outbox infrastructure (belongs in `data`)
- Any HTTP / request-path logic
- Transaction semantics

### `exeris-spring-runtime-flow`

Allowed:
- `FlowEngine` / `FlowScheduler` / `FlowDefinitionBuilder` / `FlowExecutionPlanFactory` (SPI types)
- `FlowStepAction`, `FlowContext`, `FlowOutcome`, `FlowDefinition` (SPI types)
- `FlowChoreographyMapper`, `ChoreographyDecision`, `IdempotencyGuard` (SPI types)
- `ExerisFlowDefinition` interface, `ExerisFlowTemplate`, `ExerisFlowDefinitionRegistrar`
- `ExerisFlowChoreographyBridge` (conditional on events module)
- `ExerisFlowAutoConfiguration`

Forbidden:
- HTTP routing or dispatcher logic
- Transaction ownership
- DataSource / persistence access
- `@Async` / `ThreadPoolTaskExecutor` usage

### `exeris-spring-runtime-graph`

Allowed:
- `GraphEngine`, `GraphSession`, `GraphDialect`, `GraphConfig`, `GraphProvider` (SPI types)
- `GraphNodeDescriptor`, `GraphEdgeDescriptor`, `GraphTraversal`, `PathResult` (SPI model types)
- `LoanedBuffer` (SPI type — for `streamBfsJson` return and `upsertNode` props parameter)
- `@ExerisGraphNode`, `@ExerisGraphEdge` annotations
- `ExerisGraphNodeRegistrar`, `ExerisGraphTemplate`
- `ExerisGraphAutoConfiguration`

Forbidden:
- Spring Data / JPA / Hibernate / any ORM type
- JDBC / DataSource imports
- HTTP / request-path logic
- Reactive types (`Flux`, `Mono`)
- Algorithm implementations (implement `PathFinder` / `EdgeWeightFunction` externally if needed)

---

## Verification Plan

| Gate | Suite | Notes |
|:-----|:------|:------|
| Unit | `ExerisEventTypeRegistryTest`, `ExerisEventPublisherTest`, `ExerisFlowTemplateTest` | Local adapter behavior |
| Module integration | `ExerisEventAutoConfigurationTest`, `ExerisFlowAutoConfigurationTest` | Spring context wiring, bean activation |
| Runtime integration | `ExerisFlowLifecycleRuntimeTest` | Full schedule → park → wake → complete cycle using kernel testkit fixture |
| Runtime integration | `ExerisEventChoreographyRuntimeTest` | Event publish → flow choreography decision → scheduler action |
| Architecture guard | `FlowModuleBoundaryTest` | No Spring types in `FlowStepAction` / `FlowContext` usage; no HTTP imports in flow module |
| Architecture guard | `EventModuleBoundaryTest` | No `ApplicationEventPublisher` wiring to Exeris bus |
| Unit | `ExerisGraphTemplateTest`, `ExerisGraphNodeRegistrarTest` | Session delegation, metadata registration |
| Module integration | `ExerisGraphAutoConfigurationTest` | Spring context wiring, conditional activation |
| Architecture guard | `GraphModuleBoundaryTest` | No Spring Data / JPA / ORM / JDBC imports; no HTTP imports in graph module |

---

## Exit Criteria (M4)

- [ ] `ExerisEventPublisher` operational: Spring bean publishes event → `EventBus.publish()` verified wire-level
- [ ] `@ExerisEventListener` operational: annotated method invoked on event arrival, `EventPayload` closed correctly
- [ ] `ExerisFlowDefinition` bean: Spring bean declares steps → plan compiled at boot → schedulable
- [ ] `ExerisFlowTemplate.schedule()` functional: flow executes steps in sequence, reaches `COMPLETED`
- [ ] `FlowOutcome.PARK` + `wake()` functional: flow parks, wakes on external trigger, resumes correctly
- [ ] `FlowOutcome.FAIL` + compensation: if a step fails, compensation steps execute in reverse order
- [ ] Event-driven choreography: event publish triggers `ChoreographyMapper` → `Wake` or `Start` decision executed (if `choreographySupport = true`)
- [ ] `ExerisGraphNodeRegistrar` registers node and edge metadata at boot: `GraphEngine.registerNodes()` called before first session
- [ ] `ExerisGraphTemplate.traverseBfs()` functional: traversal executes against running `GraphEngine`, returns correct node UUIDs
- [ ] `ExerisGraphTemplate.streamBfsJson()` functional: returns `LoanedBuffer` with UTF-8 JSON; caller closes without leak
- [ ] `ExerisGraphTemplate` node/edge CRUD: upsert and delete operations reach `GraphSession` and complete without exception
- [ ] No regressions in Phase 1/2/3 test suites
- [ ] Module boundary guard tests pass for all three new modules

---

## Sprint Breakdown

Phase 4 is split across the 1.0 preview train and the post-1.0 train. Sprints 4.1–4.3 land in `0.5.0-preview` (M4-A/B). Sprint 4.4 stays scheduled for the post-1.0 train (M4-C); it is not committed 1.0 scope.

### Sprint 4.1 — Events Module Foundation _(0.5.0-preview, M4-A)_
- Introduce an event publisher and listener registration over the Exeris `EventBus`.
- Keep Spring `ApplicationEventPublisher` separate from runtime event ownership.
- Exit: event publish/subscribe works through Exeris-owned adapters only.

### Sprint 4.2 — Flow Template and Boot-Time Plan Compilation _(0.5.0-preview, M4-B)_
- Register `ExerisFlowDefinition` beans and compile plans at startup.
- Support schedule, park, wake, and complete lifecycle handling.
- Exit: one representative saga path is operational in runtime tests.

### Sprint 4.3 — Choreography and Capability Gating _(0.5.0-preview, M4-B)_
- Register mappers only when the kernel advertises support.
- Preserve explicit lifecycle and shutdown ordering.
- Exit: event-driven start/wake is verified where supported and safely skipped where not.

### Sprint 4.4 — Graph Integration Spike _(post-1.0, M4-C)_
- Add metadata registration and a thin `ExerisGraphTemplate`.
- Keep JPA, Spring Data, and reactive APIs out of scope.
- Exit: traversal and node/edge CRUD work in an opt-in lab profile.

## Jira-Style Backlog

| Key | Type | Summary | Acceptance Criteria | Module | Priority |
|:----|:-----|:--------|:--------------------|:-------|:---------|
| ESR-P4-001 | Story | Create the events bridge module and publisher facade | Spring beans can publish through `EventBus` without turning Spring into the canonical bus owner | `exeris-spring-runtime-events` | P2 |
| ESR-P4-002 | Story | Register `@ExerisEventListener` methods safely | Listener scan, subscription registration, and shutdown cleanup are all verified in module tests | `exeris-spring-runtime-events` | P2 |
| ESR-P4-003 | Story | Introduce the flow template and definition registrar | `ExerisFlowDefinition` beans compile at boot and can be scheduled via a thin template | `exeris-spring-runtime-flow` | P2 |
| ESR-P4-004 | Test | Prove the runtime flow lifecycle end-to-end | A representative flow can schedule, park, wake, compensate, and complete in runtime integration tests | `exeris-spring-runtime-flow` | P2 |
| ESR-P4-005 | Spike | Gate choreography on actual kernel capability | Choreography wiring activates only when supported and does not create split-brain lifecycle ownership | `exeris-spring-runtime-flow` | P3 |
| ESR-P4-006 | Spike | Validate graph metadata registration and traversal template | Node/edge metadata registers at boot and a thin graph facade works in an opt-in lab profile | `exeris-spring-runtime-graph` | P3 |
