# Phase 2: Spring MVC Compatibility Bridge (Opt-In)

**Status:** Complete (closed 2026-05-09)
**Depends on:** Phase 1 (closed 2026-05-09)
**Milestone:** M2
**Mode:** Compatibility Mode — opt-in, never default
**Governing ADRs:** ADR-006 (The Wall), ADR-010 (Host Runtime Model), ADR-011 (Pure vs Compatibility), [ADR-021](../adr/ADR-021%20Gateway-Class%20Workloads%20Out%20of%20Compatibility%20Scope.md) (gateway-class out of Compat scope)
**Invariants captured in:** [`phase-2-invariants.md`](phase-2-invariants.md)

---

## Goal

Allow developers to write `@RestController` + `@RequestMapping` beans and have them
dispatched through the Exeris request path — **without reintroducing Tomcat, Netty, or
DispatcherServlet as the canonical runtime path**.

Compatibility mode is:
- an additive layer on top of the Pure Mode request path from Phase 1
- always explicitly activated: `exeris.runtime.web.mode=compatibility`
- never the default
- not a replacement for Pure Mode
- explicitly inferior in allocation profile to Pure Mode
- **bounded to `@RequestMapping`-style dispatch.** Gateway-class workloads (`RouterFunction`, Spring Cloud Gateway DSL) are explicitly out of scope per ADR-021.

---

## Sub-phase Structure

Phase 2 is delivered in four sub-phases. The original deliverables 1–8 from this
document have been consolidated into the sub-phase tables below.

| Sub-phase | Scope | Status |
|:---|:---|:---|
| **2a** Core MVC bridge | Dispatcher, MVC bridge, `ServerHttpRequest`/`ServerHttpResponse` adapters, autoconfiguration | ✅ Complete |
| **2b** Argument resolvers + return value handlers | `@PathVariable` / `@RequestParam` (with `ConversionService`) / `@RequestHeader` / `@RequestBody` (with `@Valid`) / `Authentication`; `@ResponseBody` / `ResponseEntity` | ✅ Complete |
| **2c** Exception handling + security bridging | `@ExceptionHandler` (controller + `@ControllerAdvice`); `ExerisSecurityContextFilter`; isolation guard | ✅ Complete |
| **2d** Closure hardening | `@CompatibilityMode` marker, allocation cost report, invariants doc, scope corrections | ✅ Complete |

---

## Phase 2a — Core MVC Bridge ✅

In-process compatibility dispatch from `HttpExchange` through Exeris-owned bridge classes
to Spring `@RequestMapping` handler methods. **No `DispatcherServlet`, no servlet API.**

| # | Deliverable | Module | Status | Evidence |
|:-:|:------------|:-------|:-------|:---------|
| 1 | `ExerisCompatDispatcher` — replaces `ExerisHttpDispatcher` when `mode=compatibility` | `web` | ✅ | `ExerisCompatDispatcherTest` 2/2 |
| 2 | `ExerisSpringMvcBridge` — owns `RequestMappingHandlerMapping` + `RequestMappingHandlerAdapter` without `DispatcherServlet` | `web` | ✅ | exercised across `ExerisCompatMvcIntegrationTest` 15/15 |
| 3 | `ExerisMvcServerHttpRequest` — `org.springframework.http.server.ServerHttpRequest` over `HttpRequest` | `web` | ✅ | `ExerisMvcServerHttpRequestTest` 10/10 |
| 4 | `ExerisMvcServerHttpResponse` — `ServerHttpResponse` writing to `HttpResponse`/`LoanedBuffer` | `web` | ✅ | `ExerisMvcServerHttpResponseTest` 2/2 |
| 5 | `ExerisCompatAutoConfiguration` — conditional `mode=compatibility` activation | `web` | ✅ | `ExerisCompatAutoConfigurationTest` 3/3 |
| 6 | `ExerisHandlerMethodRegistry` — handler method discovery + lookup | `web` | ✅ | `ExerisHandlerMethodRegistryTest` 2/2 |

---

## Phase 2b — Argument Resolvers + Return Value Handlers ✅

Spring's argument-resolution and return-value-handler contracts implemented over the
Exeris path, without servlet API.

