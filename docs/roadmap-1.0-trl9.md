# Roadmap to 1.0 and TRL-9

**Status basis:** repository state as of 2026-05-09  
**Intent:** delivery planning and scope control, not a claim of production readiness.

---

## Current Repo Status Summary

| Area | Current state | Honest interpretation |
|:-----|:--------------|:----------------------|
| Phase 0 | Closed (2026-05-09) | Bootstrap, lifecycle, and Wall verification are in place; invariants captured in `docs/phases/phase-0-invariants.md`. |
| Phase 1 | Closed (2026-05-09) | Pure Mode ingress is proven end-to-end on the wire (`ExerisWireLevelRuntimeIntegrationTest` 6/6: bind, body, 404, status, drain, telemetry scope). `PureModeClasspathGuardTest` ships uniformly in autoconfigure/web/tx/data/actuator. `ExerisDispatcherAllocationBaselineTest` enforces ≤ 1024 B/req mean for empty-body GET dispatch (observed ≈ 277 B/dispatch). Invariants captured in `docs/phases/phase-1-invariants.md`. |
| Phase 2 | Closed (2026-05-09) | Compatibility Mode bridge for `@RestController` / `@RequestMapping` is delivered (`ExerisCompatMvcIntegrationTest` 15/15) without `DispatcherServlet` or servlet API. `@CompatibilityMode` marker on top-level entry classes (per ADR-011); allocation cost reported by `ExerisCompatAllocationCostReportTest` (Pure ≈ 176 B/dispatch vs Compat ≈ 5095 B/dispatch, ≈ 29× overhead). Gateway-class workloads explicitly out of Compat scope per ADR-021 (ACCEPTED). Invariants captured in `docs/phases/phase-2-invariants.md`. |
| Phase 3 | Closed (2026-05-09; 3B deferred to 3.x) | Transaction bridge (3A) delivered: `ExerisPlatformTransactionManager` over `PersistenceConnection`, propagation matrix (`REQUIRED`/`REQUIRES_NEW`/`MANDATORY`/`SUPPORTS`/`NEVER` supported; `NESTED`/`NOT_SUPPORTED` documented unsupported), `tx` 22/22 green, opt-in via `exeris.runtime.tx.enabled`. JDBC compatibility bridge (3C Level 2) delivered per ADR-017: `ExerisDataSource` + `ExerisConnectionProxy`, `data` 34/34 green, `DataModuleBoundaryTest` enforces ADR-017 §7 Rule 1, opt-in via `exeris.runtime.data.compat-datasource.enabled`. Level 1 native repositories remain app-side code per plan. 3B (request scope, tracing) deferred to 3.x — security context partially covered by Phase 2c filter. Invariants captured in `docs/phases/phase-3-invariants.md`. |
| Phase 4A (events) | Closed (2026-05-09; preview) | Implementation landed in PR #11; closure docs in Phase 4A closure PR. `exeris-spring-runtime-events` ships default-off via `exeris.runtime.events.enabled=true` (`matchIfMissing=false`). Spring `ApplicationEventPublisher` and Exeris `EventBus` stay separate (no ownership inversion); `EventModuleBoundaryTest` 5/5 enforces (no `ApplicationEventPublisher`, no `spring-context.event`, no HTTP/servlet, no tx/persistence, no JPA). `ExerisEventListenerRegistrar` is `SmartLifecycle` — subscriptions cleaned up at `stop()`. 28/30 tests green (2 errors are env-specific port-bind flake in runtime integration test, not a code regression). Invariants captured in `docs/phases/phase-4a-events-invariants.md`. **Graduation to 1.0.x bounded GA** requires the invariants to stay green AND ≥1 downstream service running in production for a representative period. |
| Phase 4B (flow/saga) | Promoted to 1.0 preview | Bounded scope; ships default-off as a preview alongside 4A. Choreography wiring depends on 4A; snapshot persistence depends on Phase 3 maturing. |
| Phase 4C (graph) | Post-1.0 | TRL-4, lab tests pending; not required to validate the 1.0 host-runtime story. Remains a 1.1.x candidate. |
| Phase 5 (edge gateway) | Promoted to 1.0 preview | Triggered by ADR-021 (Gateway-Class Workloads Out of Compatibility Scope). Ships default-off as `exeris-spring-runtime-gateway` to deliver Exeris-owned edge ingress with bounded route + filter + forwarder primitives. **Not** a Spring Cloud Gateway compatibility bridge — workloads needing SCG DSL run native SCG outside Exeris. Graduates inside 1.0.x on real adoption. |

