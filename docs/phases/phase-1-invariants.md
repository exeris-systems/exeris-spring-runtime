# Phase 1 Invariants

**Status:** Locked-in (Phase 1 closed at sub-phases 1a + 1b + 1c)
**Source of authority:** ADR-010, `docs/architecture/module-boundaries.md`,
`docs/architecture/kernel-integration-seams.md`, and the master plan
[`phase-1-web-ingress.md`](phase-1-web-ingress.md). This page enumerates the
non-negotiable invariants Phase 1 established for the Pure Mode web ingress path.

A change that breaks any item below is an architectural regression, not a style
issue, and requires a superseding ADR — not a workaround in code.

Phase 0 invariants ([`phase-0-invariants.md`](phase-0-invariants.md)) still apply
in full; Phase 1 invariants are additive and web-specific.

---

## 1. Request enters and exits through Exeris

The Pure Mode request lifecycle is owned by Exeris end-to-end:

```
wire bytes → Exeris HttpServerEngine → HttpExchange → ExerisHttpDispatcher
           → @ExerisRoute Spring bean
           → ExerisServerResponse → exchange.respond(...) → wire bytes
```

Spring is invoked only to resolve the handler bean. It does not own the socket,
the framing, the request lifecycle, or the response emission path. Any change
that re-routes ingress through a servlet container, reactive engine, or
`DispatcherServlet` on the Pure Mode path is forbidden.

- **Guards:** `ExerisWireLevelRuntimeIntegrationTest` (6/6 wire-level scenarios),
  `ExerisPureModeRequestPathIntegrationTest`.

## 2. No `DispatcherServlet`, no `@RequestMapping` dispatch in Pure Mode

Pure Mode routing is `ExerisRouteRegistry`-only. Handler beans implement
`ExerisRequestHandler` and are registered via `@ExerisRoute`. Spring MVC
annotation dispatch (`@RestController`, `@RequestMapping`, `@GetMapping`, …)
is exclusively a Compatibility Mode feature and lives in `*.compat.*`.

- **Guard:** `CompatibilityIsolationGuardTest` — pure-mode code must not import
  from `*.compat.*`.

## 3. `ExerisRequestHandler` is a web-module contract, not a kernel SPI

The Pure Mode handler contract is defined in `exeris-spring-runtime-web` and is
the Spring-facing application extension point. The kernel SPI extension point
remains `HttpHandler`, implemented exactly once by `ExerisHttpDispatcher`.

Adding a new SPI in the kernel for the sake of Spring integration is a Wall
violation; the bridge always lives on the Spring side.

## 4. No body copy on the primary path

`ExerisServerRequest.body()` is a view backed by the kernel's
`LoanedBuffer.segment()`. The primary path operates on `MemorySegment` directly.
`byte[]`-returning methods are explicit opt-in (e.g. `bodyBytes()`) and document
the heap allocation they incur.

Adding a hidden `byte[]` materialisation to the primary path — including
defensive copies "just in case" — is forbidden.

## 5. `LoanedBuffer` ownership transfer rules

- The handler must release any `LoanedBuffer` it acquires, OR transfer ownership
  to the engine via `exchange.respond(response)`.
- After `exchange.respond(response)` returns, the engine owns the response body.
  The caller MUST NOT release it.
- `HttpHandler.handle` completes exactly once: respond OR throw `HttpException`,
  never both.

These are kernel hot-path contracts. Violations cause off-heap leaks or
double-free.

## 6. No `ThreadLocal` on the Pure Mode hot path

Per-request context propagates via `ScopedValue`. `ExerisContextHolder` binds
the request-scope state for the duration of `HttpHandler.handle`.

`ThreadLocal` is permitted only inside `*.compat.*` — and only with strict
`finally`-cleared bridging (e.g. `SecurityContextHolder` shimming). Any
`ThreadLocal` on the pure path is an architectural defect.

## 7. No standalone Pure Mode JSON codec class