| # | Deliverable | Module | Status | Evidence |
|:-:|:------------|:-------|:-------|:---------|
| 7 | `ExerisPathVariableArgumentResolver` | `web` | ✅ | `ExerisPathVariableArgumentResolverTest` 3/3 |
| 8 | `ExerisRequestParamArgumentResolver` (with `ConversionService` for enums + custom converters) | `web` | ✅ | `ExerisRequestParamArgumentResolverTest` 5/5 |
| 9 | `ExerisRequestHeaderArgumentResolver` | `web` | ✅ | `ExerisRequestHeaderArgumentResolverTest` 3/3 |
| 10 | `ExerisRequestBodyArgumentResolver` (with `@Valid` via `SmartValidator`) | `web` | ✅ | `ExerisRequestBodyArgumentResolverTest` 5/5 + integration |
| 11 | `ExerisAuthenticationArgumentResolver` | `web` | ✅ | exercised in integration |
| 12 | `ExerisResponseBodyReturnValueHandler` (`@ResponseBody`) | `web` | ✅ | integration |
| 13 | `ExerisResponseEntityReturnValueHandler` (`ResponseEntity<T>`) | `web` | ✅ | `compatMode_responseEntity_propagatesStatus` |
| 14 | `ExerisCompatTypeConverter` (Spring `ConversionService` integration) | `web` | ✅ | enum + user-converter scenarios |

### Bonuses vs original plan

These items are implemented but were not listed as Phase 2 deliverables in the original
plan; they grew naturally from real downstream needs:

- `Authentication` argument resolver (`ExerisAuthenticationArgumentResolver`)
- Full `ConversionService` integration on `@RequestParam` (enums, user-defined
  `Converter<String, T>` beans like `String → User`)
- `@Valid` on `@RequestBody` via `SmartValidator` with `MethodArgumentNotValidException`
  surfaced through `@ControllerAdvice`

---

## Phase 2c — Exception Handling + Security Bridging ✅

Spring exception-handling chain bridged to Exeris error responses, plus a narrow
`ThreadLocal` bridge for `SecurityContextHolder`.

| # | Deliverable | Module | Status | Evidence |
|:-:|:------------|:-------|:-------|:---------|
| 15 | `ExerisExceptionHandlerResolver` — controller + `@ControllerAdvice` `@ExceptionHandler` | `web` | ✅ | `ExerisExceptionHandlerResolverTest` 3/3 + integration |
| 16 | `ExerisSecurityContextFilter` — `ScopedValue` → `SecurityContextHolder` (cleared in `finally`) | `web` | ✅ | `ExerisSecurityContextFilterTest` 6/6 |
| 17 | `ExerisContextHolder` — entry-filter scope; isolated to `compat.context.*` | `web` | ✅ | `ExerisContextHolderTest` 10/10 |
| 18 | `ExerisThreadLocalBridge` — bridge contract scoped to `compat.context.*` | `web` | ✅ | unit tests |
| 19 | `CompatibilityIsolationGuardTest` — architecture guard | `web` (test) | ✅ | 7/7: no `jakarta.servlet`, no `compat → spring-webmvc`, `ExerisHttpDispatcher ↛ compat`, `ExerisContextHolder` confined to `compat.context|filter`, no `kernel.core` from web, no `servlet` in `compat.filter` |

End-to-end coverage: `ExerisCompatMvcIntegrationTest` 15/15 — `@GetMapping`, `@PostMapping` with `@RequestBody`, `@PathVariable`, `@RequestParam`, `@RequestHeader`, `@ExceptionHandler` (controller + advice), `ResponseEntity` status, `@Valid` rejection path, enum `ConversionService`, user-converter, class-level prefix, UUID path variable, missing-route 404.

---

## Phase 2d — Closure Hardening ✅

Items required to formally close M2 with full architectural anchor and a documented
compatibility cost.

| # | Item | Module | Status | Result |
|:-:|:------|:-------|:-------|:-----|
| 20 | `@CompatibilityMode` marker annotation (per ADR-011) | `web` | ✅ | `eu.exeris.spring.runtime.web.compat.CompatibilityMode` (`RetentionPolicy.CLASS`); applied to `ExerisCompatDispatcher`, `ExerisSpringMvcBridge`, `ExerisCompatAutoConfiguration`. Documentation marker; isolation enforced by package convention + `CompatibilityIsolationGuardTest` 7/7. |
| 21 | Compatibility allocation cost report test | `web` (test) | ✅ | `ExerisCompatAllocationCostReportTest` — side-by-side pure vs compat for the same empty-body GET; logs delta; informational only. **Observed (2026-05-09):** Pure ≈ 176 B/dispatch, Compat ≈ 5856 B/dispatch (≈ 33× overhead). Satisfies ADR-011 "compatibility-mode allocation cost is documented, never hidden". |
| 22 | Phase 2 invariants document | docs | ✅ | [`phase-2-invariants.md`](phase-2-invariants.md) — 10 compat-specific invariants additive to Phase 0/1, each mapped to its guard. |
| 23 | Doc reconciliation | docs | ✅ | This file rewritten as master with sub-phases; feature-support matrix corrected for items moved to ADR-021 (gateway-class) and `HandlerInterceptor`/`WebMvcConfigurer` (deferred). |

