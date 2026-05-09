# Phase 2 Invariants

**Status:** Locked-in (Phase 2 closed at sub-phases 2a + 2b + 2c + 2d)
**Source of authority:** ADR-006 (The Wall), ADR-010 (Host Runtime Model),
ADR-011 (Pure vs Compatibility Mode), ADR-021 (Gateway-Class Workloads Out of
Compatibility Scope), and the master plan
[`phase-2-spring-compat.md`](phase-2-spring-compat.md). This page enumerates the
non-negotiable invariants Phase 2 established for the Spring MVC compatibility
bridge.

A change that breaks any item below is an architectural regression, not a style
issue, and requires a superseding ADR — not a workaround in code.

Phase 0 ([`phase-0-invariants.md`](phase-0-invariants.md)) and Phase 1
([`phase-1-invariants.md`](phase-1-invariants.md)) invariants still apply in
full; Phase 2 invariants are additive and compat-specific.

---

## 1. Compatibility Mode is opt-in and never default

`exeris.runtime.web.mode=compatibility` is the **only** activator. Default
remains `pure`. There is no per-request mode switching, no auto-fallback, no
feature-detection that silently activates Compat. `ExerisCompatAutoConfiguration`
gates on `@ConditionalOnProperty(prefix="exeris.runtime.web", name="mode",
havingValue="compatibility")` — no `matchIfMissing`.

- **Guard:** `ExerisCompatAutoConfigurationTest` verifies activation only when
  the property is present and equal to `compatibility`.

## 2. No `DispatcherServlet`, no `spring-webmvc` in the Compat path

The Compat bridge uses `RequestMappingHandlerMapping` and
`RequestMappingHandlerAdapter` directly, with `org.springframework.http.server.ServerHttpRequest`/`Response`
adapters from `spring-web` — never `org.springframework.web.servlet..`.
This means **no `DispatcherServlet`**, no `ViewResolver`, no `ModelAndView`
propagation.

- **Guard:** `CompatibilityIsolationGuardTest#compatPackage_mustNotDependOnSpringWebMvc`
  blocks any `compat.*` import from `org.springframework.web.servlet..`.

## 3. Servlet API stays banned

`jakarta.servlet.*` is absent on the runtime classpath in every module,
including in Compatibility Mode. The Compat bridge specifically does not
introduce a servlet container under any property toggle.

- **Guards:** `WallIntegrityTest#noClassAnywhere_mustNotImportServletApi`,
  `PureModeClasspathGuardTest` × 5 modules.

## 4. Gateway-class workloads are not in Compat scope

Per ADR-021: Spring Cloud Gateway (both MVC and reactive flavours),
`RouterFunction<ServerResponse>` from `org.springframework.web.servlet.function..`,
and any "filter pipeline + backend forwarder + route DSL" runtime is out of
scope for Compatibility Mode. Such workloads either run on their native stack
as a documented exception, or wait on `exeris-spring-runtime-gateway` (Phase 5).

## 5. Compat ↔ Pure isolation is one-way

Pure Mode code does not import from `*.compat.*`. Compat code may import Pure
Mode bridge classes (`ExerisServerRequest`/`Response`, `ExerisErrorMapper`),
never the other way around.

- **Guards:** `CompatibilityIsolationGuardTest#pureModeDispatcher_mustNotDependOnCompatPackage`,
  module package convention.

## 6. `ThreadLocal` bridging is bounded to entry-filter scope

Compat-mode `ThreadLocal` binding (e.g. `SecurityContextHolder`,
`LocaleContextHolder`) is permitted **only** inside `compat.context.*` and
`compat.filter.*`. It is populated exactly once per request in the entry filter
before `ScopedValue.run()`, and cleared deterministically in `finally` on both
normal and exceptional paths.

- **Guards:**
  - `CompatibilityIsolationGuardTest#exerisContextHolder_mustNotBeCalledFromPureModePath`
  - `CompatibilityIsolationGuardTest#exerisContextHolder_mustOnlyBeCalledFromFilterOrContextScope`
  - `CompatibilityIsolationGuardTest#compatFilterPackage_mustNotImportServletApi`
  - `ExerisContextHolderTest`, `ExerisSecurityContextFilterTest` exercise the
    bind-and-clear contract.