Pure Mode body serialisation is handled directly through `ExerisServerResponse`
body APIs. There is intentionally no `ExerisJsonCodec` / no centralised
reflective serialisation pipeline on the pure path. Jackson is wired only in
Compatibility Mode (`MappingJackson2HttpMessageConverter`).

If body serialisation conveniences are added later, they must:

- be opt-in,
- not allocate a per-request `ObjectMapper`,
- not become a hidden default that all handlers route through.

## 8. Pure Mode dispatch allocation budget

Empty-body GET dispatch on the Pure Mode path must allocate **≤ 1024 bytes per
request on average**, measured via
`com.sun.management.ThreadMXBean.getThreadAllocatedBytes(...)`.

The threshold is a regression gate, not a performance target. Current observed
baseline: ~277 B/dispatch (≈ 27% of budget). Closing the gap to zero is a
non-goal — but blowing the budget without a documented reason is a regression.

- **Guard:** `ExerisDispatcherAllocationBaselineTest` (30k warmup + 30k measured
  iterations).

## 9. `ExerisWebAutoConfiguration` lives in the web module, not in autoconfigure

Web-runtime wiring (dispatcher bean, route registry, handler bean discovery)
belongs in `exeris-spring-runtime-web`. The boot autoconfigure module remains
the thin bootstrap layer (lifecycle, properties, `ConfigProvider` bridge) and
must not import from `eu.exeris.spring.runtime.web..`.

This preserves the Phase 0 boundary: autoconfigure stays small, web owns its
own Spring wiring.

- **Guard:** `WallIntegrityTest#autoconfigure_mustNotImportWebRuntimeClasses`.

## 10. Pure-Mode classpath baseline applies to every Pure Mode module

The Phase 0 ban on servlet API, Netty, Reactor, Spring WebFlux server
abstractions, and `DispatcherServlet` on the runtime classpath applies
uniformly across all Pure Mode modules:

- `exeris-spring-boot-autoconfigure`
- `exeris-spring-runtime-web`
- `exeris-spring-runtime-tx`
- `exeris-spring-runtime-data`
- `exeris-spring-runtime-actuator`

Each module ships a module-local `PureModeClasspathGuardTest` (4 ArchUnit rules:
servlet API, Netty/Reactor, WebFlux server, `DispatcherServlet`). New Pure Mode
modules must add the same guard set before merge.

---

## How invariants are enforced

| Invariant | Primary guard |
|:---|:---|
| Exeris-owned ingress end-to-end | `ExerisWireLevelRuntimeIntegrationTest` (bind, body, 404, status, drain, telemetry scope) |
| Routing via `ExerisRouteRegistry`, no `DispatcherServlet` on Pure Mode | `ExerisRouteRegistryTest`, `ExerisHttpDispatcherTest`, `CompatibilityIsolationGuardTest` |
| `ExerisRequestHandler` is not a kernel SPI | `WallIntegrityTest` (no Spring in kernel SPI/Core) |
| No body copy / `LoanedBuffer` ownership | `ExerisServerResponseTest`, `ExerisPureModeRequestPathIntegrationTest`, `ExerisWireLevelRuntimeIntegrationTest#pureMode_bodyResponse_*` |
| `HttpHandler.handle` completes exactly once | `ExerisHttpDispatcherTest`, error-path scenarios in dispatcher tests |
| No `ThreadLocal` on Pure Mode path | `CompatibilityIsolationGuardTest` |
| Allocation budget ≤ 1024 B/req | `ExerisDispatcherAllocationBaselineTest` |
| Autoconfigure stays thin | `WallIntegrityTest#autoconfigure_mustNotImportWebRuntimeClasses` |
| Pure-Mode classpath in every module | `PureModeClasspathGuardTest` × { autoconfigure, web, tx, data, actuator } |

These tests must stay green. A failure indicates a real architectural
regression; the test is not the bug.