---

## Feature Support Matrix (corrected)

| Feature | Supported | Notes |
|:--------|:----------|:------|
| `@RestController` + `@RequestMapping` | ✅ | `ExerisHandlerMethodRegistry` resolves; `RequestMappingHandlerAdapter` invokes |
| `@GetMapping`, `@PostMapping`, `@PutMapping`, `@DeleteMapping`, `@PatchMapping` | ✅ | |
| `@PathVariable` | ✅ | String + primitive + `UUID`; richer types via `ConversionService` |
| `@RequestParam` | ✅ | Enums + user-defined `Converter` beans via `ConversionService` |
| `@RequestBody` (JSON) | ✅ | Jackson via `MappingJackson2HttpMessageConverter`; documented heap allocation |
| `@Valid` on `@RequestBody` | ✅ | `SmartValidator`; `MethodArgumentNotValidException` surfaces through `@ControllerAdvice` |
| `@ResponseBody` (JSON) | ✅ | Documented heap allocation |
| `@ResponseStatus` | ✅ | |
| `ResponseEntity<T>` | ✅ | Status + headers propagated |
| `@RequestHeader` | ✅ | |
| `Authentication` (Spring Security) | ✅ | `ExerisAuthenticationArgumentResolver` resolves from `ExerisContextHolder`-bridged context |
| `@ExceptionHandler` | ✅ | Per-controller |
| `@ControllerAdvice` | ✅ (`@ExceptionHandler` only) | Cross-controller exception mapping |
| `ServerHttpRequest#getRemoteAddress` | 🟡 Partial | Resolved from RFC 7239 `Forwarded` and de-facto `X-Forwarded-For` / `X-Real-IP` headers. `null` when none of those is present (kernel `HttpRequest` does not expose raw socket peer). |
| `ServerHttpRequest#getLocalAddress` | ❌ | Kernel `HttpRequest` does not expose the bound local socket; returns `null`. Read `getHeaders().getHost()` if requested authority is needed. |
| `HttpServletRequest` / `HttpServletResponse` | ❌ | Banned in all modes (`jakarta.servlet.*` not on classpath) |
| `MultipartFile` | ❌ | Deferred; possible future phase |
| `Model` / `ModelAndView` | ❌ | View resolution not supported |
| `RouterFunction<ServerResponse>` (servlet variant from `org.springframework.web.servlet.function.*`) | ❌ | **Out of scope per ADR-021.** Servlet-bound; would re-introduce `DispatcherServlet`. Edge gateway use cases addressed by Phase 5 (`exeris-spring-runtime-gateway`). |
| `RouterFunction<ServerResponse>` (reactive variant from `org.springframework.web.reactive.function.*`) | ❌ | **Out of scope per ADR-021.** Pulls Reactor + Netty + WebFlux. |
| Spring Cloud Gateway (any flavour) | ❌ | **Out of scope per ADR-021.** Full gateway runtime, not a Compat-bridgeable Spring feature. |
| `WebMvcConfigurer` | ❌ Deferred to 2.x | Implementing this would require a stable interceptor mechanism first; see row below. No production demand observed yet. |
| `HandlerInterceptor` (`preHandle`, `afterCompletion`) | ❌ Deferred to 2.x | Original plan said "Partial"; no implementation landed. Most common use cases (auth, logging, telemetry) are already covered: `ExerisSecurityContextFilter` (security), `TelemetrySink` (metrics), Pure-Mode `ExerisRequestHandler` chain (custom logic). Niche use cases await real demand signal. |
| `@RequestMapping(headers=...)`, `(consumes=...)`, `(produces=...)` advanced predicates | 🟡 Partial | Standard `RequestMappingInfo` predicates inherited from Spring; advanced patterns may need additional resolver work — case by case |

### Note on deferred items (`WebMvcConfigurer`, `HandlerInterceptor`)

The original Phase 2 plan listed these as "Partial". They never landed. The honest status
is "deferred", not "partial". Reasons to defer rather than implement now:

- **Most common use cases have a native path.** Per-request auth/security shimming is in
  `ExerisSecurityContextFilter`. Per-request telemetry is via `TelemetrySink`. Per-request
  custom logic is `ExerisRequestHandler` (Pure Mode) or `@ControllerAdvice` (Compat).
- **No observed downstream demand** in the migration window so far.
- **Adding without demand creates surface area to maintain.** A `HandlerInterceptor`
  shim that nobody uses is still a Compat surface that has to stay green across Spring
  upgrades.

If a downstream service surfaces a real interceptor use case that no native path covers,
this becomes a Phase 2.x item with a concrete shape driven by the real requirement —
not a speculative bridge.

---

