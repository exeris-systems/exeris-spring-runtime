# Phase 1: Exeris-Owned Web Ingress (Pure Mode)

**Status:** Complete (closed 2026-05-09)
**Depends on:** Phase 0 (closed 2026-05-09)
**Milestone:** M1
**Mode:** Pure Mode only
**Invariants captured in:** [`phase-1-invariants.md`](phase-1-invariants.md)

---

## Goal

> Request enters through Exeris. Spring handles business logic. Response exits through Exeris.
> No Tomcat, no Netty, no Servlet API, no Spring MVC DispatcherServlet on the Pure Mode path.

This is the proof that Exeris genuinely owns the runtime — not just the application
framework's transport layer. If the wire-level path works cleanly under load and shutdown,
the host-runtime claim is legitimate.

---

## Sub-phase Structure

Phase 1 was delivered in three sub-phases. The original deliverables 1–10 from this
document have been consolidated into the sub-phase tables below.

| Sub-phase | Scope | Status |
|:---|:---|:---|
| **1a** Pure Mode dispatcher path | Dispatcher, routing, request/response model, error mapping, autoconfiguration | ✅ Complete |
| **1b** Wire-level E2E ingress proof | Kernel testkit fixture consumed; real bind + HTTP client round-trip | ✅ Complete (see [`phase-1b-kernel-seam-closure.md`](phase-1b-kernel-seam-closure.md)) |
| **1c** Closure hardening | Allocation baseline, classpath guard expansion, invariants doc | ✅ Complete |

---

## Phase 1a — Pure Mode Dispatcher Path ✅

In-process dispatch from `HttpExchange` through Exeris-owned bridge classes to Spring
handler beans. No servlet container, no Spring MVC dispatch.

| # | Deliverable | Module | Status | Evidence |
|:-:|:------------|:-------|:-------|:---------|
| 1 | `ExerisRouteRegistry` — maps URI patterns to handler beans | `web` | ✅ | `ExerisRouteRegistryTest` 3/3 |
| 2 | `ExerisServerRequest` — view over `HttpRequest` (no body copy) | `web` | ✅ | exercised in `ExerisHttpDispatcherTest`, `ExerisPureModeRequestPathIntegrationTest` |
| 3 | `ExerisServerResponse` / builder — produces `HttpResponse` | `web` | ✅ | `ExerisServerResponseTest` 5/5 |
| 4 | `ExerisHttpDispatcher implements HttpHandler` | `web` | ✅ | `ExerisHttpDispatcherTest` 5/5 |
| 5 | `ExerisWebAutoConfiguration` — wires dispatcher and route registry | `web` | ✅ | `ExerisWebAutoConfigurationTest` 6/6 |
| 6 | JSON body support | `web` | ✅ (with divergence — see below) | covered through `ExerisServerResponse` body APIs and the wire-level test body assertions |
| 7 | `ExerisErrorMapper` — exception → HTTP status + body | `web` | ✅ | exercised across dispatcher tests |
| 8 | `@ExerisRoute` — annotation for handler registration | `web` | ✅ | `ExerisWebAutoConfigurationTest` |

### Divergences from original sketch

- **No standalone `ExerisJsonCodec` class.** The original sketch named a dedicated codec
  type. Pure Mode body serialisation is handled directly through `ExerisServerResponse`
  body APIs (operating on `MemorySegment` / byte payloads); Jackson is wired only in
  Compatibility Mode (`ExerisCompatAutoConfiguration` registers `MappingJackson2HttpMessageConverter`).
  Rationale: Pure Mode does not have a single fixed codec — different handlers can write
  bytes directly. A named `ExerisJsonCodec` would have implied a centralised, reflective
  serialisation pipeline that we explicitly do not want on the pure path.
- **`ExerisHttpHandlerAutoConfiguration` placement.** The autoconfiguration class lives in
  `exeris-spring-runtime-web` (`ExerisWebAutoConfiguration`), not in
  `exeris-spring-boot-autoconfigure`. The boundary rule still holds — `autoconfigure`
  remains the thin bootstrap layer; web-specific wiring belongs in the web module
  (consistent with the "no transport in autoconfigure" invariant).

