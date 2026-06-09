# ADR-029: Phase 3B-α Scope — Request Scope and Structured Concurrency Helpers (Kernel-Independent)

| Attribute       | Value                                                                                                                                                                                                                                                                                                              |
|:----------------|:------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **Status**      | **ACCEPTED** (drafted and accepted 2026-05-17; single decider — no future gating event; ratified by the PR that introduces this file)                                                                                                                                                                              |
| **Deciders**    | Arkadiusz Przychocki                                                                                                                                                                                                                                                                                               |
| **Date**        | 2026-05-17                                                                                                                                                                                                                                                                                                         |
| **Scope**       | spring/lifecycle (extends `exeris-spring-runtime-web` request lifecycle and `exeris-spring-boot-autoconfigure` lifecycle wiring; no new top-level module)                                                                                                                                                          |
| **Owning Repo** | `exeris-spring-runtime`                                                                                                                                                                                                                                                                                            |
| **Driven By**   | Phase 3 closure (2026-05-09) deferred 3B (request scope + tracing) to 3.x; downstream observability demand identified 2026-05-17 graduated 3B back into the 1.0 train. Kernel-gating analysis (captured in the ADR-031 reserved row in `exeris-docs/adr-index.md`; content TBD) showed the work splits cleanly into a kernel-independent half (this ADR) and a kernel-gated half (ADR-031). |
| **Compliance**  | [Roadmap to 1.0 and TRL-9](../roadmap-1.0-trl9.md), [Module Boundaries](../architecture/module-boundaries.md), [Phase 3 Invariants](../phases/phase-3-invariants.md)                                                                                                                                                |

## Context and Problem Statement

Phase 3B was originally three concerns folded into one row in the roadmap: (a) request scope propagation for tenant/correlation IDs, (b) structured concurrency helpers for kernel-call fan-out, (c) distributed-tracing context propagation and emission. Phase 3 closure (2026-05-09) deferred all three to "3.x" — the reasoning was that the request-path lifecycle was settling, and adding cross-cutting context propagation early would conflict with Phase 1/2 ingress finalization.

A 2026-05-17 downstream observability review changed two facts:

1. **In-flight migration demand for (a) and (b).** A downstream service migration identified that tenant isolation and correlation-ID propagation need to be load-bearing during the migration, not after — the application uses `StructuredTaskScope` to fan out kernel calls (persistence + event publication + downstream HTTP) and the tenant ID must propagate without `ThreadLocal` (banned on hot paths by the `CLAUDE.md` §"Pure Mode vs Compatibility Mode" narrative rule; obligation 4 below turns that rule into a per-package ArchUnit guard for the scope package).
2. **Kernel-gating distinction surfaced for (c).** The kernel places the W3C `traceparent` + `TraceContext` carrier via `ScopedValue` in its consolidated 1.0 GA roadmap Sprint 0.12 (~v0.12); the OTLP sink path (`PrometheusOtlpTelemetrySink`) is in the kernel telemetry gap section without sprint commitment. Distributed tracing therefore depends on kernel work that has a planned-but-future wait period (for β) and an uncertain wait period (for γ). *(Fact corrected 2026-06-09: this point originally pinned β to "`exeris-kernel` 0.8.0 Sprint 0.12". Kernel 0.8.0 shipped 2026-06-03 with no tracing — verified in `exeris-kernel-spi` `ConfigProvider` javadoc and kernel ADR-032 §traceparent. The correction touches only the kernel-gate fact in this ADR's context/cross-references; the 3B-α decision and obligations below are unchanged.)*

The two facts together point at a split:

- **3B-α** — request scope + structured concurrency helpers, **kernel-independent** (pure JDK 26 preview features: `ScopedValue`, `StructuredTaskScope`). Can land at 0.6.0-preview without waiting on the kernel. **This ADR.**
- **3B-β/γ** — context propagation bridge + OTel emission, **kernel-gated** (waits on the kernel `TraceContext` slot — consolidated 1.0 GA roadmap Sprint 0.12, ~v0.12, **not** 0.8.0 — for β, and on a future `PrometheusOtlpTelemetrySink` for γ). ADR-031.

This ADR scopes **3B-α only**. ADR-031 scopes 3B-β/γ.

This ADR answers: **what does Phase 3B-α deliver in 0.6.0-preview, and what stays out of scope to keep it kernel-independent?**

## 🏁 The Decision

**Phase 3B-α delivers, in 0.6.0-preview, a `ScopedValue`-backed request scope and a `StructuredTaskScope`-based fan-out helper API in `exeris-spring-runtime-web` and `exeris-spring-boot-autoconfigure`. No new module. No kernel dependencies beyond what Phase 1 already requires. No `ThreadLocal` on hot paths. No tracing emission — that is ADR-031.**

The Spring-side surface reads, in normalized form: *"An Exeris-hosted Spring application can scope tenant ID, correlation ID, and request-bound attributes to the request lifecycle via `ExerisRequestScope`, fan out to multiple kernel calls via `ExerisStructuredScope`, and rely on the runtime to propagate the scope across forks without `ThreadLocal`."*

**Concrete obligations:**

1. **`ExerisRequestScope` API — `ScopedValue`-backed.** A new package `eu.exeris.spring.runtime.web.scope` ships `ExerisRequestScope` with a small typed API: `ExerisRequestScope.tenantId()`, `correlationId()`, `attribute(String key, Class<T> type)`. Implementation is a single `ScopedValue<RequestScope>` bound around `HttpHandler.handle` invocation when `exeris.runtime.context.scope.enabled=true`. No `ThreadLocal` fallback; `ScopedValue` is the only carrier. Access outside an active scope returns `Optional.empty()` (or throws `IllegalStateException` for required-attribute helpers, with the choice documented per method).
2. **`ExerisStructuredScope` fan-out API.** A new package `eu.exeris.spring.runtime.web.scope.concurrent` ships `ExerisStructuredScope` — a thin wrapper around `StructuredTaskScope` that captures the current `ExerisRequestScope` at construction time and rebinds it inside each forked virtual thread. The wrapper exists to preserve the `ScopedValue` binding across `Joiner`-policied forks (per JDK 26 JEP 525 final API) without manual `ScopedValue.where(...).call(...)` boilerplate at every call site. **Exception propagation is the caller's job at the `join()` call site, not the wrapper's:** the policy-Joiner already surfaces the first failure (or the cause of "all forks failed") from `join()` per JDK semantics, and any mapping into kernel error-code conventions is application logic that belongs at the call site. (An earlier draft of this obligation mentioned `IOException` / configured-exception types as a wrapper concern; that was over-reaching — see §"Design notes" for the rationale.)
3. **Opt-in via `exeris.runtime.context.scope.enabled` (default `false`).** Activation must be explicit — applications that do not opt in pay zero cost. When disabled, every `Optional`-returning method on `ExerisRequestScope` (including `current()`, `tenantId()`, `correlationId()`, `attribute(...)`) returns `Optional.empty()` always; the `require*` helpers throw `IllegalStateException`; and `ExerisStructuredScope` falls through to plain `StructuredTaskScope` semantics without scope rebinding. The property is read at lifecycle start; runtime toggling is not supported. **Property namespace rationale:** the prefix is `exeris.runtime.context.scope` rather than `exeris.runtime.web.scope` because the namespace is reserved for the full Phase 3B family — 3B-α (this ADR), 3B-β (W3C `traceparent` propagation, ADR-031), and 3B-γ (OTel span/metric emission, ADR-031) will all bind under `exeris.runtime.context.*`. The package owner of the implementation classes is still `web` (the request lifecycle entry point); the property reflects the cross-cutting scope of the feature family, not the module ownership.
4. **No `ThreadLocal` in the scope package, enforced by a new guard.** No existing test bans `ThreadLocal` at the package level — `WallIntegrityTest` covers kernel-SPI/Spring independence, autoconfigure→web independence, and the classpath bans (servlet API, Tomcat/Jetty/Undertow, Netty/Reactor), but does **not** ban `ThreadLocal` as a type. The 3B-α implementation PR creates a new `RequestScopeArchitectureTest#scopePackageMustNotUseThreadLocal` (in `exeris-spring-runtime-web/src/test/java/eu/exeris/spring/runtime/web/scope/`) that asserts zero `ThreadLocal` field, parameter, or static reference under `eu.exeris.spring.runtime.web.scope..`. The hot-path narrative in `CLAUDE.md` §"Pure Mode vs Compatibility Mode" already states "`ThreadLocal` is banned on hot paths"; this ADR turns that narrative rule into a per-package ArchUnit guard for the scope package specifically.
5. **No tracing emission, no OTel bridge, no W3C `traceparent` ingress.** Those belong to 3B-β (ADR-031) and 3B-γ (ADR-031). 3B-α leaves a small `attribute(String, Class<T>)` API on `ExerisRequestScope` that 3B-β/γ will later use to bind `TraceContext` — but the 3B-α package itself does **not** import any tracing types, does **not** read W3C `traceparent` headers, and does **not** depend on Micrometer Tracing, OpenTelemetry API, or any kernel telemetry SPI beyond `TelemetrySink` (already a Phase 1 dependency).
6. **Tenant isolation across forks is verified.** `ExerisStructuredScopeIntegrationTest#tenantIdPropagatesAcrossForks` and `#tenantIdIsolatesPerOutermostRequest` are merge-blocking. The first asserts that a `tenantId` set in the outer request scope is readable from inside `fork(...)` callbacks; the second asserts that two concurrent requests with different `tenantId` values never see each other's scope.
7. **No application-side `ThreadLocal`-to-`ScopedValue` migration tooling.** Application owners migrating off existing `ThreadLocal<TenantId>` patterns are responsible for the migration. This ADR scopes the runtime affordance, not the migration path.

## API at a glance

> **2026-05-17 implementation-time amendment.** The 0.6.0-preview implementation (this section
> below) is the canonical surface. The pre-implementation listing in the previous draft of
> this ADR mirrored a deprecated preview shape of JDK `StructuredTaskScope` (nested
> `ShutdownOnFailure` / `ShutdownOnSuccess` classes extending the scope); JEP 525 finalised
> in JDK 26 on the `open(Joiner)` factory pattern and removed those nested classes. The
> adjusted listing keeps ADR-029's two policy semantics (failure-policy and success-policy)
> via two factory methods on a single `ExerisStructuredScope<T, R>` class, mapped internally
> to `Joiner.awaitAllSuccessfulOrThrow()` and `Joiner.anySuccessfulOrThrow()` respectively.
> `joinUntil(Instant)` was also dropped because JEP 525 final exposes per-scope timeout via
> `Configuration` at `open()` time rather than per-`join()`; that capability is a follow-up
> if downstream demand surfaces.

```java
package eu.exeris.spring.runtime.web.scope;

public final class ExerisRequestScope {
    public static Optional<RequestScope> current();                       // raw record; mostly diagnostic
    public static Optional<UUID> tenantId();                              // convenience over current().map(...)
    public static Optional<String> correlationId();
    public static <T> Optional<T> attribute(String key, Class<T> type);
    public static UUID requireTenantId();                                 // throws IllegalStateException if absent
    public static String requireCorrelationId();                          // throws IllegalStateException if absent

    public static void runWith(RequestScope scope, Runnable action);
    public static <T, X extends Throwable> T callWith(RequestScope scope,
                                                       ScopedValue.CallableOp<T, X> action) throws X;
    public static ScopedValue<RequestScope> carrier();                    // package-visible accessor
}

public record RequestScope(UUID tenantId, String correlationId, Map<String, Object> attributes) {
    public static RequestScope empty();
    public RequestScope with(String key, Object value);
    public <T> Optional<T> attribute(String key, Class<T> type);
}

@FunctionalInterface
public interface RequestScopeResolver {
    RequestScope resolve(ExerisServerRequest request);
}

@FunctionalInterface
public interface RequestScopeBinder {
    void bind(ExerisServerRequest request, Runnable action);
    static RequestScopeBinder noop();
    static RequestScopeBinder resolving(RequestScopeResolver resolver);
}

package eu.exeris.spring.runtime.web.scope.concurrent;

public final class ExerisStructuredScope<T, R> implements AutoCloseable {
    public static <T> ExerisStructuredScope<T, Void>    failFast();        // Joiner.awaitAllSuccessfulOrThrow()
    public static <T> ExerisStructuredScope<T, T>       firstSuccess();    // Joiner.anySuccessfulOrThrow()
    public static <T> ExerisStructuredScope<T, List<T>> allSuccessful();   // Joiner.allSuccessfulOrThrow()

    public <U extends T> StructuredTaskScope.Subtask<U> fork(Callable<? extends U> task);
    public R join() throws InterruptedException;
    @Override public void close();
    public Optional<RequestScope> capturedScope();
}
```

The wrapper preserves the `ScopedValue<RequestScope>` binding across forks transparently. Inside a `fork(...)` task, `ExerisRequestScope.tenantId()` returns the same value it returned in the enclosing scope without manual rebinding.

**Design notes:**

- **One class, two policies via factory methods (not sealed hierarchy).** JEP 525 final removed the nested-class style; `ExerisStructuredScope<T, R>` is parameterised on subtask type `T` and policy result type `R`, with three factory methods (`failFast()`, `firstSuccess()`, `allSuccessful()`) mapped to JDK `Joiner` factories. The two factories ADR-029 originally named (`shutdownOnFailure()` / `shutdownOnSuccess()`) are renamed to `failFast()` / `firstSuccess()` to match common-vocabulary naming and to avoid implying a deprecated JDK shape.
- **`fork(...)` returns `StructuredTaskScope.Subtask<U>` intentionally.** Wrapping the JDK `Subtask` type would add an allocation per fork without semantic value (the wrapper has nothing to add — `get()`, `state()`, `exception()` are the entire `Subtask` surface and they are correct as-is). The JDK type leak is honest about the implementation cost; documented here so a future reviewer asking "why not `ExerisStructuredScope.Subtask`?" finds the answer.
- **`join()` returns the policy result `R`** rather than `this`. This matches JEP 525 final's `StructuredTaskScope.join()` returning the `Joiner`'s `R` value. `failFast()`'s `join()` returns `Void` (callers read subtask state from retained `Subtask` handles); `firstSuccess()`'s `join()` returns the first successful value; `allSuccessful()`'s `join()` returns a `List<T>` of subtask values.
- **`joinUntil(Instant)` is not exposed on the wrapper.** JEP 525 final's per-scope timeout is configured via `StructuredTaskScope.open(Joiner, Configuration)` at construction time, not per-`join()` call. A future amendment can expose `open(Joiner, Duration)` overloads if downstream demand surfaces.
- **No explicit `throwIfFailed` / `result(mapper)`.** With `Joiner.awaitAllSuccessfulOrThrow()` the first failure surfaces from `join()` directly; with `Joiner.anySuccessfulOrThrow()` the value is `join()`'s return. Exception-mapping into kernel error-code conventions is the application's job at the `join()` call site — adding mapper overloads on the wrapper would obscure where the mapping happens.

## Consequences

### ✅ Positive Outcomes

- **[+] Kernel-independent — lands at 0.6.0-preview without waiting on kernel 0.8.0.** The work is pure JDK 26 preview-feature usage (`ScopedValue`, `StructuredTaskScope`). Spring-side authors can adopt the scope and fan-out API in the same train that closes Phase 4B (already in main).
- **[+] Replaces `ThreadLocal` cleanly.** Application authors migrating off `ThreadLocal<TenantId>` get a typed `ScopedValue` carrier without writing their own `ScopedValue.where(...).call(...)` plumbing at every fan-out site. The hot-path ban on `ThreadLocal` extends naturally.
- **[+] Operator-visible activation.** `exeris.runtime.context.scope.enabled` is a single property. An application opts in once; downstream reviewers see the property on the deployment manifest.
- **[+] Future 3B-β/γ work has a clean attachment point.** The `attribute(String, Class<T>)` API on `ExerisRequestScope` is the slot that 3B-β will bind `TraceContext` into when the kernel `TraceContext` slot ships (consolidated 1.0 GA roadmap Sprint 0.12, ~v0.12; not 0.8.0). The 3B-β PR adds an attribute, not a new runtime carrier.
- **[+] No new module surface to maintain.** The scope package lives inside `exeris-spring-runtime-web` (request-path-adjacent code). No new BOM entry, no new POM file, no new module-boundary test fixture.

### ⚠️ Trade-offs

- **[-] `StructuredTaskScope` is JDK 26 preview.** The wrapper inherits preview-feature instability. Mitigation: the kernel and the whole runtime are already on `--enable-preview` (per ADR-004); this is not a new constraint.
- **[-] Scope rebinding across `fork(...)` adds an allocation per fork.** A `ScopedValue.where(REQUEST_SCOPE, currentScope).call(task)` is the implementation primitive — a small closure allocation per fork. Quantified in the 0.6.0-preview baseline allocation report; mitigation if measured cost is meaningful is to specialize for the common case (single attribute, no carrier change).
- **[-] No structured concurrency timeout policy in 3B-α.** The wrapper supports `joinUntil(Instant)` but does not enforce a default request-wide timeout. Application owners configure timeouts per fan-out site. A future ADR may add a default timeout if downstream demand surfaces.
- **[-] Required-attribute helpers throw `IllegalStateException` outside an active scope.** Some readers will prefer `Optional`-only return shapes for consistency. The throwing variants (`requireTenantId()`) exist because the most common application pattern is "tenant-scoped business logic that should fail loud if no tenant is bound", and forcing `Optional.orElseThrow(...)` at every call site adds boilerplate without value. Each throwing helper is documented per method.

### 📋 What is NOT in scope

- **W3C `traceparent` ingress / egress.** Reading `traceparent` from inbound HTTP headers, propagating it to outbound HTTP-client calls, and bridging into a kernel `TraceContext` — all of that is 3B-β (ADR-031), kernel-gated on the kernel `TraceContext` slot (consolidated 1.0 GA roadmap Sprint 0.12, ~v0.12; not 0.8.0).
- **OTel span / metric emission.** Emitting OTel spans for request lifecycle and exporting via OTLP — that is 3B-γ (ADR-031), kernel-gated on `PrometheusOtlpTelemetrySink`.
- **Micrometer Tracing integration.** 3B-α does not depend on or wire Micrometer Tracing. If the downstream application already uses Micrometer Tracing, it continues to do so; this ADR does not regulate that.
- **`@RequestScope` Spring bean lifecycle integration.** Spring's `@RequestScope` is a `BeanFactory`-level scope tied to the legacy servlet request lifecycle; 3B-α does not bridge it. Applications using `@RequestScope` should consider whether `ExerisRequestScope` covers their use case directly; if they need `@RequestScope` specifically, they keep using it under Compatibility Mode.
- **`@SessionScope`, `@WebApplicationScope`, and other Spring web-scope beans.** Out of scope; same reasoning as `@RequestScope`.
- **Application-side `ThreadLocal` migration tooling.** Migrating an existing `ThreadLocal<TenantId>` to `ExerisRequestScope` is the application owner's concern. The Spring-side helpers do not generate migration shims.
- **Cross-process correlation-ID propagation over arbitrary transports.** 3B-α propagates inside a single JVM via `ScopedValue`. Cross-process correlation requires header propagation (3B-β/γ) and is out of 3B-α scope.

## Cross-references

- ADR-006 — Spring-Free Kernel Boundary (The Wall): `exeris-docs/adr/ADR-006-spring-free-kernel-boundary.md` — the parent ownership invariant; the `ThreadLocal`-on-hot-path ban in `CLAUDE.md` §"Pure Mode vs Compatibility Mode" is a narrative rule that this ADR turns into a per-package ArchUnit guard for the scope package (no existing test currently bans `ThreadLocal` at the type level — the new `RequestScopeArchitectureTest` is the first)
- ADR-010 — Host Runtime Model: `docs/adr/ADR-010-host-runtime-model.md` — the ownership boundary; `ExerisRequestScope` is a Spring-side affordance over the Exeris-owned request lifecycle
- ADR-011 — Pure Mode vs Compatibility Mode: `docs/adr/ADR-011-pure-mode-vs-compatibility-mode.md` — the scope package is Pure Mode; `@RequestScope` interop, if added, would be Compatibility Mode
- ADR-027 — Spring `ApplicationEventPublisher` / Exeris `EventBus` separation: `docs/adr/ADR-027-eventbus-applicationeventpublisher-boundary.md` — adjacent invariant; bus boundary stays unchanged
- ADR-031 (reserved) — Phase 3B-β/γ Scope (kernel-gated context propagation + OTel bridge): `docs/adr/ADR-031-…` (content pending; kernel-gated on the kernel W3C `traceparent`/`TraceContext` slot — consolidated 1.0 GA roadmap Sprint 0.12, ~v0.12, **not** 0.8.0 — plus a future `PrometheusOtlpTelemetrySink`)
- `docs/roadmap-1.0-trl9.md` — 0.6.0-preview train row anchors this ADR in the release plan
- JEP 446 (Scoped Values) — JDK preview feature backing the carrier
- JEP 462 (Structured Concurrency) — JDK preview feature backing `StructuredTaskScope`

## Engineering Protocol

The ADR is forward-looking — implementation lands in the 0.6.0-preview train. Four concrete deliverables:

1. **`ExerisRequestScope` + `ExerisStructuredScope` API package.** New package `eu.exeris.spring.runtime.web.scope[.concurrent]`. Hot-path-safe; covered by `RequestScopeArchitectureTest` (the new merge-blocking guard banning `ThreadLocal` and `org.springframework.web.context.request..` imports under `eu.exeris.spring.runtime.web.scope..`) and `ExerisStructuredScopeIntegrationTest` (tenant propagation across forks, tenant isolation across concurrent requests).
2. **Lifecycle wiring in `ExerisHttpDispatcher`.** When `exeris.runtime.context.scope.enabled=true`, the dispatcher binds the `ScopedValue<RequestScope>` around `HttpHandler.handle` invocation. Property read once at lifecycle start; runtime toggling unsupported.
3. **Autoconfig opt-in.** New `@ConfigurationProperties("exeris.runtime.context.scope")` record with `enabled` field; default `false`. `@ConditionalOnProperty` gating on the dispatcher binding so the disabled path is zero-cost.
4. **Phase 3 invariants reconciliation.** `docs/phases/phase-3-invariants.md` currently records 3B (request scope + tracing) as "deferred to 3.x" (lines 3 and 141 of that file at the time this ADR was drafted). The implementation PR for 3B-α must reconcile this by **one** of:
   - amending the relevant lines of `phase-3-invariants.md` with a forward-reference to this ADR ("3B-α graduated 2026-05-17 per ADR-029; see `phase-3b-alpha-invariants.md` for 3B-α-specific invariants"); or
   - creating a new `docs/phases/phase-3b-alpha-invariants.md` that supersedes the deferral row of `phase-3-invariants.md` (preferred — keeps the phase-3 closure record intact and adds the new phase boundary at its own file).
   Both forms must leave a trail from `phase-3-invariants.md` to the new artefact; a silent contradiction between the two files is not acceptable.

A future PR that introduces `ThreadLocal` field or static in `eu.exeris.spring.runtime.web.scope..` is an ADR-029-violating PR; the reviewer cites this ADR by number when blocking. A future PR that wires Micrometer Tracing, OTel, or W3C `traceparent` into the scope package is **not** an ADR-029 violation — it is an ADR-031 concern; the scope package is the attachment point, and ADR-031 governs what attaches there.
