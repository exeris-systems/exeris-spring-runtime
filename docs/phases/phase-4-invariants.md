# Phase 4B Invariants — Flow / Saga Bridge

**Status:** Locked-in (Phase 4B closed 2026-05-11; preview-track, default-off)
**Source of authority:** ADR-006 (The Wall), ADR-010 (Host Runtime Model),
ADR-011 (Pure vs Compatibility Mode), kernel
[ADR-022](https://github.com/exeris-systems/exeris-kernel/blob/development/0.8.0/docs/adr/ADR-022-persistence-spi-extension-instant-binders.md)
(Persistence SPI Extension — Instant Binders, which closes the kernel-side
`JdbcFlowSnapshotStore` wiring gap), and the master plan
[`phase-4-events-flow.md`](phase-4-events-flow.md). This page enumerates the
non-negotiable invariants Phase 4B established for the Exeris flow / saga
bridge.

A change that breaks any item below is an architectural regression, not a style
issue, and requires a superseding ADR — not a workaround in code.

Phase 0–3 invariants
([`phase-0-invariants.md`](phase-0-invariants.md),
[`phase-1-invariants.md`](phase-1-invariants.md),
[`phase-2-invariants.md`](phase-2-invariants.md),
[`phase-3-invariants.md`](phase-3-invariants.md))
and Phase 4A invariants
([`phase-4a-events-invariants.md`](phase-4a-events-invariants.md))
still apply in full; Phase 4B invariants are additive and flow-specific.

These are also the **graduation gate** for promoting `exeris-spring-runtime-flow`
from 1.0 preview to bounded GA in 1.0.x. A regression on any of these blocks
graduation.

---

## 1. Flow bridge is opt-in and never default

`ExerisFlowAutoConfiguration` activates only when
`exeris.runtime.flow.enabled=true`. The conditional is
`@ConditionalOnProperty(prefix="exeris.runtime.flow", name="enabled",
matchIfMissing=false)` — there is **no `matchIfMissing=true`** and there is no
auto-detection that silently activates the module.

When disabled, the artefact compiles in but no beans are created — zero runtime
overhead on the application's hot paths, zero classpath risk for apps that do
not need flows.

- **Guard:** `ExerisFlowAutoConfigurationTest#doesNotActivateByDefault` and
  `#activatesWhenExplicitlyEnabled`.

## 2. `FlowEngine` is the runtime owner; Spring is the declaration model

`FlowEngine` owns scheduling, state transitions, park / wake, compensation, and
idempotency. The Spring integration layer does NOT implement any of those — it
provides:

- A declaration interface (`ExerisFlowDefinition`).
- A compile + register pass at boot time (`ExerisFlowDefinitionRegistrar`).
- An imperative façade for scheduling (`ExerisFlowTemplate.schedule(name)`,
  `.park()`, `.wake()`, `.lookupParked()`, `.stats()`).
- An event-driven choreography bridge for opt-in cases (`ExerisFlowChoreographyBridge`).

Any feature that requires the integration layer to own scheduling decisions,
state machine transitions, or compensation logic is a violation of this
invariant and requires a superseding ADR.

- **Guard:** code review + `FlowModuleBoundaryTest` (no Spring `@Async` /
  `TaskExecutor` / `org.springframework.scheduling.*` reachable from the flow
  module).

## 3. `@Async` is explicitly NOT a substitute for `ExerisFlowTemplate`

The kernel's `FlowScheduler` is the canonical execution path for stateful
background work in Exeris-hosted applications. `@Async` / Spring's
`TaskExecutor` model:

- Has no durable state.
- Has no compensation contract.
- Has no idempotency guard.
- Has no park / wake semantics.
- Runs on a Spring-owned thread pool — not on a kernel-managed virtual thread
  carrier, inverting the runtime ownership model.

The flow module's architecture guard enforces this mechanically: classes in
`eu.exeris.spring.runtime.flow.*` may not depend on `org.springframework.scheduling.*`
or `org.springframework.core.task.*`.

- **Guard:** `FlowModuleBoundaryTest#doesNotImportSpringAsyncOrTaskExecutor`.

## 4. `BridgeFlowContext.timeoutNanos = 0L` is the kernel-computed-deadline sentinel

`FlowContext.timeoutNanos()` is documented by kernel SPI as an **absolute**
monotonic deadline computed as `System.nanoTime() + plan.timeoutDurationNanos()`.
The Spring template's `newContext(definitionName)` MUST emit `0L` for the
`timeoutNanos` field — the kernel SPI explicitly treats `0` as "scheduler
please compute the deadline from the plan". Passing the plan's duration
directly would be misread as a past deadline (`System.nanoTime()` after JVM
startup dwarfs any reasonable duration) and the kernel would time the flow out
before invoking its first step.

This invariant was added after the duration-as-deadline bug was found and fixed
(PR #26); the unit test that codifies it is
`ExerisFlowTemplateTest#newContextProducesFreshUuidStateCreatedAndZeroTimeoutSentinel`.

- **Guard:** unit test above + the cross-restart runtime IT (a fully timed-out
  flow could not park, so the IT also serves as a runtime guard).

## 5. Choreography activation requires three gates, evaluated in order

`ExerisFlowChoreographyBridge` activates only when ALL three gates are
satisfied, in this order:

1. `exeris.runtime.flow.choreography-enabled=true` (opt-in property,
   `matchIfMissing=false`).
2. An `ExerisEventPublisher` bean is present (the events module is active —
   no kernel `EventBus` access without it).
3. The bound `FlowEngine.capabilities().choreographySupport()` returns `true`.

Gate 3 is checked at `SmartLifecycle.start()` (not as a bean condition) because
kernel capabilities cannot be probed until the kernel has booted, but bean
wiring runs during refresh. When gate 3 fails, the bridge throws
`IllegalStateException` — always-loud, never silent — because the user
explicitly opted in via gate 1 and a tier without the capability cannot honour
that opt-in.

- **Guard:** `ExerisFlowChoreographyBridgeTest` (10 unit cases, 1 always-loud
  capability gate case) + `ExerisFlowAutoConfigurationTest` (3 activation matrix
  cases) + `ExerisFlowChoreographyBridgeRuntimeIntegrationTest` (live kernel
  dispatch).

## 6. SmartLifecycle phase ordering: lifecycle → registrars → choreography

`SmartLifecycle` phase ordering must remain stable across the three flow-module
beans so kernel boot completes, plans compile, and choreography subscribes in
the correct order:

| Bean | Phase | Role |
|:-----|:------|:-----|
| `ExerisRuntimeLifecycle` (autoconfigure) | `Integer.MAX_VALUE - 100` | Kernel bootstrap |
| `ExerisFlowDefinitionRegistrar`, `ExerisEventListenerRegistrar` | `Integer.MAX_VALUE - 99` | Plan compile + listener subscribe |
| `ExerisFlowChoreographyBridge` | `Integer.MAX_VALUE - 98` | Choreography mapper registration |

Reverse order on shutdown — the kernel stops last, after the bridges have
released their references.

- **Guard:** `ExerisFlowChoreographyBridgeTest#phaseConstantSitsOneSlotAfterRegistrar`
  + manual review of `getPhase()` returns.

## 7. `flow.*` kernel config keys reach the kernel through `exeris.runtime.flow.*`

The kernel's `CommunityFlowSubsystem.buildFlowConfig()` reads camelCase keys
under `flow.*` directly from the `ConfigProvider`. Spring application
properties live under `exeris.runtime.flow.*`.
`ExerisSpringConfigProvider.flowKernelKeyAlias` bridges the two — when a kernel
lookup for `flow.X` misses, the alias retries under
`exeris.runtime.flow.X` (camelCase) and then `exeris.runtime.flow.x-y` (kebab
case derived from `XY`). Both forms resolve so this works with full Spring Boot
relaxed binding and with bare `MockEnvironment` in tests.

Without this bridge, setting `exeris.runtime.flow.persistence-enabled=true`
would have no effect on the kernel: the kernel-side flow subsystem would fall
back to `FlowEngineConfig.defaults()` (which sets `persistenceEnabled=false`)
and saga state would silently stay in-memory regardless of the Spring property.

- **Guard:** the cross-restart runtime IT — if the alias breaks, no saga row
  appears in `exeris_saga_state` and the IT fails fast at the direct-DB
  assertion. `ExerisFlowAutoConfigurationTest#optionalFlagDefaultsAfterMasterSwitchEnabled`
  covers the Spring-side property binding.

## 8. Durable saga state requires a JDBC `PersistenceEngine` — kernel handles selection

The kernel's `CommunityFlowSubsystem.initialize()` selects the snapshot store
based on whether a `PersistenceEngine` was registered with
`CommunityBootstrapServices` during the SERVICES phase:

| Persistence wiring | `flow.persistenceEnabled` | `FLOW_SNAPSHOT_STORE` bound to |
|:-------------------|:--------------------------|:-------------------------------|
| JDBC `PersistenceEngine` bound | `true` | `JdbcFlowSnapshotStore` (durable) |
| No `PersistenceEngine` bound | `true` | `CommunityFlowSnapshotStore` (in-memory) |
| Any | `false` | (no store bound — `Optional.empty()`) |

The Spring side does NOT construct or bind `FlowSnapshotStore` directly. The
default for `ExerisFlowProperties.persistenceEnabled` is `true`; the fallback
to in-memory when no JDBC engine is bound makes the default safe even for
applications that have not configured persistence.

- **Guard:** kernel-side `CommunityFlowSubsystemSnapshotStoreWiringTest` (locks
  the three branches at the kernel level) + this repo's
  `parkedFlowSnapshotsSurviveLifecycleRestartViaJdbcStore` runtime IT (proves
  the JDBC branch is reachable end-to-end from the Spring side).

## 9. Cross-restart durable recovery is the load-bearing IT — not unit-test substitutable

The Phase 4B Step 4 closure gate is the cross-restart runtime IT:

1. Lifecycle A schedules a flow whose first step returns `PARK` — kernel
   persists a `state = PARKED` row in `exeris_saga_state`.
2. Lifecycle A stops; H2 in-memory DB stays alive for the JVM
   (`DB_CLOSE_DELAY=-1`).
3. Direct DB assertion: the saga row exists with the expected composite-PK and
   `state = 'PARKED'`. This is the unambiguous machine check that
   `exeris.runtime.flow.persistence-enabled=true` reaches the kernel via the
   alias AND that the kernel selected `JdbcFlowSnapshotStore` instead of the
   in-memory fallback.
4. Lifecycle B starts against the same DB and re-registers the same plan under
   the same definition name. `template.lookupParked(...)` rehydrates the parked
   context through `FLOW_SNAPSHOT_STORE`.

Unit tests cannot substitute this IT: the load-bearing claim is that the
end-to-end alias-to-kernel-to-JDBC path actually writes durable rows that
survive a lifecycle restart. A unit-level mock of `FlowEngine` can't prove
this.

- **Guard:** `ExerisFlowBridgeRuntimeIntegrationTest#parkedFlowSnapshotsSurviveLifecycleRestartViaJdbcStore`.

## 10. Tolerant vs strict posture (`require-engine`) mirrors events module

`ExerisFlowDefinitionRegistrar` and `ExerisFlowChoreographyBridge` default to
**strict** posture (`exeris.runtime.flow.require-engine=true`): when
`ExerisFlowDefinition` / `ExerisFlowChoreographyMapper` beans are declared but
the kernel did not bind a `FlowEngine` during bootstrap, the lifecycle start
fails loud with a clear message — silent drop of compiled plans / unregistered
mappers is not acceptable.

`require-engine=false` is an explicit opt-out for test / dev environments that
intentionally skip kernel boot. The flag mirrors
`exeris.runtime.events.require-engine` so an application that opts both modules
out follows a single pattern.

- **Guard:** `ExerisFlowDefinitionRegistrarTest` + `ExerisFlowChoreographyBridgeTest`
  (each carries strict-and-tolerant posture cases).

## 11. Engine references in `ExerisRuntimeLifecycle` are scoped-value captures, not field exports

`ExerisRuntimeLifecycle.getFlowEngine()` returns the engine reference captured
on the kernel boot thread via the `KernelProviders.FLOW_ENGINE` ScopedValue,
not via a static field, system property, or service-locator lookup. This:

- Keeps the engine reference inside its lifecycle scope (cleared after
  `lifecycle.stop()` — re-bootstrap starts with `Optional.empty()`).
- Stays consistent with the events bridge's `EventEngineSupplier` pattern.
- Avoids any cross-classloader leak that would survive a Spring context refresh.

`FlowEngineSupplier` is the deferred-access seam — Spring beans that need the
engine inject the supplier (not the engine itself) and call `tryGet()` or
`requireEngine()` at use time.

- **Guard:** `ExerisFlowBridgeRuntimeIntegrationTest#lifecycleCapturesFlowEngineDuringBootAndClearsItOnStop`
  + `#requireEngineFailsBeforeStartAndAfterStop`.

---

## How invariants are enforced

| Invariant | Primary guard |
|:----------|:--------------|
| Opt-in activation, never default | `ExerisFlowAutoConfigurationTest` |
| `FlowEngine` is runtime owner; Spring is declaration model | Code review + `FlowModuleBoundaryTest` |
| `@Async` is not a flow substitute | `FlowModuleBoundaryTest#doesNotImportSpringAsyncOrTaskExecutor` |
| `timeoutNanos = 0L` sentinel | `ExerisFlowTemplateTest#newContextProducesFreshUuidStateCreatedAndZeroTimeoutSentinel` + cross-restart IT |
| Choreography three-gate ladder | `ExerisFlowChoreographyBridgeTest` + activation-matrix in autoconfig test |
| SmartLifecycle phase ordering | Phase-constant unit test + manual review |
| `flow.*` config alias bridge | Cross-restart runtime IT (would fail at the direct-DB check) |
| Durable saga store selection (kernel) | Kernel TCK + cross-restart runtime IT |
| Cross-restart durable recovery | `parkedFlowSnapshotsSurviveLifecycleRestartViaJdbcStore` IT |
| Tolerant vs strict posture | `ExerisFlowDefinitionRegistrarTest` + `ExerisFlowChoreographyBridgeTest` |
| ScopedValue-captured engine references | `ExerisFlowBridgeRuntimeIntegrationTest` lifecycle capture cases |

These tests must stay green. A failure indicates a real architectural
regression; the test is not the bug.
