# Phase 4A Invariants — Events Bridge

**Status:** Locked-in (Phase 4A closed 2026-05-09; preview-track, default-off)
**Source of authority:** ADR-006 (The Wall), ADR-010 (Host Runtime Model),
ADR-011 (Pure vs Compatibility Mode), and the master plan
[`phase-4-events-flow.md`](phase-4-events-flow.md). This page enumerates the
non-negotiable invariants Phase 4A established for the Exeris events bridge.

A change that breaks any item below is an architectural regression, not a style
issue, and requires a superseding ADR — not a workaround in code.

Phase 0–3 invariants
([`phase-0-invariants.md`](phase-0-invariants.md),
[`phase-1-invariants.md`](phase-1-invariants.md),
[`phase-2-invariants.md`](phase-2-invariants.md),
[`phase-3-invariants.md`](phase-3-invariants.md))
still apply in full; Phase 4A invariants are additive and events-specific.

These are also the **graduation gate** for promoting `exeris-spring-runtime-events`
from 1.0 preview to bounded GA in 1.0.x. A regression on any of these blocks
graduation.

---

## 1. Events bridge is opt-in and never default

`ExerisEventAutoConfiguration` activates only when
`exeris.runtime.events.enabled=true`. The conditional is
`@ConditionalOnProperty(prefix="exeris.runtime.events", name="enabled",
matchIfMissing=false)` — there is **no `matchIfMissing=true`** and there is no
auto-detection that silently activates the module.

When disabled, the artefact compiles in but no beans are created — zero runtime
overhead on the application's hot paths, zero classpath risk for apps that do
not need events.

- **Guard:** `ExerisEventAutoConfigurationTest#doesNotActivateByDefault` and
  `#activatesWhenExplicitlyEnabled`.

## 2. Spring `ApplicationEventPublisher` ↔ Exeris `EventBus` are SEPARATE — no inversion

The Exeris bus is the runtime bus; Spring `ApplicationContext` events are
application-local in-process callbacks. They are **two separate buses**.

- The events module **must not** wire `ApplicationEventPublisher` to the
  Exeris bus.
- The events module **must not** bridge Spring `@EventListener` annotations
  into Exeris bus subscriptions.
- An app that needs both keeps them separate; the boundary is operator-visible.

This is the most consequential ownership invariant in Phase 4A. Wiring the two
buses would invert ownership — Spring's `ApplicationContext` would become the
event-routing authority, with Exeris as a passive subscriber. That is the exact
inversion ADR-010 forbids.

- **Guards:**
  - `EventModuleBoundaryTest#doesNotDependOnSpringApplicationEventPublisher`
  - `EventModuleBoundaryTest#doesNotDependOnSpringContextEventPackage`

## 3. `@EventListener` (Spring) → Exeris bus implicit bridge is BANNED

A future PR that adds "if `@EventListener` is on a method, also subscribe it to
the Exeris bus" implicit-magic bridge violates this invariant. Subscribing to
the Exeris bus requires the explicit `@ExerisEventListener` annotation. This
keeps the producer/consumer relationship operator-visible at code-review time.

## 4. Subscription cleanup at `SmartLifecycle.stop()` is mandatory

`ExerisEventListenerRegistrar implements SmartLifecycle`. On `stop()`, **every**
`SubscriptionToken` it issued during startup must be cancelled. There is no
"lazy GC" path — cancellation is synchronous and complete before `stop()`
returns.

This is the graceful-shutdown invariant for the events module: a context that
shuts down cleanly leaves zero registered handlers behind, so a subsequent
context refresh does not double-dispatch.

- **Guard:** `ExerisEventListenerRegistrarTest#unsubscribesAllSubscriptionsOnStop`.

## 5. `EventDescriptor` ordinals come from `ExerisEventTypeRegistry` — no manual construction

Application code must obtain `EventDescriptor` through
`ExerisEventTypeRegistry.descriptorFor(String typeName)` (or via the publisher's
convenience overload that delegates to it). Manual construction of an
`EventDescriptor` with a hardcoded ordinal is a regression risk — kernel
ordinals can shift across registry rebuilds and are an internal detail.

- **Guards:** `ExerisEventTypeRegistryTest#ordinalOfDelegatesToKernelRegistry`,
  `#unknownEventTypeFailsLoudly` (clear error message rather than silent
  zero-ordinal dispatch); `ExerisEventPublisherTest#convenienceOverloadBuildsDescriptorViaTypeRegistry`.

## 6. `EventPayload` is a RAII resource — try-with-resources contract

`EventPayload` is heap-backed in Community and off-heap slab-allocated in
Enterprise. Application code **must** use try-with-resources:

```java
try (EventPayload p = ...) {
    publisher.publish(descriptor, p);
}
```

Caching an `EventPayload` reference, sharing it across request scope
boundaries, or allowing it to escape into a callback that outlives the
publishing scope is forbidden. The lifecycle is explicit; auto-close is the
only safe pattern.

This invariant exists because the Enterprise off-heap implementation has
deterministic release semantics — leaking payloads exhausts the slab and
backpressures the bus. Spring beans cannot be allowed to defeat that contract.

## 7. `KernelProviders.EVENT_ENGINE` is resolved per call, never captured at bean construction

`EventEngineSupplier.get()` reads `KernelProviders.EVENT_ENGINE` each time it
is invoked. The engine reference is **never** captured as a static singleton at
bean construction or at autoconfiguration time.