## 7. Compat dispatch allocation cost is measured and reported

Per ADR-011, "compatibility-mode allocation cost is documented, never hidden."
A side-by-side allocation report compares Pure Mode and Compat Mode dispatch
for the same logical empty-body GET request and logs the delta on every test
run. The report **does not assert** a hard budget on Compat magnitude — the
cost is acknowledged and tracked, not constrained.

- **Guard:** `ExerisCompatAllocationCostReportTest` (informational only;
  sanity assertions verify both dispatchers produce a response).
- **Current observed baseline (2026-05-09):** Pure ≈ 176 B/dispatch, Compat
  ≈ 5856 B/dispatch (≈ 33× overhead, dominated by argument resolution
  wrappers, `ServerHttpRequest`/`Response` adapters, and ThreadLocal
  binding/clear cycles). Pure Mode keeps its hard ≤ 1024 B/req budget per
  Phase 1.

## 8. `@CompatibilityMode` marker on top-level Compat entry classes

Compat entry classes carry the `@CompatibilityMode` annotation as a
discoverability marker. Currently applied to `ExerisCompatDispatcher`,
`ExerisSpringMvcBridge`, and `ExerisCompatAutoConfiguration`. Inner mechanics
(argument resolvers, return-value handlers, filters) inherit Compat status
from their package location and need not be marked individually.

The marker is documentation, not enforcement — architectural isolation is
enforced by the package convention (`*.compat.*`) and the isolation guard
test set. New top-level Compat entry classes added in future PRs SHOULD be
marked.

## 9. Heap allocation acknowledged, never claimed away

The Compat path allocates more than Pure Mode. JSON body
deserialization (`Jackson ObjectMapper.readValue()`), argument resolution
wrappers, `ServerHttpRequest`/`Response` adapters per request — all are heap
allocations the Compat bridge accepts as the cost of Spring MVC semantics.
Documentation, javadocs, and feature-support matrices must not claim
zero-heap or Pure-Mode-equivalent performance for the Compat path.

## 10. Feature support matrix is the contract

The "Feature Support Matrix" in `phase-2-spring-compat.md` enumerates which
Spring MVC features the bridge supports, partially supports, defers, or
declares out of scope. Adding a feature to "Supported" requires a passing
integration test; moving a feature from "Deferred" to "Supported" requires
the same plus a real downstream demand signal recorded in the PR. Removing
a feature from "Supported" is an ADR-class change.

`HandlerInterceptor` and `WebMvcConfigurer` are explicitly **deferred to
2.x** in the matrix; the original "Partial" claim was downgraded at Phase 2d
closure on the basis that no downstream demand was observed and that the
common use cases already have native paths (security via
`ExerisSecurityContextFilter`, telemetry via `TelemetrySink`,
custom logic via `@ControllerAdvice` or Pure Mode).

---

## How invariants are enforced

| Invariant | Primary guard |
|:---|:---|
| Opt-in activation, never default | `ExerisCompatAutoConfigurationTest` |
| No `DispatcherServlet`, no `spring-webmvc` in Compat | `CompatibilityIsolationGuardTest#compatPackage_mustNotDependOnSpringWebMvc` |
| Servlet API banned | `WallIntegrityTest`, `PureModeClasspathGuardTest` × 5 modules |
| Gateway-class out of Compat scope | ADR-021 + `compatPackage_mustNotDependOnSpringWebMvc` (blocks `RouterFunction` from `web.servlet.function..`) |
| One-way Compat ↛ Pure visibility | `CompatibilityIsolationGuardTest#pureModeDispatcher_mustNotDependOnCompatPackage` |
| `ThreadLocal` bounded to filter/context scope | `CompatibilityIsolationGuardTest` (two ContextHolder rules + filter-no-servlet rule) |
| Compat allocation cost reported | `ExerisCompatAllocationCostReportTest` (informational logging) |
| `@CompatibilityMode` marker on entry classes | Documentation; package convention is the enforcement |
| Heap allocation acknowledged | `phase-2-spring-compat.md` feature matrix + ADR-011 obligation |
| Feature support matrix as contract | Reviewer policy + integration test set (`ExerisCompatMvcIntegrationTest` 15/15) |

These tests must stay green. A failure indicates a real architectural
regression; the test is not the bug.