---

## Phase 1b — Wire-level E2E Ingress Proof ✅

Real socket bind, HTTP client round-trip, response read off the wire — through Exeris-owned
ingress, not a dispatcher-only stand-in.

| # | Deliverable | Module | Status | Evidence |
|:-:|:------------|:-------|:-------|:---------|
| 9 | Runtime integration test: full HTTP round-trip | `web` (test) | ✅ | `ExerisWireLevelRuntimeIntegrationTest` 6/6 |

`ExerisWireLevelRuntimeIntegrationTest` covers:

1. `pureMode_bindsPort_routesRequest_and_cleansUpAfterFixtureAndContextClose` — real bind, route, clean shutdown
2. `pureMode_bodyResponse_returnsCorrectPayloadAndHeaders` — body + headers off the wire
3. `pureMode_missingRoute_returns404_wireLevel` — 404 mapping through error mapper
4. `pureMode_customStatus_bodyResponse_returns201WithPayload` — custom status code path
5. `pureMode_shutdownDrainsInFlightRequest_beforeIngressBecomesUnavailable` — graceful drain
6. `pureMode_wireRequest_providesTelemetryScopeEvidence` — telemetry scope binding observable

The kernel seam used is `eu.exeris:exeris-kernel-community-testkit`'s
`EmbeddedHttpEngineFixtures.kernelBootstrapFixture()` — see
[`phase-1b-kernel-seam-closure.md`](phase-1b-kernel-seam-closure.md) for the seam delivery
record and the `MEMORY_ALLOCATOR` `ScopedValue` finding from this work.

---

## Phase 1c — Closure Hardening ✅

Items required to formally close M1 with full guard coverage and a documented baseline.

| # | Item | Module | Status | Plan |
|:-:|:------|:-------|:-------|:-----|
| 10 | Allocation baseline test with hard budget | `web` (test) | ✅ | `ExerisDispatcherRepeatedDispatchSmokeTest` stays budget-free (correctness under stress); `ExerisDispatcherAllocationBaselineTest` enforces ≤ 1024 B/req mean for empty-body GET dispatch via `com.sun.management.ThreadMXBean.getThreadAllocatedBytes(...)`, with 30k-iteration warmup and 30k-iteration measurement. Current observed baseline: ~277 B/dispatch (≈ 27% of budget). Latency is measured and logged for visibility but not asserted (CI hardware noise). |
| 11 | `PureModeClasspathGuardTest` extended to all Pure Mode modules | `tx`, `data`, `actuator` | ✅ | Guards present and green in `autoconfigure`, `web`, `tx`, `data`, `actuator` — 4 ArchUnit rules × 5 modules (servlet API, Netty/Reactor, WebFlux server abstractions, `DispatcherServlet`). |
| 12 | Phase docs reconciliation | docs | ✅ (rolling) | `phase-1-web-ingress.md`, `phase-1-milestone-status.md` kept in sync as 1c items land. |
| 13 | Phase 1 invariants document | docs | ✅ | [`phase-1-invariants.md`](phase-1-invariants.md) — 10 web-specific invariants with their guard tests, additive to Phase 0. |

---

## Pure Mode Programming Model

A Pure Mode handler is a Spring bean implementing the `ExerisRequestHandler` contract:

```java
@Component
@ExerisRoute(method = HttpMethod.GET, path = "/hello")
public class HelloHandler implements ExerisRequestHandler {

    @Override
    public ExerisServerResponse handle(ExerisServerRequest request) {
        return ExerisServerResponse.ok()
            .contentType(MediaType.TEXT_PLAIN)
            .body("Hello from Exeris");
    }
}
```

This is NOT `@RestController`. There is no `@RequestMapping` dispatch in Pure Mode.
`@ExerisRoute`-annotated beans are scanned at startup and registered into
`ExerisRouteRegistry`. This is intentionally narrow.

