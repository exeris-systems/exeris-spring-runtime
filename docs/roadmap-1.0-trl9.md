# Roadmap to 1.0 and TRL-9

**Status basis:** repository state as of 2026-05-09  
**Intent:** delivery planning and scope control, not a claim of production readiness.

---

## Current Repo Status Summary

| Area | Current state | Honest interpretation |
|:-----|:--------------|:----------------------|
| Phase 0 | Substantially complete | Bootstrap, lifecycle, and Wall verification are in place. |
| Phase 1 | Mature for repo-local proof | Pure Mode ingress is demonstrated through Exeris-owned request handling and wire-level integration tests, with ongoing hardening still recommended. |
| Phase 2 | In progress, scaffolded | Compatibility Mode wiring exists, but this is not yet full Spring MVC parity and must stay opt-in. |
| Phase 3 | In progress, scaffolded | Transaction and data modules are default-off and should not yet be described as production ownership transfer. |
| Phase 4A (events) | Promoted to 1.0 preview | Bounded scope; ships default-off as a preview to unblock downstream Spring services that need event-driven choreography during their migration onto the runtime. |
| Phase 4B (flow/saga) | Promoted to 1.0 preview | Bounded scope; ships default-off as a preview alongside 4A. Choreography wiring depends on 4A; snapshot persistence depends on Phase 3 maturing. |
| Phase 4C (graph) | Post-1.0 | TRL-4, lab tests pending; not required to validate the 1.0 host-runtime story. Remains a 1.1.x candidate. |

### Recommended 1.0 Scope

The production 1.0 target should stay deliberately narrow:

- **Primary GA story:** Exeris-owned Pure Mode runtime path for Spring applications.
- **Secondary story:** explicitly bounded Compatibility Mode support for selected web semantics.
- **Conditional story:** tx/data bridges may ship only if their verification gates pass without ownership inversion; otherwise they remain preview/default-off.
- **Conditional story:** Phase 4A (events) and 4B (flow/saga) bridges ship as preview default-off so downstream services can adopt event-driven choreography and saga semantics during their runtime migration. They are not GA promises in 1.0; they graduate based on the same verification gates as tx/data.
- **Out of 1.0 scope:** Phase 4C (graph) module unless a hard blocker appears — kernel Graph SPI is at TRL-4 and the lab test scope is still pending.

---

## Recommended Release Train

| Version | Feature focus | Finetuning focus | Quality gate | Target TRL |
|:--------|:--------------|:-----------------|:-------------|:-----------|
| 0.1.0-SNAPSHOT | Architecture baseline, bootstrap proof, Pure Mode ingress proof | Docs, ADR alignment, module boundaries | Guard tests and integration proofs remain green | TRL-4 to TRL-5 |
| 0.2.0-alpha | Pure Mode hardening | error mapping polish, telemetry/actuator fit, graceful shutdown, route stability | repeatable wire-level runtime tests; no servlet/netty creep | TRL-5 |
| 0.3.0-beta | Compatibility Mode subset | controller mapping coverage, JSON handling, explicit unsupported-feature matrix | documented compatibility cost; pure-mode non-regression | TRL-6 |
| 0.4.0-preview | Transaction and persistence preview | REQUIRED propagation, request/context isolation, native repository path, ADR-012-bounded JDBC bridge | concurrent leak tests, rollback cleanup, default-off safety | TRL-6 to TRL-7 |
| 0.5.0-preview | Events + Flow/Saga preview (Phase 4A + 4B) | `ExerisEventPublisher` / `@ExerisEventListener`, `ExerisFlowDefinition` / `ExerisFlowTemplate`, opt-in choreography via `ExerisFlowChoreographyBridge` | event subscription cleanup at shutdown, flow park/wake without thread pinning, no ownership inversion of Spring `ApplicationEventPublisher`, default-off properties | TRL-6 to TRL-7 |
| 0.9.0-rc1 | Release hardening | support matrix, migration notes, failure handling, dependency hygiene | regression freeze, soak tests, operational runbooks | TRL-7 to TRL-8 |
| 1.0.0 | Narrow production GA | Pure Mode GA, bounded Compatibility Mode, optional tx/data and events/flow only if gates are met | staged production rollout evidence, upgrade/rollback guidance, support statement | TRL-8 |

> **Recommendation:** do not delay 1.0 waiting for Phase 4C (graph). Phase 4A (events) and 4B (flow/saga) ship as preview alongside tx/data — if either bundle's gates are not met by the release-candidate stage, keep them preview rather than expanding the GA promise.

---

## TRL Progression Plan