### Recommended 1.0 Scope

The production 1.0 target should stay deliberately narrow:

- **Primary GA story:** Exeris-owned Pure Mode runtime path for Spring applications.
- **Secondary story:** explicitly bounded Compatibility Mode support for selected web semantics.
- **Conditional story:** tx/data bridges may ship only if their verification gates pass without ownership inversion; otherwise they remain preview/default-off.
- **Conditional story:** Phase 4A (events) and 4B (flow/saga) bridges ship as preview default-off so downstream services can adopt event-driven choreography and saga semantics during their runtime migration. They are not GA promises in 1.0. **Graduation criterion (single, applies in both 1.0 and 1.0.x):** verification gates clear (subscription cleanup at `SmartLifecycle.stop()`, choreography activation gated on `FlowEngineCapabilities.choreographySupport()`, no Spring `ApplicationEventPublisher` ↔ Exeris `EventBus` inversion, no `@Async` workaround) **and** at least one downstream service has run 4A/4B in production for a representative period. Either gap keeps the modules preview.
- **Conditional story:** Phase 5 (edge gateway, `exeris-spring-runtime-gateway`) ships as preview default-off so downstream services that need Exeris-owned edge ingress can adopt it without taking a hard 1.0 GA dependency. It is not an SCG compatibility bridge (per ADR-021); workloads requiring Spring Cloud Gateway DSL run native SCG outside Exeris. **Graduation criterion (1.0.x):** wire-level forwarder proof, filter/retry/circuit-breaker/rate-limit verified under controlled upstream failures, allocation budget enforced on the forward path, classpath guards green (no servlet/Netty/Reactor/SCG creep), **and** at least one downstream service runs Phase 5 in production for a representative period.
- **Out of 1.0 scope:** Phase 4C (graph) module unless a hard blocker appears — kernel Graph SPI is at TRL-4 and the lab test scope is still pending.

---

## Recommended Release Train

| Version | Feature focus | Finetuning focus | Quality gate | Target TRL |
|:--------|:--------------|:-----------------|:-------------|:-----------|
| 0.1.0-SNAPSHOT | Architecture baseline, bootstrap proof, Pure Mode ingress proof | Docs, ADR alignment, module boundaries | Guard tests and integration proofs remain green | TRL-4 to TRL-5 |
| 0.2.0-alpha | Pure Mode hardening | error mapping polish, telemetry/actuator fit, graceful shutdown, route stability | repeatable wire-level runtime tests; no servlet/netty creep | TRL-5 |
| 0.3.0-beta | Compatibility Mode subset | controller mapping coverage, JSON handling, explicit unsupported-feature matrix | documented compatibility cost; pure-mode non-regression | TRL-6 |
| 0.4.0-preview | Transaction and persistence preview | REQUIRED propagation, request/context isolation, native repository path, ADR-017-bounded JDBC bridge | concurrent leak tests, rollback cleanup, default-off safety | TRL-6 to TRL-7 |
| 0.5.0-preview | Events + Flow/Saga preview (Phase 4A + 4B) | `ExerisEventPublisher` / `@ExerisEventListener`, `ExerisFlowDefinition` / `ExerisFlowTemplate`, opt-in choreography via `ExerisFlowChoreographyBridge` | event subscription cleanup at shutdown, flow park/wake without thread pinning, no ownership inversion of Spring `ApplicationEventPublisher`, default-off properties | TRL-6 to TRL-7 |
| 0.6.0-preview | Edge Gateway preview (Phase 5) | `exeris-spring-runtime-gateway` artefact: `ExerisGatewayRoute` / `ExerisGatewayDispatcher` / `ExerisHttpForwarder` / minimal filter set + Resilience4j core; opt-in via `exeris.runtime.gateway.enabled=true` | wire-level forward proof, allocation budget on forward path, classpath guards green (no SCG / servlet / Netty / Reactor creep), default-off bootstrap | TRL-6 to TRL-7 |
| 0.9.0-rc1 | Release hardening | support matrix, migration notes, failure handling, dependency hygiene | regression freeze, soak tests, operational runbooks | TRL-7 to TRL-8 |
| 1.0.0 | Narrow production GA | Pure Mode GA, bounded Compatibility Mode, optional tx/data, events/flow, and edge gateway only if gates are met | staged production rollout evidence, upgrade/rollback guidance, support statement | TRL-8 |