`ExerisRequestHandler` is a contract defined in `exeris-spring-runtime-web`.
It is NOT a kernel SPI type. The kernel SPI extension point is `HttpHandler` —
implemented by `ExerisHttpDispatcher`.

---

## Request Flow (Pure Mode)

```
TCP/QUIC byte stream arrives at Exeris transport layer
    → Exeris HttpServerEngine: HTTP/1.1 or HTTP/2 framing
    → HttpExchange created (off-heap, LoanedBuffer body)
    → PAQS scheduler: priority check, backpressure gate
    → Virtual Thread spawned (1 per request)

Virtual Thread executes:
    → ExerisHttpDispatcher.handle(exchange)
        → ExerisRouteRegistry.resolve(method, path)
        → ExerisServerRequest wraps HttpRequest (no copy)
        → handler.handle(request) → ExerisServerResponse
        → ExerisServerResponse.toKernelResponse() builds HttpResponse
        → exchange.respond(response)   ← ownership of LoanedBuffer transferred to engine
    → Virtual Thread completes

Exeris engine writes response bytes to wire
    → LoanedBuffer released (off-heap, ref-counted)
```

---

## Key Design Decisions (preserved as Phase 1 invariants)

- **No body copy in primary path.** `ExerisServerRequest.body()` is a view backed by the
  kernel's `LoanedBuffer.segment()`. `byte[]`-returning methods are explicit opt-in.
- **No `ThreadLocal` on the Pure Mode hot path.** Per-request context propagates via
  `ScopedValue`. `ThreadLocal` is permitted only in `*.compat.*` and only with
  `finally`-cleared bridging.
- **No `DispatcherServlet`.** Routing is `ExerisRouteRegistry`-only. `@RequestMapping`
  dispatch lives in Compatibility Mode (Phase 2).
- **Singleton codec instances.** Any object mapper must be a pre-constructed singleton —
  never per-request.
- **Error responses carry no stack traces in production.** `ExerisErrorMapper` produces
  safe error bodies.

---

## Exit Criteria

Phase 1 closes when all of the following hold:

1. ✅ A Spring application starts without Tomcat/Netty/Jetty/Undertow as ingress.
2. ✅ HTTP request reaches Exeris transport → `ExerisHttpDispatcher` → Spring handler bean.
3. ✅ HTTP response exits through Exeris transport.
4. ✅ `WallIntegrityTest` still passes (The Wall remains intact).
5. ✅ `ExerisWireLevelRuntimeIntegrationTest` covers bind, body, 404, custom status,
   in-flight drain, and telemetry scope evidence.
6. ✅ Allocation baseline test enforces a hard ≤ 1024 B/req mean budget for empty-body
   GET dispatch on the Pure Mode path (`ExerisDispatcherAllocationBaselineTest`).
7. ✅ No `jakarta.servlet.*` or `io.netty.*` on the effective classpath of `web` and
   `autoconfigure` (`PureModeClasspathGuardTest`).
8. ✅ Same classpath guard applied across `tx`, `data`, `actuator` modules.
9. ✅ Phase 1 invariants documented in [`phase-1-invariants.md`](phase-1-invariants.md).

All nine criteria are met. Phase 1 is closed.

---

## Risks (resolved at closure)

| Risk | Resolution |
|:-----|:-----------|
| Allocation budget too tight on CI hardware → flaky | Budget set at 1024 B/req mean (observed ~277 B/req, ≈ 27% of budget); 30k warmup + 30k measurement smooths JIT/GC noise; latency measured but not asserted. |
| Classpath guard expansion exposes accidental drift in tx/data/actuator | Guards landed green across all five Pure Mode modules; no drift surfaced. |
| Phase 1 invariants overlap with Phase 0 invariants | `phase-1-invariants.md` is additive and web-specific (request flow, no body copy, `LoanedBuffer` ownership, `ScopedValue`-only context, alloc budget); references Phase 0 instead of duplicating. |