| TRL | Meaning in this repository | Planned evidence |
|:----|:---------------------------|:-----------------|
| TRL-4 | Component validation in controlled development conditions | module tests, boot proofs, architecture guards |
| TRL-5 | Integrated validation of the Pure Mode request path | repeatable wire-level request/response execution under Exeris-owned ingress |
| TRL-6 | Representative subsystem demos with explicit compatibility boundaries | Compatibility Mode subset and tx/data previews demonstrated without ownership drift |
| TRL-7 | Pre-production readiness in realistic environments | soak, shutdown, recovery, and dependency regression checks |
| TRL-8 | First bounded production deployment | runbooks, support matrix, staged rollout evidence |
| TRL-9 | Proven operational maturity | multiple real deployments, stable upgrades, incident response evidence, and routine operations |

**Important:** TRL-9 is an operational outcome, not a version number. The repository can reasonably target **TRL-8 at 1.0.0** and then progress to **TRL-9 across 1.0.x / 1.1.x** through real service adoption and production evidence.

---

## Version-by-Version Plan

### 0.2.0-alpha
- Finish the remaining Phase 1 hardening items.
- Keep the Pure Mode path lean, measurable, and well documented.
- Freeze the claim set around what is already proven in-repo.

### 0.3.0-beta
- Deliver the first bounded Compatibility Mode feature set.
- Publish a small support matrix covering supported controller patterns and known exclusions.
- Add explicit measurement of the compatibility cost versus the Pure Mode baseline.

### 0.4.0-preview
- Advance Phase 3 only where Exeris remains the runtime owner.
- Prefer native repository integration first; keep any JDBC bridge narrow, opt-in, and justified by ADR-012.
- Verify cleanup, rollback, and isolation under concurrent execution.

### 0.5.0-preview
- Land Phase 4A (events bridge) and 4B (flow/saga bridge) as opt-in modules: `exeris-spring-runtime-events` and `exeris-spring-runtime-flow`.
- Default-off via `exeris.runtime.events.enabled` and `exeris.runtime.flow.enabled`; activation must remain explicit, never silently enabled.
- Keep Spring `ApplicationEventPublisher` and the Exeris `EventBus` separate; never wire one into the other.
- Verify subscription cleanup at `SmartLifecycle.stop()`, choreography activation gated on `FlowEngineCapabilities.choreographySupport()`, and that step lambdas closing over Spring beans do not invert lifecycle ownership.
- Do not promote 4A/4B to GA in 1.0 unless their verification gates are clean — preview status is the safe default.

### 0.9.0-rc1
- Enter release hardening rather than feature expansion.
- Close documentation drift, freeze supported scope, and publish migration guidance.
- Confirm that Pure Mode remains the performance and ownership reference path.

### 1.0.0
- Release a truthfully bounded production profile.
- Guarantee what is tested and documented; defer broad framework parity claims.
- Treat unresolved high-risk compatibility areas as preview, not GA promises.

---

## Phase 4 Recommendation

Phase 4 is split for the 1.0 train:

- **Phase 4A (events bridge) and Phase 4B (flow/saga bridge) — preview in 1.0.**
  Driven by downstream Spring services that are migrating onto the runtime now and need event-driven choreography and saga semantics during, not after, that migration. Both modules ship default-off via property flags, activate explicitly, and graduate via the same verification gates as tx/data — no GA promise unless the gates are met.
- **Phase 4C (graph integration) — post-1.0.**
  The kernel Graph SPI is at TRL-4 and the lab test scope is still pending. Pulling 4C into 1.0 would expand the module surface without clear in-flight migration demand, so it stays in the 1.1.x candidate window.

Reasons to keep 4C deferred:
1. It expands the module surface in a TRL-4 SPI without an in-flight migration use case.
2. It is not required to prove the central host-runtime claim from ADR-010.
3. It introduces new lifecycle and subsystem coupling that is better addressed after the core runtime path is operationally stable.

Only pull 4C earlier if it becomes a **hard blocker** for a Phase 1-3 outcome or a downstream service migration. Otherwise, use the post-1.0 train below:

| Post-1.0 train | Candidate scope |
|:---------------|:----------------|
| 1.0.x | 4A/4B graduation from preview to bounded GA based on real downstream adoption evidence |
| 1.1.x | graph integration spike (4C), production evaluation of 4A/4B GA |
| 1.2.x | graph integration hardening, choreography expansion, multi-tenant flow validation |

---

## Release Management Notes

- Keep Pure Mode as the default and reference architecture.
- Keep Compatibility Mode explicit and never silently enabled.
- Keep new tx/data behavior default-off until verification proves safe ownership boundaries.
- Keep events (4A) and flow/saga (4B) default-off in 1.0 preview; activation remains explicit. Spring `ApplicationEventPublisher` is never wired into the Exeris `EventBus`, and `@Async` is never reintroduced as a workaround for missing flow capabilities.
- Update the roadmap whenever a phase changes from proof, to preview, to supported production scope.