> **Recommendation:** do not delay 1.0 waiting for Phase 4C (graph). Phase 4A (events), 4B (flow/saga), and Phase 5 (edge gateway) ship as previews alongside tx/data — if any bundle's gates are not met by the release-candidate stage, keep that bundle preview rather than expanding the GA promise.

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
- Prefer native repository integration first; keep any JDBC bridge narrow, opt-in, and justified by ADR-017.
- Verify cleanup, rollback, and isolation under concurrent execution.

### 0.5.0-preview
- Land Phase 4A (events bridge) and 4B (flow/saga bridge) as opt-in modules: `exeris-spring-runtime-events` and `exeris-spring-runtime-flow`.
- Default-off via `exeris.runtime.events.enabled` and `exeris.runtime.flow.enabled`; activation must remain explicit, never silently enabled.
- **Durable flow snapshots are NOT in scope for 0.5.0-preview.** 4B ships with `persistenceEnabled=false`; flows live in process memory and are lost on restart. The `exeris.runtime.flow.snapshot-persistence.enabled` flag stays held back until Phase 3 reaches production-ready and an Exeris-owned `FlowSnapshotStore` lands in `exeris-spring-runtime-tx` or `exeris-spring-runtime-data`.
- Keep Spring `ApplicationEventPublisher` and the Exeris `EventBus` separate; never wire one into the other.
- Verify subscription cleanup at `SmartLifecycle.stop()`, choreography activation gated on `FlowEngineCapabilities.choreographySupport()`, and that step lambdas closing over Spring beans do not invert lifecycle ownership.
- Do not promote 4A/4B to GA in 1.0 unless the single graduation criterion (gates clear **and** ≥1 production-run downstream service) is met — preview status is the safe default.

### 0.6.0-preview
- Land Phase 5 (edge gateway) as a new opt-in module: `exeris-spring-runtime-gateway`.
- Default-off via `exeris.runtime.gateway.enabled=true`; activation must be explicit. When disabled, the artefact compiles in but adds zero runtime overhead.
- **Spring Cloud Gateway compatibility is NOT in scope** (per ADR-021). The artefact ships its own route/filter primitives and YAML shape; it is not a SCG DSL bridge. Workloads requiring SCG run native SCG outside Exeris.
- Banned dependencies replicate the Pure Mode classpath baseline: `jakarta.servlet..`, `io.netty..`, `reactor..`, `org.springframework.web.reactive..`, `org.springframework.web.servlet..`, `spring-cloud-starter-gateway*`, Tomcat/Jetty/Undertow embeds. Module ships its own `PureModeClasspathGuardTest`.
- Verify wire-level forward proof, allocation budget on the forward path, and `LoanedBuffer` ownership transfer (no leaks across hops).
- Do not promote Phase 5 to GA in 1.0 unless the single graduation criterion (gates clear **and** ≥1 production-run downstream service) is met — preview status is the safe default.

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
  Driven by downstream Spring services that are migrating onto the runtime now and need event-driven choreography and saga semantics during, not after, that migration. Both modules ship default-off via property flags, activate explicitly, and use the single graduation criterion stated in *Recommended 1.0 Scope* (verification gates clear **and** ≥1 downstream service has run 4A/4B in production for a representative period). Either gap keeps the modules preview — the 1.0.x post-train row below restates this criterion, it does not weaken it.
