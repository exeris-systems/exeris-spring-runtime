# ADR-027: Spring `ApplicationEventPublisher` and Exeris `EventBus` Are Separate Buses

| Attribute       | Value                                                                                                                                                                   |
|:----------------|:------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **Status**      | **ACCEPTED** (codifies existing implementation reality from PR #11; single decider — no future gating event; ratified by the PR that introduces this file)              |
| **Deciders**    | Arkadiusz Przychocki                                                                                                                                                    |
| **Date**        | 2026-05-17                                                                                                                                                              |
| **Scope**       | spring (binds `exeris-spring-runtime-events` Phase 4A; affects any future Spring-side event integration in this repo)                                                   |
| **Owning Repo** | `exeris-spring-runtime`                                                                                                                                                 |
| **Driven By**   | ADR-006 (Spring-Free Kernel Boundary), ADR-010 (Host Runtime Model); PR #11 (Phase 4A events bridge) review follow-up — invariant was implemented and tested but not lifted from `docs/phases/phase-4a-events-invariants.md` §2 to a long-lived ADR |
| **Compliance**  | [Module Boundaries](../architecture/module-boundaries.md), [Phase 4A Events Invariants](../phases/phase-4a-events-invariants.md)                                        |

## Context and Problem Statement

The events bridge (`exeris-spring-runtime-events`, Phase 4A) introduces two surfaces — `ExerisEventPublisher` (Spring bean that publishes onto the kernel `EventBus`) and `@ExerisEventListener` (annotation that subscribes Spring beans as `EventHandler` instances on the same kernel bus). Spring ships its own in-process event mechanism: `ApplicationEventPublisher` / `@EventListener` / `ApplicationListener`, backed by the `ApplicationContext` `ApplicationEventMulticaster`.

These two mechanisms address overlapping vocabulary ("publish an event", "listen for an event") on disjoint planes:

- **`ApplicationEventPublisher`** is a process-local, in-context, synchronous-by-default callback mechanism scoped to a single Spring `ApplicationContext`. It does not cross process boundaries, has no off-heap representation, no descriptor ordinal, and no persistence story.
- **Exeris `EventBus`** is the runtime bus per `KernelProviders.EVENT_ENGINE`. It carries `EventDescriptor`-typed `EventPayload`-backed events, is the substrate for Phase 4B flow choreography (event-driven flow trigger), and is the path that downstream durability stores (`JdbcEventStore`, slab-backed enterprise stores) intercept.

A reviewer reading the events module would reasonably ask: *should the bridge wire `ApplicationEventPublisher` into the Exeris bus, so a Spring bean publishing via the Spring API "just works" on the runtime bus?* That wiring exists in adjacent ecosystems (e.g. Spring Cloud Stream binds `@StreamListener` / Spring messaging onto its own broker abstractions). Phase 4A deliberately did **not** do this. The implementation invariant lives in `docs/phases/phase-4a-events-invariants.md` §2 and is enforced by `EventModuleBoundaryTest`, but phase-invariants documents have phase-scoped lifecycle — once a phase closes, its invariants are at risk of fading. The PR #11 review asked for the invariant to be lifted to a long-lived ADR.

This ADR answers: **does the events bridge wire Spring's in-context event multicaster into the runtime bus, and on what grounds?**

## 🏁 The Decision

**Spring `ApplicationEventPublisher` and the Exeris `EventBus` are two separate buses; the events module never wires one into the other in either direction. An application that needs both keeps them separate, and the boundary is operator-visible.**

The two mechanisms address different planes (in-context callbacks vs. runtime bus), and bridging them would invert ownership — Spring's `ApplicationEventMulticaster` would become the event-routing authority on the Exeris path, with the kernel `EventBus` as a passive subscriber or downstream republisher. That is the exact inversion ADR-010 forbids. The boundary is not a stylistic choice; it is the same Wall invariant that keeps the kernel Spring-free, applied at the events-module layer.

**Concrete obligations:**

1. **No `ApplicationEventPublisher` import or runtime dependency from any production class of `exeris-spring-runtime-events`.** `EventModuleBoundaryTest#doesNotDependOnSpringApplicationEventPublisher` (existing) is the merge-blocking guard. Importing the type, declaring a field/parameter of that type, or injecting it via `@Autowired` in any production class is an ADR-violating PR — the ArchUnit assertion runs in the standard test phase and fails the build. (The type is necessarily present on the compile classpath transitively via `spring-context`, which the module legitimately consumes for `@Component`, `ApplicationContext`, and `SmartLifecycle`; the guard targets *use*, not classpath membership. See Engineering Protocol §3 for the rationale.)
2. **No `org.springframework.context.event..` package import from the events module.** Bridging Spring `@EventListener` annotations into Exeris bus subscriptions — even via reflection — is forbidden. `EventModuleBoundaryTest#doesNotDependOnSpringContextEventPackage` enforces this.
3. **No implicit `@EventListener` → Exeris bus bridge.** Subscribing to the kernel bus requires the explicit `@ExerisEventListener` annotation. A future PR that adds "if `@EventListener` is on a method, also subscribe it to the Exeris bus" implicit-magic bridge violates this ADR. The producer/consumer relationship stays operator-visible at code-review time and at telemetry inspection time.
4. **No reverse bridge from Exeris bus into `ApplicationEventPublisher` either.** Forwarding kernel events into `ApplicationEventPublisher.publishEvent(...)` would re-introduce the inversion in the opposite direction (kernel becomes a producer of Spring application events, Spring becomes the multicast authority). The module does not publish onto Spring's event channel.
5. **Mode declaration is unaffected.** Both surfaces remain available to a Pure Mode application — they are simply distinct. An application can use `ApplicationEventPublisher` for in-context Spring callbacks (e.g. `ContextRefreshedEvent`, application-level state notifications scoped to the JVM) and `ExerisEventPublisher` for runtime-bus events that participate in flow choreography, persistence, and cross-process semantics. No `MIXED` mode declaration is required to use both — they do not interact.

## Bus boundary at a glance

| Surface                                  | Plane                              | Substrate                                  | Cross-process? | Participates in flow choreography? |
|:-----------------------------------------|:-----------------------------------|:-------------------------------------------|:---------------|:-----------------------------------|
| `ApplicationEventPublisher.publishEvent` | In-context Spring callback         | `ApplicationEventMulticaster`              | No             | No                                 |
| `@EventListener` (Spring)                | In-context Spring callback         | `ApplicationListener` registered in context | No            | No                                 |
| `ExerisEventPublisher.publish`           | Runtime bus                        | `KernelProviders.EVENT_ENGINE.bus()`       | Per `EventEngine` impl | Yes (Phase 4B `ExerisFlowChoreographyBridge`) |
| `@ExerisEventListener`                   | Runtime bus                        | `EventHandler` subscribed via `EventBus.subscribe(...)` | Per `EventEngine` impl | Yes                              |

No row in this table forwards to another row. The two top rows are Spring-side and stay inside Spring; the two bottom rows are Exeris-side and stay inside the kernel.

## Consequences

### ✅ Positive Outcomes

- **[+] Ownership boundary stays correctly placed.** Spring owns DI, bean lifecycle, and in-context callbacks; Exeris owns the runtime bus. Neither is reduced to a passive subscriber of the other. Matches ADR-010 §"Spring is the application framework. Exeris is the runtime owner."
- **[+] Operator-visible producer/consumer relationship.** A reviewer reading a class with `@ExerisEventListener` sees it on the Exeris bus; one with `@EventListener` sees it on the Spring bus. Implicit cross-bridging would force the reviewer to know runtime configuration to decide which bus a given handler attaches to.
- **[+] Future Phase 4B choreography stays bounded.** `ExerisFlowChoreographyBridge` activates flows from kernel events. Because the bus boundary is closed, a Spring bean cannot accidentally trigger a flow by calling `ApplicationEventPublisher.publishEvent(...)` — the flow surface is reachable only through the explicit runtime-bus path.
- **[+] Phase 4A invariants gain long-lived load-bearing status.** Lifting §2 of `phase-4a-events-invariants.md` to ADR scope means the invariant survives the phase-closure cadence and is cited by ADR number when blocking future PRs.

### ⚠️ Trade-offs

- **[-] Two publish APIs in the same JVM.** Application authors must learn which bus does which job. Mitigation: invariants doc and ADR distinguish the planes; the bus-boundary table above is the operator-readable summary.
- **[-] No "single bus" affordance for migrating Spring codebases that lean heavily on `ApplicationEventPublisher`.** A brownfield Spring app with extensive `@EventListener` usage migrating to the Exeris runtime keeps its in-context events on Spring's bus until it explicitly ports event sources to `ExerisEventPublisher`. The migration is opt-in per event family. This is intentional: a silent bridge would obscure which events participate in choreography/persistence and which do not.
- **[-] No "Spring-style fan-out" semantics on the Exeris bus from Spring beans.** A Spring bean that wants Spring-fan-out semantics (synchronous in-context, ordered, transaction-aware via `@TransactionalEventListener`) keeps using `ApplicationEventPublisher`. The Exeris bus does not promise those semantics — its delivery contract is the kernel `EventEngine`'s, not Spring's.

### 📋 What is NOT in scope

- The **kernel-internal** decision of how `EventBus` itself delivers, persists, or fans out events. That is owned by `exeris-kernel` and is governed by kernel SPI / persistence ADRs (`ADR-013` saga state distribution, `ADR-022` persistence SPI extension), not this one.
- **Cross-process event transport.** Whether the Exeris bus carries events across JVMs is a kernel-side question (engine implementation: Community in-process, Enterprise slab-backed with off-heap persistence). This ADR only governs the Spring-side boundary at the events module.
- **Flow-side coupling.** Whether and how a flow step references a Spring bean is governed by `phase-4b-flow-invariants.md` and Phase 5/4B-step closures. This ADR does not constrain flow-step closures.
- **Future Phase 4C graph integration.** Graph events (if any) sit on their own SPI and are out of this ADR's scope.
- **Spring `ApplicationEventPublisher` use inside the host application itself.** Application code is free to use `ApplicationEventPublisher` for in-context Spring callbacks; this ADR does not regulate application-side use of Spring's mechanism. It only regulates the events module's own surface.

## Cross-references

- ADR-006 — Spring-Free Kernel Boundary (The Wall): `exeris-docs/adr/ADR-006-spring-free-kernel-boundary.md` — the parent invariant this ADR specializes at the events-module layer
- ADR-010 — Host Runtime Model: `docs/adr/ADR-010-host-runtime-model.md` — the ownership inversion this ADR prevents
- ADR-011 — Pure Mode vs Compatibility Mode: `docs/adr/ADR-011-pure-mode-vs-compatibility-mode.md` — mode taxonomy; this ADR adds no new mode
- ADR-021 — Gateway-Class Workloads Out of Compatibility Scope: `docs/adr/ADR-021-gateway-class-workloads-out-of-compatibility-scope.md` — same "bridge would invert ownership" reasoning at a different boundary
- `docs/phases/phase-4a-events-invariants.md` §2–§3 — the phase-scoped invariants that this ADR lifts to long-lived status (§2 covers the bus-separation rule that maps to obligations #1–#2 and #4; §3 covers the no-implicit-bridge rule that maps to obligation #3)
- `exeris-spring-runtime-events/src/test/java/eu/exeris/spring/runtime/events/EventModuleBoundaryTest.java` — the merge-blocking guards

## Engineering Protocol

The ADR is descriptive — it codifies the existing implementation that landed in PR #11 (commit 70c7077) and was kept green through Phase 4A closure (PR #15) and Phase 4B Steps 1–4 (PRs #17, #18, #23, #27). No migration is required.

Enforcement:

1. **`EventModuleBoundaryTest#doesNotDependOnSpringApplicationEventPublisher`** (`exeris-spring-runtime-events` test scope) — ArchUnit assertion that no production class in the events module imports `org.springframework.context.ApplicationEventPublisher`. Runs in the standard `test` phase; failure blocks merge.
2. **`EventModuleBoundaryTest#doesNotDependOnSpringContextEventPackage`** — ArchUnit assertion that no production class in the events module imports from `org.springframework.context.event..` (which would cover `@EventListener`, `EventListenerMethodProcessor`, `ApplicationEventMulticaster`, etc.). Same merge-blocking semantics.
3. **`PureModeClasspathGuardTest`** (per-module, shared baseline) — does not specifically guard this boundary, but its banned-dependency set forbids transports that would tempt a bridge (Tomcat, Netty, Reactor). This ADR's guards are tighter than the classpath baseline because the offending dependency (`org.springframework.context.ApplicationEventPublisher`) is part of `spring-context`, which the module legitimately consumes for `@Component`, `ApplicationContext`, and `SmartLifecycle`. Sub-package and specific-type bans are the right tool here.
4. **Documentation precedence** (per `CLAUDE.md`): an ADR-cited rule wins over a phase-invariants restatement when the two drift. Future edits to `phase-4a-events-invariants.md` §2–§3 must remain consistent with this ADR — the invariants doc may add detail but may not relax the bus-separation rule without superseding this ADR.

> **`allowEmptyShould(true)` semantics — known coverage edge case.** Both `EventModuleBoundaryTest` ArchUnit rules carry `.allowEmptyShould(true)`. This means the rules pass silently if **zero production classes exist in `eu.exeris.spring.runtime.events..`** — appropriate for CI resilience during initial module scaffolding, but it implies that a hypothetical wholesale deletion of all events-module production sources would not be caught by these guards alone. The "merge-blocking" claim above is accurate for class-level mutations (the common case); a "delete-everything-then-reintroduce-with-bridge" attack vector is out of scope for these guards. Reviewers blocking via ADR-027 should rely on diff inspection in addition to the guards when a PR removes production sources from the events module wholesale.

A future PR that proposes wiring the two buses (either direction, with or without a property flag) is an ADR-027-violating PR; the reviewer cites this ADR by number when blocking.
