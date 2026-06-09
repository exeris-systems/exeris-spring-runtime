# Roadmap to 1.0 and TRL-9

**Status basis:** repository state as of 2026-05-17 (Phase 4B closure 2026-05-11 via PR #27; ADR-021 amendment 2026-05-13; ADR-027/028/029/030 acceptance, 0.6.0/0.7.0/0.8.0/0.9.0-preview resequence, Phase 3B-α closed via PR #32, and Phase 4C closed via PRs #34/35/36/37/38 — all 2026-05-17)  
**Intent:** delivery planning and scope control, not a claim of production readiness.

---

## Current Repo Status Summary

| Area | Current state | Honest interpretation |
|:-----|:--------------|:----------------------|
| Phase 0 | Closed (2026-05-09) | Bootstrap, lifecycle, and Wall verification are in place; invariants captured in `docs/phases/phase-0-invariants.md`. |
| Phase 1 | Closed (2026-05-09) | Pure Mode ingress is proven end-to-end on the wire (`ExerisWireLevelRuntimeIntegrationTest` 6/6: bind, body, 404, status, drain, telemetry scope). `PureModeClasspathGuardTest` ships uniformly in autoconfigure/web/tx/data/actuator. `ExerisDispatcherAllocationBaselineTest` enforces ≤ 1024 B/req mean for empty-body GET dispatch (observed ≈ 277 B/dispatch). Invariants captured in `docs/phases/phase-1-invariants.md`. |
| Phase 2 | Closed (2026-05-09) | Compatibility Mode bridge for `@RestController` / `@RequestMapping` is delivered (`ExerisCompatMvcIntegrationTest` 15/15) without `DispatcherServlet` or servlet API. `@CompatibilityMode` marker on top-level entry classes (per ADR-011); allocation cost reported by `ExerisCompatAllocationCostReportTest` (Pure ≈ 176 B/dispatch vs Compat ≈ 5095 B/dispatch, ≈ 29× overhead). Gateway-class workloads explicitly out of Compat scope per ADR-021 (ACCEPTED). Invariants captured in `docs/phases/phase-2-invariants.md`. |
| Phase 3 | Closed (2026-05-09; 3B split, see below) | Transaction bridge (3A) delivered: `ExerisPlatformTransactionManager` over `PersistenceConnection`, propagation matrix (`REQUIRED`/`REQUIRES_NEW`/`MANDATORY`/`SUPPORTS`/`NEVER` supported; `NESTED`/`NOT_SUPPORTED` documented unsupported), `tx` 22/22 green, opt-in via `exeris.runtime.tx.enabled`. JDBC compatibility bridge (3C Level 2) delivered per ADR-017: `ExerisDataSource` + `ExerisConnectionProxy`, `data` 34/34 green, `DataModuleBoundaryTest` enforces ADR-017 §7 Rule 1, opt-in via `exeris.runtime.data.compat-datasource.enabled`. Level 1 native repositories remain app-side code per plan. 3B (request scope, tracing) was previously deferred to 3.x — graduated to 1.0 preview on 2026-05-17 per downstream observability demand, split into kernel-independent 3B-α (request scope + structured concurrency, ADR-029) and kernel-gated 3B-β/γ (W3C `traceparent` + OTel bridge, ADR-031). Invariants captured in `docs/phases/phase-3-invariants.md`. |
| Phase 3B-α (request scope + structured concurrency) | Promoted to 1.0 preview (0.6.0-preview, per ADR-029) | Kernel-independent: pure JDK 26 preview features (`ScopedValue`, `StructuredTaskScope`). Delivers `ExerisRequestScope`, tenant/correlation ID propagation across kernel calls, structured concurrency helpers for fan-out patterns. Default-off via `exeris.runtime.context.scope.enabled`. |
| Phase 3B-β (W3C `traceparent` context propagation) | Kernel-gated for 1.0 preview (0.9.0-preview target, per ADR-031 — reserved row in `exeris-docs/adr-index.md`; content lands in the follow-up PR for the 0.9.0-preview train) | Waits on the kernel `TraceContext`/`ScopedValue` slot, which the kernel places in the consolidated 1.0 GA roadmap Sprint 0.12 (~v0.12) — **not** `exeris-kernel` 0.8.0 (0.8.0 shipped 2026-06-03 with no tracing; see kernel ADR-032 §traceparent). Bridges kernel `TraceContext` to Spring beans via `ScopedValue<TraceContext>`; HTTP client egress propagates `traceparent` header. |
| Phase 3B-γ (OTel span/metric emission) | Kernel-gated, may slip to 1.0.x (per ADR-031) | Waits on kernel `PrometheusOtlpTelemetrySink` (currently in `exeris-kernel` v0.8/v0.9 telemetry gap section — not committed to any v0.8 sprint deliverable). If kernel slips to v0.9, this Spring-side bridge slips to 1.0.x. |
| Phase 4A (events) | Closed (2026-05-09; preview, `0.5.0-preview` train) | Implementation landed in PR #11; closure docs in Phase 4A closure PR. `exeris-spring-runtime-events` ships default-off via `exeris.runtime.events.enabled=true` (`matchIfMissing=false`). Spring `ApplicationEventPublisher` and Exeris `EventBus` stay separate (no ownership inversion); `EventModuleBoundaryTest` 5/5 enforces (no `ApplicationEventPublisher`, no `spring-context.event`, no HTTP/servlet, no tx/persistence, no JPA). `ExerisEventListenerRegistrar` is `SmartLifecycle` — subscriptions cleaned up at `stop()`. 30/30 tests green (closure PR added `exeris.runtime.network.port=0` to the runtime integration test, eliminating the prior port-bind flake). Invariants captured in `docs/phases/phase-4a-events-invariants.md`. **Graduation to 1.0.x bounded GA** requires the invariants to stay green AND ≥1 downstream service running in production for a representative period. |
| Phase 4B (flow/saga) | Closed (2026-05-11; preview, `0.5.0-preview` train) | Implementation landed across PRs #17 (Step 1 module skeleton + FlowEngine seam), #18 (Step 2 declarative + imperative invocation surface), #23 (Step 3 event-driven choreography bridge), and #27 (Step 4 closure — kernel 0.8.0 + durable saga state via `JdbcFlowSnapshotStore`). Ships default-off via `exeris.runtime.flow.enabled` (`matchIfMissing=false`); `persistence-enabled` separately gated. Choreography activation gated on `FlowEngineCapabilities.choreographySupport()`. `FlowModuleBoundaryTest` enforces no `@Async` workaround, no HTTP/servlet, no `spring-context.event` bridge. **Graduation to 1.0.x bounded GA** requires the invariants to stay green AND ≥1 downstream service running 4B in production for a representative period. |
| Phase 4C (graph) | Closed (2026-05-17; preview, `0.7.0-preview` train) | Implementation landed across PRs #34 (Step 1 module skeleton + structural docs), #35 (Step 2 autoconfig + properties + `ExerisRuntimeLifecycle.getGraphEngine()` capture), #36 (Step 3 `ExerisGraphTemplate` + `@ExerisGraphQuery` + `BeanPostProcessor`), #37 (Step 4 architecture guards), #38 (Step 5 runtime integration test). Ships default-off via `exeris.runtime.graph.enabled` (`matchIfMissing=false`); `require-engine` separately gated. `GraphModuleBoundaryTest` (9 rules) bans Spring Data / spring-web / kernel-community production-scope / servlet / tx+JDBC / JPA / async+ApplicationEventPublisher / cross-runtime-module edges. Invariants captured in [`phase-4c-graph-invariants.md`](phases/phase-4c-graph-invariants.md). **Graduation to 1.0.x bounded GA is kernel-gated** — requires the Spring-side invariants green AND kernel `GraphChurnRatioTck` Community binding green in `exeris-kernel` CI (kernel v0.8 Sprint 7) AND ≥1 downstream service running 4C in production for a representative period. |
| Spring Boot 4 nominal compatibility | Planned for 0.8.0-preview (per ADR-028) | Dual-matrix CI claim that the 1.0 baseline supports **both** Spring Boot 3.5+ and Spring Boot 4.x. Not a feature toggle; a cross-cutting matrix invariant. |
| Phase 5 (edge gateway) | Deprioritized to 0.9.0-preview (1.0 preview retained) | Triggered by ADR-021 and amended 2026-05-13 to clarify that the platform-side architectural home for gateway workloads is the Tier 3 Gateway-family Platform SKUs (kernel-direct), with `exeris-spring-runtime-gateway` scoped to Spring brownfield customers. Deprioritized in the 2026-05-17 resequence because the singular downstream gateway service chose Compatibility Mode SCG MVC explicitly (per its own architectural review), removing the in-flight migration demand that motivated higher placement. Still ships default-off; graduation criterion unchanged. |

### Recommended 1.0 Scope

The production 1.0 target should stay deliberately narrow:

- **Primary GA story:** Exeris-owned Pure Mode runtime path for Spring applications.
- **Secondary story:** explicitly bounded Compatibility Mode support for selected web semantics.
- **Conditional story:** tx/data bridges may ship only if their verification gates pass without ownership inversion; otherwise they remain preview/default-off.
- **Conditional story:** Phase 4A (events) and 4B (flow/saga) bridges ship as preview default-off so downstream services can adopt event-driven choreography and saga semantics during their runtime migration. They are not GA promises in 1.0. **Graduation criterion (single, applies in both 1.0 and 1.0.x):** verification gates clear (subscription cleanup at `SmartLifecycle.stop()`, choreography activation gated on `FlowEngineCapabilities.choreographySupport()`, no Spring `ApplicationEventPublisher` ↔ Exeris `EventBus` inversion, no `@Async` workaround) **and** at least one downstream service has run 4A/4B in production for a representative period. Either gap keeps the modules preview.
- **Conditional story:** Phase 5 (edge gateway, `exeris-spring-runtime-gateway`) ships as preview default-off so downstream services that need Exeris-owned edge ingress can adopt it without taking a hard 1.0 GA dependency. It is not an SCG compatibility bridge (per ADR-021); workloads requiring Spring Cloud Gateway DSL run native SCG outside Exeris. **Graduation criterion (1.0.x):** wire-level forwarder proof, filter/retry/circuit-breaker/rate-limit verified under controlled upstream failures, allocation budget enforced on the forward path, classpath guards green (no servlet/Netty/Reactor/SCG creep), **and** at least one downstream service runs Phase 5 in production for a representative period. Deprioritized to 0.9.0-preview in the 2026-05-17 resequence — the singular downstream gateway service chose Compatibility Mode SCG MVC explicitly, removing the in-flight migration demand.
- **Conditional story:** Phase 3B is split between kernel-independent and kernel-gated halves. **3B-α (request scope + structured concurrency)** lands at 0.6.0-preview as a kernel-independent module (`ScopedValue<RequestScope>`, `StructuredTaskScope` helpers, tenant/correlation ID propagation; ADR-029); default-off. **3B-β (W3C `traceparent` context propagation)** targets the 0.9.0-preview Spring-side train, gated on the kernel `TraceContext` carrier via `ScopedValue` — which the kernel defers to the consolidated 1.0 GA roadmap Sprint 0.12 (~v0.12), **not** `exeris-kernel` 0.8.0 (ADR-031). **3B-γ (OTel span/metric emission)** is gated on kernel `PrometheusOtlpTelemetrySink` — currently in the v0.8/v0.9 telemetry gap section without sprint commitment; if kernel slips to v0.9, 3B-γ slips to 1.0.x without affecting the 3B-α / 3B-β GA story.
- **Conditional story:** Phase 4C (graph) Spring-side seam (`exeris-spring-runtime-graph` autoconfig + lifecycle + `GraphSession` facade, per ADR-030) ships at 0.7.0-preview. Kernel Graph SPI is at **TRL-3** (corrected from the prior "TRL-4" claim by reading `exeris-kernel/docs/subsystems/graph.md:11`); Community PGQ/Bolt drivers exist for real product scenarios but the `GraphChurnRatioTck` Community binding is not yet gated in kernel CI. Spring-side seam is kernel-independent — it exposes whatever the kernel supports without depending on kernel TCK hardening. **GA graduation criterion (1.0.x):** Spring-side guards green **AND** kernel Graph baseline TCK green in CI **AND** ≥1 downstream service running 4C in production for a representative period.

---

## Recommended Release Train

| Version | Feature focus | Finetuning focus | Quality gate | Target TRL |
|:--------|:--------------|:-----------------|:-------------|:-----------|
| 0.1.0-SNAPSHOT | Architecture baseline, bootstrap proof, Pure Mode ingress proof | Docs, ADR alignment, module boundaries | Guard tests and integration proofs remain green | TRL-4 to TRL-5 |
| 0.2.0-alpha | Pure Mode hardening | error mapping polish, telemetry/actuator fit, graceful shutdown, route stability | repeatable wire-level runtime tests; no servlet/netty creep | TRL-5 |
| 0.3.0-beta | Compatibility Mode subset | controller mapping coverage, JSON handling, explicit unsupported-feature matrix | documented compatibility cost; pure-mode non-regression | TRL-6 |
| 0.4.0-preview | Transaction and persistence preview | REQUIRED propagation, request/context isolation, native repository path, ADR-017-bounded JDBC bridge | concurrent leak tests, rollback cleanup, default-off safety | TRL-6 to TRL-7 |
| 0.5.0-preview | Events + Flow/Saga preview (Phase 4A + 4B) | `ExerisEventPublisher` / `@ExerisEventListener`, `ExerisFlowDefinition` / `ExerisFlowTemplate`, opt-in choreography via `ExerisFlowChoreographyBridge` | event subscription cleanup at shutdown, flow park/wake without thread pinning, no ownership inversion of Spring `ApplicationEventPublisher`, default-off properties | TRL-6 to TRL-7 |
| 0.6.0-preview | Phase 3B-α — Request Scope + Structured Concurrency (per ADR-029) | `ExerisRequestScope` (`ScopedValue`-backed), `StructuredTaskScope` helpers for kernel-call fan-out, tenant/correlation ID propagation; opt-in via `exeris.runtime.context.scope.enabled` | scope leak tests under concurrent execution, tenant isolation across `StructuredTaskScope` forks, no `ThreadLocal` on hot paths, default-off bootstrap | TRL-6 to TRL-7 |
| 0.7.0-preview | Phase 4C Spring-side seam — Graph integration (per ADR-030) | `exeris-spring-runtime-graph` autoconfig + lifecycle (`KernelProviders.GRAPH_ENGINE` capture) + `GraphSession` facade + `@ExerisGraphQuery` MATCH parameterization; opt-in via `exeris.runtime.graph.enabled=true` | `GraphModuleBoundaryTest` green (no servlet/Netty/Reactor), `PureModeClasspathGuardTest` green, autoconfig stand-down test when kernel `GraphEngine` absent, default-off bootstrap | TRL-6 to TRL-7 |
| 0.8.0-preview | Spring Boot 4 nominal compatibility (per ADR-028) | Dual-matrix build (Spring Boot 3.5+ and 4.x); explicit "supported on both" claim becomes the 1.0 baseline; minor compat shims if SB4 reorganizations affect actuator/observability bridges | matrix CI green on both Spring Boot lines (full reactor + integration), no Pure Mode classpath drift on either matrix, no servlet/Netty/Reactor regression on SB4 line | TRL-7 |
| 0.9.0-preview | Edge Gateway preview (Phase 5) + Phase 3B-β (W3C `traceparent`, per ADR-031 first half) | **Phase 5:** `exeris-spring-runtime-gateway` artefact (`ExerisGatewayRoute` / `ExerisGatewayDispatcher` / `ExerisHttpForwarder` / filter set + Resilience4j core), opt-in via `exeris.runtime.gateway.enabled=true`. **3B-β:** W3C `traceparent` ingress + `ScopedValue<TraceContext>` bridge + HTTP client egress propagation, gated on the kernel `TraceContext` carrier — placed in the consolidated 1.0 GA roadmap Sprint 0.12 (~v0.12), **not** `exeris-kernel` 0.8.0; 3B-β slips if the kernel slot is not yet shipped at the 0.9.0-preview cut. | Phase 5: wire-level forward proof, allocation budget on forward path, classpath guards green. 3B-β: traceparent round-trip test, kernel `ScopedValue` interop test, OTel-context-compatible attributes. | TRL-6 to TRL-7 |
| 0.9.5-rc1 | Release hardening | support matrix, migration notes, failure handling, dependency hygiene | regression freeze, soak tests, operational runbooks; SB4 matrix in CI is part of the freeze; Phase 3B-γ (OTel sink) status reviewed for inclusion vs. 1.0.x slip | TRL-7 to TRL-8 |
| 1.0.0 | Narrow production GA | Pure Mode GA on Spring Boot 3.5+ **and** Spring Boot 4.x, bounded Compatibility Mode, optional tx/data, events/flow, **3B-α** GA, **3B-β + 4C Spring-side seam + edge gateway + 3B-γ (if kernel ready)** as preview only if gates are met | staged production rollout evidence, upgrade/rollback guidance, support statement (both Spring Boot lines) | TRL-8 |

> **Recommendation:** the 1.0 train sequences kernel-independent work first (Phase 3B-α at 0.6.0; Phase 4C Spring-side seam at 0.7.0 — the seam itself is kernel-independent even though GA depends on kernel Graph TCK hardening), then SB4 dual-matrix (0.8.0 — cross-cutting), then kernel-gated work (Phase 3B-β at 0.9.0 once the kernel ships the W3C `traceparent`/`TraceContext` slot — placed in the consolidated 1.0 GA roadmap Sprint 0.12 (~v0.12), not 0.8.0). Phase 5 (edge gateway) is co-located at 0.9.0-preview after the 2026-05-17 resequence that deprioritized it on absence of downstream migration demand. Phase 3B-γ (OTel sink) is kernel-gated on `PrometheusOtlpTelemetrySink`, which is currently in the kernel v0.8/v0.9 telemetry gap section without sprint commitment — if kernel slips to v0.9, 3B-γ slips to 1.0.x. If any preview bundle's gates are not met by the release-candidate stage, keep that bundle preview rather than expanding the GA promise.

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
- **Durable flow snapshots are NOT in scope for 0.5.0-preview.** 4B ships with `persistenceEnabled=false`; flows live in process memory and are lost on restart. Kernel 0.7.0 added the Community `JdbcFlowSnapshotStore` (with `exeris_saga_state` DDL), so the kernel-side prerequisite is satisfied. The Spring-side bridge that binds it through `KernelProviders.FLOW_SNAPSHOT_STORE` is sequenced for Phase 4B Step 4 closure and depends on the Pure Mode persistence autoconfiguration ordering being settled. Activation flag is `exeris.runtime.flow.persistence-enabled` (kebab-cased; record field `persistenceEnabled`).
- Keep Spring `ApplicationEventPublisher` and the Exeris `EventBus` separate; never wire one into the other.
- Verify subscription cleanup at `SmartLifecycle.stop()`, choreography activation gated on `FlowEngineCapabilities.choreographySupport()`, and that step lambdas closing over Spring beans do not invert lifecycle ownership.
- Do not promote 4A/4B to GA in 1.0 unless the single graduation criterion (gates clear **and** ≥1 production-run downstream service) is met — preview status is the safe default.

### 0.6.0-preview
- Land Phase 3B-α (request scope + structured concurrency helpers) as kernel-independent work per ADR-029. Module: extends `exeris-spring-runtime-web` and `exeris-spring-boot-autoconfigure` with `ExerisRequestScope` API; no new top-level module required.
- Default-off via `exeris.runtime.context.scope.enabled` — applications opt in. Activation introduces `ScopedValue<RequestScope>` bindings around `HttpHandler.handle` invocation; tenant/correlation IDs propagate through `StructuredTaskScope` forks without `ThreadLocal` copying.
- Verify scope leak tests under concurrent execution; tenant isolation across `StructuredTaskScope` forks; no `ThreadLocal` on hot paths (existing `WallIntegrityTest` ban extends here).
- Phase 3B-α is **not** the OTel bridge — it is the substrate the OTel bridge will later attach to. Tracing-attribute API stays minimal; the full propagation/emission story lands in 3B-β (0.9.0-preview, kernel-gated) and 3B-γ (kernel-gated, may slip to 1.0.x).
- Do not promote 3B-α to GA in 1.0 unless the single graduation criterion (gates clear **and** ≥1 production-run downstream service) is met — preview status is the safe default.

### 0.7.0-preview
- Land Phase 4C Spring-side seam as a new opt-in module: `exeris-spring-runtime-graph` (per ADR-030).
- Default-off via `exeris.runtime.graph.enabled=true`; activation must be explicit. The module captures `KernelProviders.GRAPH_ENGINE` (if present) via `ExerisRuntimeLifecycle`, exposes a `GraphSession` facade for try-with-resources usage from Spring beans, and provides `@ExerisGraphQuery` for parameterized MATCH-DSL strings.
- **Kernel Graph SPI is at TRL-3** ("Validated Architectural Prototype" per `exeris-kernel/docs/subsystems/graph.md:11`); Community drivers (PostgreSQL JDBC PGQ, Neo4j Bolt, Memgraph Bolt) exist for real product scenarios but the `GraphChurnRatioTck` Community binding is not yet gated in kernel CI. The Spring-side seam is **kernel-independent in landing** but **kernel-gated in graduation**: the seam exposes whatever the kernel supports today; GA waits on kernel baseline TCK hardening (kernel Sprint 7 work, likely v0.8.x).
- `GraphModuleBoundaryTest` enforces no servlet/Netty/Reactor edges; `PureModeClasspathGuardTest` ships per module; autoconfig stand-down test verifies graceful degradation when kernel `GraphEngine` is absent.
- **Forever-out of scope:** fluent `GraphQueryBuilder` DSL (not in kernel SPI today — `GraphTraversal` record is the surface); `GraphCursor` unbounded-traversal API (kernel "Planned, not yet implemented").
- Do not promote 4C Spring-side seam to GA in 1.0 unless the single graduation criterion (Spring-side guards green **AND** kernel Graph baseline TCK green in CI **AND** ≥1 production-run downstream service) is met — preview status is the safe default.

### 0.8.0-preview
- Land Spring Boot 4 nominal compatibility per ADR-028 — the 1.0 release supports **both** Spring Boot 3.5+ and Spring Boot 4.x.
- Introduce a dual-matrix Maven profile (default `matrix-sb3` uses the current `spring-boot-dependencies` 3.5.x pin; opt-in `matrix-sb4` profile imports the 4.x BOM). The reactor builds and tests under both profiles; no source-level fork.
- CI matrix runs the full reactor (`mvn -Pmatrix-sb3 clean install` and `mvn -Pmatrix-sb4 clean install`) on every push; matrix failure on either line blocks merge.
- Pure Mode classpath guards (`PureModeClasspathGuardTest`, `WallIntegrityTest`) must stay green on **both** matrices — Spring Boot 4 must not be allowed to re-introduce a servlet/Netty/Reactor edge that the current SB3 matrix forbids.
- SB4 bridges live only where SB4 package reorganizations actually affect a consumed type (anticipated: `spring-boot-actuate-autoconfigure`, possibly `MappingJackson2HttpMessageConverter` if its package or replacement changes). Per the three-tier taxonomy in ADR-028 obligation 4, the bridge package depends on which path it serves: **Pure Mode** SB4 bridges (the common case — Pure-Mode-visible) live in `bridge.sb4.*` sub-packages; **Compatibility Mode** SB4 shims live in `compat.sb4.*` sub-packages (governed by `CompatibilityIsolationGuardTest` per ADR-011); **≤ 1-class divergences** stay inline (sealed-interface SPI or narrow reflection). All carry the `@SbCompat` marker; removed when the SB3 matrix is dropped post-1.0.x.
- The dual-matrix claim is **not** a Spring Cloud Gateway-style "all of Spring works" promise — it scopes only to what `exeris-spring-runtime` consumes from the framework (DI, configuration binding, `spring-context` lifecycle, `spring-web` model types, `spring-tx` `PlatformTransactionManager`, `spring-boot-actuator` health/info/metrics). Things explicitly out of scope: `spring-cloud-starter-gateway*` (per ADR-021), embedded servlet containers (per Phase 0/1 classpath guards), `spring-webflux` reactive stack.

### 0.9.0-preview
- Land Phase 5 (edge gateway) as a new opt-in module: `exeris-spring-runtime-gateway`. Deprioritized to 0.9.0-preview in the 2026-05-17 resequence on absence of in-flight downstream migration demand (the singular downstream gateway service chose Compat Mode SCG MVC explicitly), retained in 1.0 preview because the cap-tier Wall extension still needs the Spring-Runtime-side answer for future brownfield customers without SCG hard dependency.
- Default-off via `exeris.runtime.gateway.enabled=true`; activation must be explicit. When disabled, the artefact compiles in but adds zero runtime overhead.
- **Spring Cloud Gateway compatibility is NOT in scope** (per ADR-021). The artefact ships its own route/filter primitives and YAML shape; it is not a SCG DSL bridge. Workloads requiring SCG run native SCG outside Exeris.
- Banned dependencies replicate the Pure Mode classpath baseline: `jakarta.servlet..`, `io.netty..`, `reactor..`, `org.springframework.web.reactive..`, `org.springframework.web.servlet..`, `spring-cloud-starter-gateway*`, Tomcat/Jetty/Undertow embeds. Module ships its own `PureModeClasspathGuardTest`.
- Verify wire-level forward proof, allocation budget on the forward path, and `LoanedBuffer` ownership transfer (no leaks across hops).
- Co-deliver Phase 3B-β (W3C `traceparent` context propagation, ADR-031 first half) — gated on the kernel `TraceContext` carrier via `ScopedValue`, which the kernel places in the consolidated 1.0 GA roadmap Sprint 0.12 (~v0.12), **not** `exeris-kernel` 0.8.0 (0.8.0 shipped 2026-06-03 with no tracing). Since that kernel slot lands well after the 0.9.0-preview cut, 3B-β realistically slips to 0.9.5-preview or 1.0.x; Phase 5 ships independently of 3B-β.
- Do not promote Phase 5 or 3B-β to GA in 1.0 unless the single graduation criterion (gates clear **and** ≥1 production-run downstream service) is met — preview status is the safe default.

### 0.9.5-rc1
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

- **Phase 4A (events bridge) and Phase 4B (flow/saga bridge) — preview in 1.0** (`0.5.0-preview` train; closed 2026-05-09 / 2026-05-11).
  Driven by downstream Spring services that are migrating onto the runtime now and need event-driven choreography and saga semantics during, not after, that migration. Both modules ship default-off via property flags, activate explicitly, and use the single graduation criterion stated in *Recommended 1.0 Scope*. Either gap keeps the modules preview — the 1.0.x post-train row below restates this criterion, it does not weaken it.
- **Phase 4C (graph integration) Spring-side seam — preview in 1.0** (`0.7.0-preview` train, promoted 2026-05-17 per ADR-030).
  Spring-side seam (`exeris-spring-runtime-graph`) is kernel-independent in landing — it captures `KernelProviders.GRAPH_ENGINE` and exposes `GraphSession` facade. GA graduation is kernel-gated on Community baseline TCK (`GraphChurnRatioTck` Community binding), which is on kernel Sprint 7 (v0.8.x). If kernel slips, Spring-side seam stays preview without affecting other modules' GA story.

Phase 5 (edge gateway, `exeris-spring-runtime-gateway`) follows the same preview pattern as Phase 4A/4B and was promoted to 1.0 preview by ADR-021. Deprioritized to `0.9.0-preview` in the 2026-05-17 resequence on absence of in-flight downstream migration demand. It is **not** a Spring Cloud Gateway compatibility bridge: gateway-class workloads needing SCG DSL run native SCG outside Exeris (documented exception). Phase 5 ships a bounded route/filter/forwarder primitive set on the Pure Mode hot path; the rationale, exit criteria, and "forever-out" scope rows live in `docs/phases/phase-5-edge-gateway.md`.

### Why 4C promotion is acceptable now (vs. the previous "post-1.0" stance)

The previous draft kept 4C post-1.0 on three grounds (TRL-4 kernel SPI, no in-flight migration demand, additional lifecycle coupling). The 2026-05-17 review reversed each:

1. **Kernel TRL re-read.** The "TRL-4" claim was an over-statement; `exeris-kernel/docs/subsystems/graph.md:11` explicitly states **TRL-3** ("Validated Architectural Prototype"). However, the Community drivers (PostgreSQL JDBC PGQ, Neo4j Bolt, Memgraph Bolt) **do** work in real product scenarios — the gap is operational CI/TCK confidence, not driver existence. The Spring-side seam exposes whatever the kernel ships; landing the seam is decoupled from kernel TCK hardening.
2. **In-flight migration demand surfaced.** A downstream service migration review (2026-05-17) identified graph-shaped queries on net-worth-style data with a ~6–8-week horizon. Without the Spring-side seam in 1.0 preview, that service falls back to denormalized columns — workable but not the architectural target.
3. **Lifecycle coupling is not new.** The seam reuses the same `ExerisRuntimeLifecycle` capture pattern that 4A/4B established. The coupling that previously read as "additional" is the same coupling already in `0.5.0-preview` GA-promise; promoting 4C does not change the lifecycle-coupling cost.

The graduation criterion remains kernel-gated: 4C Spring-side seam does not promote from preview to GA until kernel baseline Graph TCK is green in CI **and** ≥1 downstream service runs 4C in production for a representative period. The coupling does not silently leak into the 1.0 GA promise.

### Phase 3B sequencing — kernel-gating drives the split

A 2026-05-17 review of `exeris-kernel/docs/ROADMAP.md` §"Telemetry: OTLP Metrics Export and Distributed Tracing" established that kernel-side telemetry capabilities arrive in two waves. *(Corrected 2026-06-09: the original review mis-attributed the tracing slot to "Kernel 0.8.0 Sprint 0.12 (committed)". Kernel 0.8.0 shipped 2026-06-03 with no tracing — no `TraceContext` carrier, no OTLP sink (verified in `exeris-kernel-spi` `ConfigProvider` javadoc; kernel ADR-032 §traceparent). The slot is a future milestone, not a 0.8.0 deliverable.)*

- **Kernel consolidated 1.0 GA roadmap Sprint 0.12 (~v0.12; planned, not yet shipped):** W3C `traceparent` + `TraceContext` via `ScopedValue`. → Enables Phase 3B-β (Spring-side context propagation bridge).
- **Kernel telemetry gap section (not yet sprint-committed):** `PrometheusOtlpTelemetrySink` + `TraceSpanEvent` JFR records. → Enables Phase 3B-γ (Spring-side OTel span/metric emission).

Splitting Phase 3B accordingly:

- **3B-α** (request scope + structured concurrency) is **kernel-independent** — pure JDK 26 preview features. Lands at 0.6.0-preview without waiting on the kernel. ADR-029.
- **3B-β** (W3C `traceparent` propagation) is **kernel-gated on the consolidated 1.0 GA roadmap Sprint 0.12 (~v0.12) `TraceContext` slot — not 0.8.0**. Targets the 0.9.0-preview Spring-side train but slips until the kernel slot lands (co-delivered with Phase 5 only if ready). ADR-031 first half.
- **3B-γ** (OTel sink) is **kernel-gated, sprint-uncommitted**. May land at 0.9.5-preview or slip to 1.0.x; the decision is reviewed at rc1. ADR-031 second half.

| Post-1.0 train | Candidate scope |
|:---------------|:----------------|
| 1.0.x | 4A/4B/4C-spring-seam, Phase 5, and 3B-β graduation from preview to bounded GA — same single criterion as above (gates clear **and** ≥1 production-run downstream service per bundle); preview status is the safe default. 3B-γ lands here if it slipped from 1.0. |
| 1.1.x | production evaluation of all 1.0.x graduations; kernel Graph SPI graduation from TRL-3 → TRL-5; deeper 4C surface as kernel matures (cursor API, fluent DSL if kernel adds it). |
| 1.2.x | choreography expansion, multi-tenant flow validation, Phase 5 advanced features (WebSocket forwarding, body modification, HTTP/2↔1.1 translation) if validated by production demand. |

---

## Release Management Notes

- Keep Pure Mode as the default and reference architecture.
- Keep Compatibility Mode explicit and never silently enabled.
- Keep new tx/data behavior default-off until verification proves safe ownership boundaries.
- Keep events (4A) and flow/saga (4B) default-off in 1.0 preview; activation remains explicit. Spring `ApplicationEventPublisher` is never wired into the Exeris `EventBus` (per ADR-027), and `@Async` is never reintroduced as a workaround for missing flow capabilities.
- Maintain Spring Boot 4 nominal compatibility from 0.8.0-preview onward (per ADR-028; train slot moved from 0.7.0 to 0.8.0 in the 2026-05-17 resequence) — the 1.0 support statement covers both Spring Boot 3.5+ and Spring Boot 4.x lines. The matrix is a CI invariant, not a release-time switch.
- Phase 3B-α (request scope + structured concurrency, ADR-029) is kernel-independent; lands at 0.6.0-preview. Phase 3B-β/γ (context propagation + OTel bridge, ADR-031) is kernel-gated; β at 0.9.0-preview, γ may slip to 1.0.x.
- Phase 4C Spring-side seam (ADR-030) lands at 0.7.0-preview; GA is kernel-gated on Community Graph baseline TCK.
- Phase 5 (edge gateway) deprioritized to 0.9.0-preview after the 2026-05-17 downstream migration review — co-delivered with Phase 3B-β.
- Update the roadmap whenever a phase changes from proof, to preview, to supported production scope.