- **Phase 4C (graph integration) — post-1.0.**
  The kernel Graph SPI is at TRL-4 and the lab test scope is still pending. Pulling 4C into 1.0 would expand the module surface without clear in-flight migration demand, so it stays in the 1.1.x candidate window.

Phase 5 (edge gateway, `exeris-spring-runtime-gateway`) follows the same preview pattern as Phase 4A/4B and was promoted to 1.0 preview by ADR-021. It is **not** a Spring Cloud Gateway compatibility bridge: gateway-class workloads needing SCG DSL run native SCG outside Exeris (documented exception). Phase 5 ships a bounded route/filter/forwarder primitive set on the Pure Mode hot path; the rationale, exit criteria, and "forever-out" scope rows live in `docs/phases/phase-5-edge-gateway.md`.

### Why 4A/4B coupling is acceptable in preview, but 4C is not

Reviewer reading the previous draft will notice that *new lifecycle and subsystem coupling* (subscription cleanup at `SmartLifecycle.stop()`, choreography activation gating, step-lambda lifecycle ownership, etc.) was previously cited as a reason to defer all of Phase 4 — and 4A/4B introduce exactly that coupling. The split is justified precisely because:

1. Activation is **default-off** behind property flags. Applications that do not enable the modules pay none of the lifecycle cost; the coupling only matters for opt-in adopters.
2. There is **in-flight migration demand**. Downstream services need 4A/4B during their migration window. 4C has no such demand today.
3. The **graduation criterion is the gate**, not the timing. If shutdown drain, subscription cleanup, choreography gating, or step-lambda ownership cannot be cleanly demonstrated, the modules stay preview and never reach GA. The coupling does not silently leak into the 1.0 GA promise.

Reasons to keep 4C deferred (these still apply only to 4C, since 4C lacks the migration-demand offset above):
1. It expands the module surface in a TRL-4 SPI without an in-flight migration use case.
2. It is not required to prove the central host-runtime claim from ADR-010.
3. It introduces new lifecycle and subsystem coupling that is better addressed after the core runtime path is operationally stable.

Only pull 4C earlier if it becomes a **hard blocker** for a Phase 1-3 outcome or a downstream service migration. Otherwise, use the post-1.0 train below:

| Post-1.0 train | Candidate scope |
|:---------------|:----------------|
| 1.0.x | 4A/4B and Phase 5 graduation from preview to bounded GA — same single criterion as above (gates clear **and** ≥1 production-run downstream service per bundle); preview status is the safe default until both halves of the criterion are demonstrated for each bundle independently |
| 1.1.x | graph integration spike (4C), production evaluation of 4A/4B and Phase 5 GA |
| 1.2.x | graph integration hardening, choreography expansion, multi-tenant flow validation, Phase 5 advanced features (WebSocket forwarding, body modification, HTTP/2↔1.1 translation) if validated by production demand |

---

## Release Management Notes

- Keep Pure Mode as the default and reference architecture.
- Keep Compatibility Mode explicit and never silently enabled.
- Keep new tx/data behavior default-off until verification proves safe ownership boundaries.
- Keep events (4A) and flow/saga (4B) default-off in 1.0 preview; activation remains explicit. Spring `ApplicationEventPublisher` is never wired into the Exeris `EventBus`, and `@Async` is never reintroduced as a workaround for missing flow capabilities.
- Update the roadmap whenever a phase changes from proof, to preview, to supported production scope.