This preserves the kernel `ScopedValue` contract — the engine is always read
from the current kernel VT scope. The same pattern is used for
`PersistenceEngineProvider` (Phase 3 invariant #3).

- **Guard:** `ExerisEventPublisherTest#engineUnavailableFailsLoudly`,
  `ExerisEventTypeRegistryTest#engineUnavailableThrowsClearMessage`.

## 8. Engine unavailability has bounded behaviour: tolerant vs strict

`ExerisEventListenerRegistrar` operates in two modes:

- **Tolerant** (no `@ExerisEventListener` beans declared) — engine
  unavailability is silently tolerated; `start()` transitions to running
  without subscriptions. Used by apps that enable the module but have not yet
  declared listeners.
- **Strict** (one or more `@ExerisEventListener` beans declared) — engine
  unavailability fails loudly. An app that declares a listener but cannot
  reach the engine has a configuration bug, not a soft-degraded state.

There is no third "silent failure" mode. Either listeners reach the bus, or
the bridge fails the bean lifecycle with a clear message.

- **Guards:** `ExerisEventListenerRegistrarTest#engineUnavailableInTolerantModeSkipsSubscriptionsButTransitionsToRunning`,
  `#engineUnavailableInStrictModeFailsLoudWhenListenersDeclared`,
  `#engineUnavailableWithoutListenersIsAlwaysTolerated`.

## 9. Module boundary: no web, no tx, no persistence, no JPA

`exeris-spring-runtime-events` does not depend on `web`, `tx`, `data`, or
`actuator`. It does not import servlet, HTTP, persistence, transaction, or JPA
packages.

Events are an orthogonal cross-cutting concern. Bundling tx-coupled outbox
patterns or JPA persistence into the events module would entangle the activation
graph and bleed Phase 3 ownership into Phase 4A.

If an outbox pattern is needed (Kafka, PostgreSQL outbox), it belongs in
`exeris-spring-runtime-data` or a dedicated `exeris-spring-runtime-outbox`
module — never in `exeris-spring-runtime-events`.

- **Guards:**
  - `EventModuleBoundaryTest#doesNotImportHttpOrServletPackages`
  - `EventModuleBoundaryTest#doesNotImportTransactionOrPersistencePackages`
  - `EventModuleBoundaryTest#doesNotImportJpaOrHibernate`

## 10. Pure-Mode classpath baseline applies to events module

Per Phase 1 invariant #10: every Pure Mode module ships its own
`PureModeClasspathGuardTest` enforcing the bans (`jakarta.servlet..`,
`io.netty..`, `reactor..`, `org.springframework.web.reactive..`,
`org.springframework.web.servlet.DispatcherServlet`).

`exeris-spring-runtime-events` ships its own — bringing the matrix to **6
modules** (`autoconfigure` + `web` + `tx` + `data` + `actuator` + `events`). New
preview modules in Phase 4B / Phase 5 must add the same guard set before merge.

- **Guard:** `PureModeClasspathGuardTest` (events module test scope).

---

## How invariants are enforced

| Invariant | Primary guard |
|:---|:---|
| Opt-in activation, never default | `ExerisEventAutoConfigurationTest` |
| `ApplicationEventPublisher` ↔ `EventBus` separate | `EventModuleBoundaryTest` (2 rules) |
| No implicit `@EventListener` → Exeris bridge | Code review + `EventModuleBoundaryTest` |
| Subscription cleanup at shutdown | `ExerisEventListenerRegistrarTest#unsubscribesAllSubscriptionsOnStop` |
| `EventDescriptor` ordinals via registry | `ExerisEventTypeRegistryTest` (2 rules) + `ExerisEventPublisherTest#convenienceOverloadBuildsDescriptorViaTypeRegistry` |
| `EventPayload` RAII | Documented in `ExerisEventPublisher` Javadoc + try-with-resources usage in registrar handler invocation |
| `EVENT_ENGINE` resolved per call | `ExerisEventPublisherTest#engineUnavailableFailsLoudly` + `ExerisEventTypeRegistryTest#engineUnavailableThrowsClearMessage` |
| Tolerant vs strict mode | `ExerisEventListenerRegistrarTest` (3 mode rules) |
| Module boundary | `EventModuleBoundaryTest` (5 rules) |
| Pure-Mode classpath ban | `PureModeClasspathGuardTest` × 6 modules |

These tests must stay green. A failure indicates a real architectural
regression; the test is not the bug.

---

## Known issue (post-merge follow-up)

`ExerisEventBridgeRuntimeIntegrationTest` (2 tests) is **environment-sensitive**:
the test does not set `exeris.runtime.network.port=0`, so `BindException`
surfaces when default port 8080 is occupied on the runner. The fix is a
one-line property addition; tracked separately as the test exercises full
runtime startup (which is orthogonal to the events bridge invariants above —
the bridge code paths are exercised by the unit + module-level tests, all of
which are green).

---

## Graduation criterion (preview → 1.0.x bounded GA)

Per the roadmap's single graduation criterion (applies in both 1.0 and 1.0.x):

> Verification gates clear (subscription cleanup at `SmartLifecycle.stop()`,
> no Spring `ApplicationEventPublisher` ↔ Exeris `EventBus` inversion, no
> `@Async` workaround, default-off properties) **and** at least one
> downstream service has run the events bridge in production for a
> representative period.

This invariants page is the gate-test list for the first half. The second
half (real production adoption) is operational and measured separately.
Either gap keeps the module preview.