## Architecture: What Changes vs Phase 1

Phase 1 path (Pure Mode):
```
HttpExchange → ExerisHttpDispatcher → ExerisRouteRegistry → ExerisRequestHandler
```

Phase 2 Compatibility path (opt-in, when `mode=compatibility`):
```
HttpExchange → ExerisCompatDispatcher → ExerisSpringMvcBridge
                                            → RequestMappingHandlerMapping (Spring)
                                            → RequestMappingHandlerAdapter (Spring)
                                            → @RestController method
                                            → return value → HttpResponse
```

`ExerisCompatDispatcher` replaces `ExerisHttpDispatcher` when the property activates.
Both modes do not coexist in the same JVM (a different from gateway routes in Phase 5,
which are sibling routes within the same dispatcher).

---

## Key Design Constraints (locked in by 2c isolation guard + ADR-011)

- **`DispatcherServlet` is NOT used.** `RequestMappingHandlerMapping` + `RequestMappingHandlerAdapter` are used directly with `ServerHttpRequest`/`ServerHttpResponse` adapters from `spring-web`. `compatPackage_mustNotDependOnSpringWebMvc` blocks any drift into `org.springframework.web.servlet..`.
- **Servlet API banned.** `jakarta.servlet.*` is absent on the classpath; `WallIntegrityTest` and module-local `PureModeClasspathGuardTest` enforce this.
- **`ThreadLocal` bridging is bounded.** Permitted only inside `compat.context.*` and `compat.filter.*`; cleared in `finally`; `exerisContextHolder_mustOnlyBeCalledFromFilterOrContextScope` enforces.
- **One-way visibility.** Pure Mode code does not import `*.compat.*`. `pureModeDispatcher_mustNotDependOnCompatPackage` enforces.
- **Heap allocation acknowledged.** Compat path allocates more than Pure Mode (Jackson `readValue` object graph; argument resolution wrappers; `ServerHttpRequest`/`Response` adapters per request). Cost is measured by `ExerisCompatAllocationCostReportTest` (Phase 2d) and documented per ADR-011.
- **Gateway-class workloads are not in this bridge.** Per ADR-021. Phase 5 owns that scope.

---

## Activation Configuration

```yaml
# application.yaml — opt into Compatibility Mode
exeris:
  runtime:
    web:
      mode: compatibility
```

Pure Mode (default):

```yaml
exeris:
  runtime:
    web:
      mode: pure  # default; no explicit config needed
```

---

## Exit Criteria

Phase 2 closes when all of the following hold:

1. ✅ A `@RestController` with `@GetMapping` and `@PostMapping` is dispatched correctly.
2. ✅ JSON request/response body works.
3. ✅ `@ExceptionHandler` is invoked for handler exceptions (controller + `@ControllerAdvice`).
4. ✅ No `jakarta.servlet.*` on the classpath in Compatibility Mode.
5. ✅ Compatibility Mode heap allocation report is generated and logged (`ExerisCompatAllocationCostReportTest`; observed Pure ≈ 176 B/dispatch, Compat ≈ 5856 B/dispatch, ≈ 33× overhead).
6. ✅ Pure Mode allocation baseline is unchanged — Phase 1 `ExerisDispatcherAllocationBaselineTest` stays green.
7. ✅ `ExerisHttpDispatcher` has zero imports from `*.compat.*` (`CompatibilityIsolationGuardTest`).
8. ✅ Phase 1 integration tests continue to pass unchanged.
9. ✅ `@CompatibilityMode` marker annotation present on top-level Compat entry classes (dispatcher, MVC bridge, autoconfig).
10. ✅ Phase 2 invariants documented in [`phase-2-invariants.md`](phase-2-invariants.md).

All ten criteria are met. Phase 2 is closed.

---

## Risks (resolved at closure)

| Risk | Resolution |
|:-----|:-----------|
| Compat allocation cost test becomes flaky on CI | Test ships informational only — no assertion on Compat magnitude. Output is a logged report (Pure ≈ 176 B, Compat ≈ 5856 B, ≈ 33× ratio observed); 30k warmup + 30k measurement smooths JIT/GC noise. Sanity assertions on response presence are robust. |
| `@CompatibilityMode` marker drifts (added to one class, missed on another) | Marker is documentation, not enforcement. Architectural isolation enforced by package convention + `CompatibilityIsolationGuardTest` 7/7 — independent of marker placement. Future entry classes can be marked at PR time. |
| Phase 2 invariants overlap with Phase 0/1 | `phase-2-invariants.md` is additive — references Phase 0/1 instead of duplicating. Compat-specific (mode opt-in, no `DispatcherServlet`, gateway-class out, `ThreadLocal` bounded, allocation cost reported). |
