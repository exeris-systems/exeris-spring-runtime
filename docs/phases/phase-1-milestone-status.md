# Phase 1 Milestone Status — Pure Mode Ingress (M1)

- Date: 2026-03-15
- Scope owner: `exeris-spring-boot-autoconfigure` + `exeris-spring-runtime-web`
- Reporting intent: practical coordination status for M1 closure, without host-runtime overclaims.

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
| Spring starts app | DONE | `exeris-spring-boot-autoconfigure/src/main/java/eu/exeris/spring/boot/autoconfigure/ExerisRuntimeAutoConfiguration.java` ; `exeris-spring-boot-autoconfigure/src/test/java/eu/exeris/spring/boot/autoconfigure/ExerisBootstrapIntegrationTest.java` | Context boot + beans verified in tests. |
| Exeris binds port | PARTIAL | `exeris-spring-boot-autoconfigure/src/main/java/eu/exeris/spring/boot/autoconfigure/ExerisRuntimeLifecycle.java` ; `exeris-spring-boot-autoconfigure/src/main/java/eu/exeris/spring/boot/autoconfigure/ExerisSpringConfigProvider.java` | Lifecycle calls `HttpServerEngine.start()` with injected handler, but no runtime socket-bind E2E assertion yet (unverified). |
| request enters via Exeris | PARTIAL | `exeris-spring-boot-autoconfigure/src/main/java/eu/exeris/spring/boot/autoconfigure/ExerisRuntimeLifecycle.java` ; `exeris-spring-runtime-web/src/main/java/eu/exeris/spring/runtime/web/ExerisHttpDispatcher.java` ; `exeris-spring-runtime-web/src/test/java/eu/exeris/spring/runtime/web/ExerisPureModeRequestPathIntegrationTest.java` | Dispatcher-level integration proof exists (runtime dispatcher handles a realistic kernel exchange in Spring context), but wire-level socket E2E ingress proof (real bind + HTTP client round-trip) is still missing. |
| endpoint is Spring bean | DONE | `exeris-spring-runtime-web/src/main/java/eu/exeris/spring/runtime/web/autoconfigure/ExerisWebAutoConfiguration.java` ; `exeris-spring-runtime-web/src/test/java/eu/exeris/spring/runtime/web/autoconfigure/ExerisWebAutoConfigurationTest.java` | Route registry discovers Spring beans implementing `ExerisRequestHandler` and annotated with `@ExerisRoute`; tested. |
| response exits via Exeris | PARTIAL | `exeris-spring-runtime-web/src/main/java/eu/exeris/spring/runtime/web/ExerisHttpDispatcher.java` ; `exeris-spring-runtime-web/src/main/java/eu/exeris/spring/runtime/web/ExerisServerResponse.java` ; `exeris-spring-runtime-web/src/test/java/eu/exeris/spring/runtime/web/ExerisPureModeRequestPathIntegrationTest.java` | Dispatcher-level integration proof exists (`exchange.respond(...)` path is exercised), but wire-level socket E2E response proof (real bind + HTTP client round-trip) is still missing. |
| telemetry + graceful shutdown | PARTIAL | `exeris-spring-boot-autoconfigure/src/main/java/eu/exeris/spring/boot/autoconfigure/ExerisSpringConfigProvider.java` ; `exeris-spring-boot-autoconfigure/src/main/java/eu/exeris/spring/boot/autoconfigure/ExerisRuntimeProperties.java` ; `exeris-spring-boot-autoconfigure/src/main/java/eu/exeris/spring/boot/autoconfigure/ExerisRuntimeLifecycle.java` ; `exeris-spring-runtime-web/src/test/java/eu/exeris/spring/runtime/web/ExerisPureModeRequestPathIntegrationTest.java` ; `exeris-spring-runtime-actuator/pom.xml` | Graceful shutdown callback behavior now has dispatcher-level integration proof (`ExerisRuntimeLifecycle.stop(...)` callback invoked), but in-flight drain behavior and telemetry emission/visibility are not yet proven in wire-level runtime integration tests. |
| no Tomcat/Netty/Servlet API | PARTIAL | `pom.xml` ; `exeris-spring-runtime-web/pom.xml` ; `exeris-spring-boot-autoconfigure/src/test/java/eu/exeris/spring/boot/autoconfigure/ExerisBootstrapIntegrationTest.java` ; `exeris-spring-runtime-web/src/test/java/eu/exeris/spring/runtime/web/PureModeClasspathGuardTest.java` | Dependency policy/exclusions, classpath assertions, and a web-module ArchUnit guard are present; coverage is still partial because enforcement is scoped to selected web classes and not yet expanded across all Pure Mode modules. |

## Risks / Unknowns Blocking M1 Closure

1. Missing wire-level runtime E2E proof for Exeris-owned ingress (real bind → HTTP client request in → handler → response out).
2. Graceful shutdown semantics have lifecycle callback proof, but in-flight drain behavior is not yet verified under real ingress load.
3. Telemetry is configuration-wired, but emission/visibility behavior is not proven in integration tests.
4. Existing phase document baseline still says Phase 1 status is not started (`docs/phases/phase-1-web-ingress.md`), creating planning drift.
5. Dependency hygiene now has a dedicated web-module architecture guard, but drift risk remains for unguarded classes/modules until guard scope is expanded for all Pure Mode modules.

## Ordered Continuation Plan

### P0 (must land to close M1)

1. Add runtime integration test in web module for real HTTP round-trip with Exeris engine:
   - boot Spring context,
   - Exeris binds configured port,
   - HTTP client performs round-trip through real ingress,
   - Spring `@ExerisRoute` bean invoked,
   - response read on client socket.
2. Extend runtime integration to verify graceful shutdown in-flight drain behavior with bounded timeout.
3. Add telemetry integration proof for at least one request-path signal in Pure Mode runtime.

### P1 (high value, near-term hardening)

1. Expand architecture guard coverage beyond current web-module scope so build fails on servlet/netty/webflux classpath leakage across all Pure Mode modules.
2. Reconcile phase docs to remove status drift between planning and implementation reality.

### P2 (follow-up after M1 closure)

1. Add performance smoke checks for request-path allocation/latency baselines.
2. Expand route capabilities (path variables/wildcards) only after Pure Mode ingress gate is stable.

## Exit Gate Checklist for M1 Complete

- [ ] Wire-level end-to-end runtime test demonstrates Exeris-owned HTTP ingress path with real bind + HTTP client round-trip (no servlet/reactive runtime ownership).
- [ ] End-to-end test demonstrates Spring-managed `@ExerisRoute` bean invocation through `ExerisHttpDispatcher`.
- [ ] End-to-end test demonstrates response emission through Exeris `HttpExchange.respond(...)` path.
- [ ] Graceful shutdown behavior is verified with in-flight request drain in wire-level runtime tests.
- [ ] Telemetry integration is verified in at least one runtime request-path test.
- [ ] Architecture guard coverage enforces no Tomcat/Netty/Servlet/WebFlux dependency creep for Pure Mode modules.
- [ ] Phase documentation reflects current milestone truth and no longer reports this scope as "not started".

Current closure verdict: **M1 is in-progress and not yet complete** due to missing runtime E2E verification gates.