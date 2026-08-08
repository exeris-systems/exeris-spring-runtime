# Phase 3B-α Invariants — Request Scope + Structured Concurrency

**Status:** Locked-in (Phase 3B-α implementation lands in the 0.6.0-preview train; preview-track, default-off)
**Source of authority:** ADR-006 (The Wall), ADR-010 (Host Runtime Model), ADR-011 (Pure vs
Compatibility Mode), and **ADR-029** (Phase 3B-α scope). This page enumerates the
non-negotiable invariants Phase 3B-α establishes for the Exeris request-scope and structured-
concurrency surface.

A change that breaks any item below is an architectural regression, not a style issue, and
requires a superseding ADR — not a workaround in code.

Phase 0–3 invariants
([`phase-0-invariants.md`](phase-0-invariants.md),
[`phase-1-invariants.md`](phase-1-invariants.md),
[`phase-2-invariants.md`](phase-2-invariants.md),
[`phase-3-invariants.md`](phase-3-invariants.md))
still apply in full; Phase 3B-α invariants are additive and scope-specific. The Phase 3
"3B deferred to 3.x" entry was graduated on 2026-05-17 per ADR-029; see
`phase-3-invariants.md` invariant #10 for the graduation pointer.

These are also the **graduation gate** for promoting 3B-α from 1.0 preview to bounded GA
in 1.0.x. A regression on any of these blocks graduation.

---

## 1. Scope binding is opt-in and never default

`ExerisHttpDispatcher` binds `ScopedValue<RequestScope>` around `HttpHandler.handle` only when
`exeris.runtime.context.scope.enabled=true`. The conditional is the
`ExerisContextScopeProperties` record with `@DefaultValue("false")` — there is no
`matchIfMissing=true` and there is no implicit activation path.

- **Guard:** `ExerisWebAutoConfigurationTest` covers the default-disabled path; integration
  tests cover the enabled path.

## 2. `ScopedValue` is the only carrier — no `ThreadLocal` in the scope package

`eu.exeris.spring.runtime.web.scope..` does not depend on `java.lang.ThreadLocal` or
`java.lang.InheritableThreadLocal`. Hot-path discipline (per `CLAUDE.md` §"Pure Mode vs
Compatibility Mode") is enforced at the package level.

- **Guard:** `RequestScopeArchitectureTest#scopePackageMustNotUseThreadLocal` (the first
  per-package `ThreadLocal` ban in the repo at the type level).

## 3. No Spring legacy web-context coupling

The scope package does not depend on `org.springframework.web.context.request..`. Spring's
`@RequestScope` / `@SessionScope` are servlet-bound and intentionally not bridged — they remain
available to Compatibility Mode applications via the existing Spring path.

- **Guard:** `RequestScopeArchitectureTest#scopePackageMustNotDependOnSpringWebContextRequest`.

## 4. ⛔ Withdrawn 2026-08-08 — tenant isolation across `StructuredTaskScope` forks

This invariant, and the two merge-blocking tests that guarded it, are **withdrawn** together with
ADR-029 obligations 2 and 6. It is recorded here rather than deleted so the disappearance of a
merge-blocking guard is explained instead of merely noticed.

It asserted that two concurrent outer scopes never leak tenants into each other's forks, and
credited `ExerisStructuredScope.fork(...)`'s rebind for it. Measured on JDK 26, a raw
`StructuredTaskScope` propagates the binding on its own and the rebind was redundant — so the
property was `StructuredTaskScope`'s, and the tests passed against the JDK. Nothing this repository
owned was being verified, and the class has been deleted.

What is ours, and is still guarded, is the facade: see invariant 5 and
`ExerisRequestScopeTest`. Full rationale in ADR-029 §"Withdrawal of obligations 2 and 6" and
[RFC-2026-08-08](../rfc/RFC-2026-08-08-two-track-jdk-line.md).

## 5. Disabled-path returns `Optional.empty()` — no fallback to thread-bound state

When the scope is unbound (the property is disabled or code runs outside an Exeris HTTP request
path — for example a `@Scheduled` method on a Spring bean), every `Optional`-returning method on
`ExerisRequestScope` returns `Optional.empty()`. The `require*` helpers throw
`IllegalStateException`. There is no `ThreadLocal` fallback path that would mask the absence.

- **Guards:** `ExerisRequestScopeTest#unboundScope_typedAccessorsReturnEmpty`,
  `#unboundScope_requireAccessorsThrow`. (Previously
  `ExerisStructuredScopeIntegrationTest#disabledPathReturnsEmpty` — restated without the withdrawn
  wrapper, since it was always a property of the facade.)

## 6. No tracing emission, no W3C `traceparent` ingress, no OTel bridge in 3B-α

3B-α leaves a small `attribute(String, Class<T>)` API on `ExerisRequestScope` that ADR-031
(3B-β/γ) will later use to bind `TraceContext` — but the 3B-α package itself does **not**
import any tracing types, does **not** read W3C `traceparent` headers, and does **not** depend
on Micrometer Tracing, OpenTelemetry API, or any kernel telemetry SPI beyond `TelemetrySink`
(already a Phase 1 dependency).

A future PR that wires Micrometer Tracing, OTel, or W3C `traceparent` into the scope package is
**not** an ADR-029 violation — it is an ADR-031 concern; the scope package is the attachment
point, and ADR-031 governs what attaches there.

## 7. `RequestScopeResolver` is the single extension point for building a scope

The dispatcher does not parse request headers itself. When the property is enabled, the
application provides a `@Bean RequestScopeResolver` that builds a `RequestScope` from each
incoming request (typically from headers, claims, or query parameters). The auto-config maps
the property-and-resolver combination to one of three states:

- Property disabled (default) → `RequestScopeBinder.noop()` (zero-cost pass-through).
- Property enabled, no resolver bean → `RequestScopeBinder.noop()` + INFO log once.
- Property enabled, resolver bean present → `RequestScopeBinder.resolving(resolver)`.

If the resolver returns `null` (it should not), the binder falls back to
`RequestScope.empty()` — the scope is bound but carries no identity.

- **Guard:** autoconfig tests cover the three-state matrix.

---

## Summary of guard mapping

| Invariant | Guard test(s) |
|:----------|:--------------|
| Default-off activation | `ExerisWebAutoConfigurationTest` (default-disabled path) |
| `ScopedValue` only — no `ThreadLocal` | `RequestScopeArchitectureTest#scopePackageMustNotUseThreadLocal` |
| No Spring web-context coupling | `RequestScopeArchitectureTest#scopePackageMustNotDependOnSpringWebContextRequest` |
| ~~Tenant isolation across forks~~ | **withdrawn 2026-08-08** — see invariant 4; the property was `StructuredTaskScope`'s |
| Disabled-path returns empty | `ExerisRequestScopeTest#unboundScope_typedAccessorsReturnEmpty`, `#unboundScope_requireAccessorsThrow` |
| Explicit rebind off the request thread | `ExerisRequestScopeTest#callWith_rebindsOnAThreadThatInheritedNothing`, `#nestedScopes_innerShadowsOuterAndOuterIsRestored` |
| No tracing/OTel emission in 3B-α | code review (ADR-029 obligation 5); attribute API stays the only seam ADR-031 attaches to |
| Resolver is the sole extension point | autoconfig test matrix (three-state) |

---

## Graduation criterion to 1.0.x bounded GA

Per ADR-029 (the same single criterion stated in `docs/roadmap-1.0-trl9.md` §"Recommended 1.0
Scope") — graduation requires (a) all invariants above to stay green AND (b) at least one
downstream service has run 3B-α in production for a representative period. Either gap keeps
the module preview.
