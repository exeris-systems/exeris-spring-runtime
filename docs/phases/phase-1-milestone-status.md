# Phase 1 Milestone Status — Pure Mode Ingress (M1)

- Date: 2026-05-09 (updated; original 2026-03-15)
- Scope owner: `exeris-spring-boot-autoconfigure` + `exeris-spring-runtime-web`
- Reporting intent: practical coordination status for M1 closure, without host-runtime overclaims.
- See: [`phase-1-web-ingress.md`](phase-1-web-ingress.md) for the sub-phase split (1a/1b/1c) and [`phase-1b-kernel-seam-closure.md`](phase-1b-kernel-seam-closure.md) for the seam delivery record.

## Objective and Scope (M1)

Prove the Pure Mode ingress path in this repository:

1. Spring boots the application context.
2. Exeris runtime binds ingress and owns request execution.
3. Request enters through Exeris, dispatches to a Spring-managed Pure Mode handler bean, and response exits through Exeris.
4. Telemetry and graceful shutdown are wired for operational use.
5. No servlet container/reactive runtime ownership (Tomcat/Netty/Servlet API) is required in the Pure Mode path.

Status language in this report is strict:
- `DONE` = implemented + test evidence for this scope item.
- `PARTIAL` = implementation exists but full runtime E2E proof is missing.
- `NOT STARTED` = no meaningful implementation/evidence yet.

## Coverage Matrix (M1 Exact Scope)

| Scope item | Status | Evidence (repo paths) | Notes |
|---|---|---|---|
| Spring starts app | DONE | `ExerisRuntimeAutoConfiguration.java` ; `ExerisBootstrapIntegrationTest` (13/13) | Context boot + beans verified in tests. |
| Exeris binds port | DONE | `ExerisRuntimeLifecycle.java` ; `ExerisWireLevelRuntimeIntegrationTest#pureMode_bindsPort_routesRequest_and_cleansUpAfterFixtureAndContextClose` | Real socket bind verified end-to-end via the kernel testkit fixture. |
| request enters via Exeris | DONE | `ExerisHttpDispatcher.java` ; `ExerisWireLevelRuntimeIntegrationTest` (6/6) | Wire-level HTTP client round-trip through Exeris ingress proven (bind, body, 404, status, drain, telemetry scope). |
| endpoint is Spring bean | DONE | `ExerisWebAutoConfiguration.java` ; `ExerisWebAutoConfigurationTest` (6/6) | Route registry discovers Spring beans implementing `ExerisRequestHandler` and annotated with `@ExerisRoute`; tested. |
| response exits via Exeris | DONE | `ExerisServerResponse.java` ; `ExerisWireLevelRuntimeIntegrationTest#pureMode_bodyResponse_returnsCorrectPayloadAndHeaders` ; `#pureMode_customStatus_bodyResponse_returns201WithPayload` | Body, headers, and custom status proven on the wire. |
| telemetry + graceful shutdown | DONE | `ExerisRuntimeLifecycle.java` ; `ExerisWireLevelRuntimeIntegrationTest#pureMode_shutdownDrainsInFlightRequest_beforeIngressBecomesUnavailable` ; `#pureMode_wireRequest_providesTelemetryScopeEvidence` | In-flight drain at shutdown and telemetry scope binding both verified at wire level. |
| no Tomcat/Netty/Servlet API | DONE | `WallIntegrityTest` (autoconfigure) ; `PureModeClasspathGuardTest` (autoconfigure + web + tx + data + actuator) | All five Pure Mode modules ship their own `PureModeClasspathGuardTest`; each runs four ArchUnit rules against servlet, Netty/Reactor, WebFlux server abstractions, and `DispatcherServlet`. 4/4 green per module. |

## M1 Closure

All three sub-phases are complete:

- **1a** dispatcher path — dispatcher, routing, request/response model, error mapping, autoconfiguration.
- **1b** wire-level E2E ingress proof — `ExerisWireLevelRuntimeIntegrationTest` 6/6, kernel testkit fixture consumed.
- **1c** closure hardening — `PureModeClasspathGuardTest` replicated to `tx`, `data`, and `actuator` (4 rules × 5 modules, all green); `ExerisDispatcherAllocationBaselineTest` enforces a hard ≤ 1024 B/req mean budget for empty-body GET dispatch (observed ≈ 277 B/dispatch, ~27% of budget); [`phase-1-invariants.md`](phase-1-invariants.md) documents the ten web-specific invariants.

## Exit Gate Checklist for M1 Complete

- [x] Wire-level end-to-end runtime test demonstrates Exeris-owned HTTP ingress path with real bind + HTTP client round-trip (no servlet/reactive runtime ownership). — `ExerisWireLevelRuntimeIntegrationTest` 6/6
- [x] End-to-end test demonstrates Spring-managed `@ExerisRoute` bean invocation through `ExerisHttpDispatcher`. — same test class
- [x] End-to-end test demonstrates response emission through Exeris `HttpExchange.respond(...)` path. — `pureMode_bodyResponse_*` and `pureMode_customStatus_*`
- [x] Graceful shutdown behavior is verified with in-flight request drain in wire-level runtime tests. — `pureMode_shutdownDrainsInFlightRequest_beforeIngressBecomesUnavailable`
- [x] Telemetry integration is verified in at least one runtime request-path test. — `pureMode_wireRequest_providesTelemetryScopeEvidence`
- [x] Allocation baseline test enforces a hard per-dispatch budget on the Pure Mode hot path. — `ExerisDispatcherAllocationBaselineTest` asserts mean ≤ 1024 B/req for empty-body GET dispatch (observed ≈ 277 B/dispatch over 30k iterations after 30k-iter warmup)
- [x] Architecture guard coverage enforces no Tomcat/Netty/Servlet/WebFlux dependency creep for **all** Pure Mode modules. — `PureModeClasspathGuardTest` ships in `autoconfigure`, `web`, `tx`, `data`, `actuator` (4 rules × 5 modules, all green)
- [x] Phase documentation reflects current milestone truth. — reconciled in this commit; sub-phase split lives in `phase-1-web-ingress.md`

Current closure verdict: **M1 closed (2026-05-09).** All exit-gate items are met. Phase 1 invariants are captured in [`phase-1-invariants.md`](phase-1-invariants.md).
