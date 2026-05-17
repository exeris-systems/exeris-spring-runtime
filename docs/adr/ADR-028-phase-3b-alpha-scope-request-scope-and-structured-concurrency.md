# ADR-028: Phase 3B-α Scope — Request Scope and Structured Concurrency Helpers (Kernel-Independent)

| Attribute       | Value                                                                                                                                                                                                                                                                                                              |
|:----------------|:------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **Status**      | **ACCEPTED** (drafted and accepted 2026-05-17; single decider — no future gating event; ratified by the PR that introduces this file)                                                                                                                                                                              |
| **Deciders**    | Arkadiusz Przychocki                                                                                                                                                                                                                                                                                               |
| **Date**        | 2026-05-17                                                                                                                                                                                                                                                                                                         |
| **Scope**       | spring/lifecycle (extends `exeris-spring-runtime-web` request lifecycle and `exeris-spring-boot-autoconfigure` lifecycle wiring; no new top-level module)                                                                                                                                                          |
| **Owning Repo** | `exeris-spring-runtime`                                                                                                                                                                                                                                                                                            |
| **Driven By**   | Phase 3 closure (2026-05-09) deferred 3B (request scope + tracing) to 3.x; downstream observability demand identified 2026-05-17 graduated 3B back into the 1.0 train. Kernel-gating analysis (see [[adr-030-phase-3b-beta-gamma-scope]]) showed the work splits cleanly into a kernel-independent half (this ADR) and a kernel-gated half (ADR-030). |
| **Compliance**  | [Roadmap to 1.0 and TRL-9](../roadmap-1.0-trl9.md), [Module Boundaries](../architecture/module-boundaries.md), [Phase 3 Invariants](../phases/phase-3-invariants.md)                                                                                                                                                |

## Context and Problem Statement

Phase 3B was originally three concerns folded into one row in the roadmap: (a) request scope propagation for tenant/correlation IDs, (b) structured concurrency helpers for kernel-call fan-out, (c) distributed-tracing context propagation and emission. Phase 3 closure (2026-05-09) deferred all three to "3.x" — the reasoning was that the request-path lifecycle was settling, and adding cross-cutting context propagation early would conflict with Phase 1/2 ingress finalization.

A 2026-05-17 downstream observability review changed two facts:

1. **In-flight migration demand for (a) and (b).** A downstream service migration identified that tenant isolation and correlation-ID propagation need to be load-bearing during the migration, not after — the application uses `StructuredTaskScope` to fan out kernel calls (persistence + event publication + downstream HTTP) and the tenant ID must propagate without `ThreadLocal` (banned on hot paths by `WallIntegrityTest`).
2. **Kernel-gating distinction surfaced for (c).** `exeris-kernel/docs/v0.8-sprint-and-implementation-map.md` Sprint 0.12 commits to W3C `traceparent` + `TraceContext` carrier via `ScopedValue`; the OTLP sink path (`PrometheusOtlpTelemetrySink`) is in the kernel v0.8/v0.9 gap section without sprint commitment. Distributed tracing therefore depends on kernel work that has a known wait period (for β) and an uncertain wait period (for γ).

The two facts together point at a split:

- **3B-α** — request scope + structured concurrency helpers, **kernel-independent** (pure JDK 26 preview features: `ScopedValue`, `StructuredTaskScope`). Can land at 0.6.0-preview without waiting on the kernel. **This ADR.**
- **3B-β/γ** — context propagation bridge + OTel emission, **kernel-gated** (waits on `exeris-kernel` 0.8.0 Sprint 0.12 for β, on a future `PrometheusOtlpTelemetrySink` for γ). ADR-030.

This ADR scopes **3B-α only**. ADR-030 scopes 3B-β/γ.

This ADR answers: **what does Phase 3B-α deliver in 0.6.0-preview, and what stays out of scope to keep it kernel-independent?**

## 🏁 The Decision

**Phase 3B-α delivers, in 0.6.0-preview, a `ScopedValue`-backed request scope and a `StructuredTaskScope`-based fan-out helper API in `exeris-spring-runtime-web` and `exeris-spring-boot-autoconfigure`. No new module. No kernel dependencies beyond what Phase 1 already requires. No `ThreadLocal` on hot paths. No tracing emission — that is ADR-030.**

The Spring-side surface reads, in normalized form: *"An Exeris-hosted Spring application can scope tenant ID, correlation ID, and request-bound attributes to the request lifecycle via `ExerisRequestScope`, fan out to multiple kernel calls via `ExerisStructuredScope`, and rely on the runtime to propagate the scope across forks without `ThreadLocal`."*

**Concrete obligations:**

1. **`ExerisRequestScope` API — `ScopedValue`-backed.** A new package `eu.exeris.spring.runtime.web.scope` ships `ExerisRequestScope` with a small typed API: `ExerisRequestScope.tenantId()`, `correlationId()`, `attribute(String key, Class<T> type)`. Implementation is a single `ScopedValue<RequestScope>` bound around `HttpHandler.handle` invocation when `exeris.runtime.context.scope.enabled=true`. No `ThreadLocal` fallback; `ScopedValue` is the only carrier. Access outside an active scope returns `Optional.empty()` (or throws `IllegalStateException` for required-attribute helpers, with the choice documented per method).
2. **`ExerisStructuredScope` fan-out API.** A new package `eu.exeris.spring.runtime.web.scope.concurrent` ships `ExerisStructuredScope` — a thin wrapper around `StructuredTaskScope` that captures the current `ExerisRequestScope` and rebinds it inside each forked virtual thread. The wrapper exists to (a) preserve the `ScopedValue` binding across `StructuredTaskScope.ShutdownOnFailure` / `ShutdownOnSuccess` forks without manual `ScopedValue.where(...).call(...)` boilerplate at every call site, and (b) ensure failures inside forks propagate as `IOException` / configured-exception types consistently with the kernel error-code conventions.
3. **Opt-in via `exeris.runtime.context.scope.enabled` (default `false`).** Activation must be explicit — applications that do not opt in pay zero cost. When disabled, `ExerisRequestScope.current()` returns `Optional.empty()` always and `ExerisStructuredScope` falls through to plain `StructuredTaskScope` without scope rebinding. The property is read at lifecycle start; runtime toggling is not supported.
4. **No `ThreadLocal` on hot paths.** `WallIntegrityTest` (existing) already bans `ThreadLocal` imports in `exeris-spring-runtime-web/.../web/..`. The new scope package extends the ban: `RequestScopeArchitectureTest#scopePackageMustNotUseThreadLocal` enforces zero `ThreadLocal` field or static references in `eu.exeris.spring.runtime.web.scope..`.
5. **No tracing emission, no OTel bridge, no W3C `traceparent` ingress.** Those belong to 3B-β (ADR-030) and 3B-γ (ADR-030). 3B-α leaves a small `attribute(String, Class<T>)` API on `ExerisRequestScope` that 3B-β/γ will later use to bind `TraceContext` — but the 3B-α package itself does **not** import any tracing types, does **not** read W3C `traceparent` headers, and does **not** depend on Micrometer Tracing, OpenTelemetry API, or any kernel telemetry SPI beyond `TelemetrySink` (already a Phase 1 dependency).
6. **Tenant isolation across forks is verified.** `ExerisStructuredScopeIntegrationTest#tenantIdPropagatesAcrossForks` and `#tenantIdIsolatesPerOutermostRequest` are merge-blocking. The first asserts that a `tenantId` set in the outer request scope is readable from inside `fork(...)` callbacks; the second asserts that two concurrent requests with different `tenantId` values never see each other's scope.
7. **No application-side `ThreadLocal`-to-`ScopedValue` migration tooling.** Application owners migrating off existing `ThreadLocal<TenantId>` patterns are responsible for the migration. This ADR scopes the runtime affordance, not the migration path.

## API at a glance

```java
package eu.exeris.spring.runtime.web.scope;

public final class ExerisRequestScope {
    public static Optional<UUID> tenantId();
    public static Optional<String> correlationId();
    public static <T> Optional<T> attribute(String key, Class<T> type);
    public static UUID requireTenantId();        // throws IllegalStateException if absent
}

package eu.exeris.spring.runtime.web.scope.concurrent;

public final class ExerisStructuredScope implements AutoCloseable {
    public static ExerisStructuredScope.ShutdownOnFailure shutdownOnFailure();
    public static ExerisStructuredScope.ShutdownOnSuccess<T> shutdownOnSuccess();

    public <T> StructuredTaskScope.Subtask<T> fork(Callable<? extends T> task);
    public ExerisStructuredScope join() throws InterruptedException;
    public ExerisStructuredScope joinUntil(Instant deadline) throws InterruptedException;
    @Override public void close();
}
```

The wrapper preserves the `ScopedValue<RequestScope>` binding across forks transparently. Inside a `fork(...)` task, `ExerisRequestScope.tenantId()` returns the same value it returned in the enclosing scope without manual rebinding.

## Consequences

### ✅ Positive Outcomes

- **[+] Kernel-independent — lands at 0.6.0-preview without waiting on kernel 0.8.0.** The work is pure JDK 26 preview-feature usage (`ScopedValue`, `StructuredTaskScope`). Spring-side authors can adopt the scope and fan-out API in the same train that closes Phase 4B (already in main).
- **[+] Replaces `ThreadLocal` cleanly.** Application authors migrating off `ThreadLocal<TenantId>` get a typed `ScopedValue` carrier without writing their own `ScopedValue.where(...).call(...)` plumbing at every fan-out site. The hot-path ban on `ThreadLocal` extends naturally.
- **[+] Operator-visible activation.** `exeris.runtime.context.scope.enabled` is a single property. An application opts in once; downstream reviewers see the property on the deployment manifest.
- **[+] Future 3B-β/γ work has a clean attachment point.** The `attribute(String, Class<T>)` API on `ExerisRequestScope` is the slot that 3B-β will bind `TraceContext` into when kernel 0.8.0 ships. The 3B-β PR adds an attribute, not a new runtime carrier.
- **[+] No new module surface to maintain.** The scope package lives inside `exeris-spring-runtime-web` (request-path-adjacent code). No new BOM entry, no new POM file, no new module-boundary test fixture.

### ⚠️ Trade-offs

- **[-] `StructuredTaskScope` is JDK 26 preview.** The wrapper inherits preview-feature instability. Mitigation: the kernel and the whole runtime are already on `--enable-preview` (per ADR-004); this is not a new constraint.
- **[-] Scope rebinding across `fork(...)` adds an allocation per fork.** A `ScopedValue.where(REQUEST_SCOPE, currentScope).call(task)` is the implementation primitive — a small closure allocation per fork. Quantified in the 0.6.0-preview baseline allocation report; mitigation if measured cost is meaningful is to specialize for the common case (single attribute, no carrier change).
- **[-] No structured concurrency timeout policy in 3B-α.** The wrapper supports `joinUntil(Instant)` but does not enforce a default request-wide timeout. Application owners configure timeouts per fan-out site. A future ADR may add a default timeout if downstream demand surfaces.
- **[-] Required-attribute helpers throw `IllegalStateException` outside an active scope.** Some readers will prefer `Optional`-only return shapes for consistency. The throwing variants (`requireTenantId()`) exist because the most common application pattern is "tenant-scoped business logic that should fail loud if no tenant is bound", and forcing `Optional.orElseThrow(...)` at every call site adds boilerplate without value. Each throwing helper is documented per method.

### 📋 What is NOT in scope

- **W3C `traceparent` ingress / egress.** Reading `traceparent` from inbound HTTP headers, propagating it to outbound HTTP-client calls, and bridging into a kernel `TraceContext` — all of that is 3B-β (ADR-030), kernel-gated on `exeris-kernel` 0.8.0 Sprint 0.12.
- **OTel span / metric emission.** Emitting OTel spans for request lifecycle and exporting via OTLP — that is 3B-γ (ADR-030), kernel-gated on `PrometheusOtlpTelemetrySink`.
- **Micrometer Tracing integration.** 3B-α does not depend on or wire Micrometer Tracing. If the downstream application already uses Micrometer Tracing, it continues to do so; this ADR does not regulate that.
- **`@RequestScope` Spring bean lifecycle integration.** Spring's `@RequestScope` is a `BeanFactory`-level scope tied to the legacy servlet request lifecycle; 3B-α does not bridge it. Applications using `@RequestScope` should consider whether `ExerisRequestScope` covers their use case directly; if they need `@RequestScope` specifically, they keep using it under Compatibility Mode.
- **`@SessionScope`, `@WebApplicationScope`, and other Spring web-scope beans.** Out of scope; same reasoning as `@RequestScope`.
- **Application-side `ThreadLocal` migration tooling.** Migrating an existing `ThreadLocal<TenantId>` to `ExerisRequestScope` is the application owner's concern. The Spring-side helpers do not generate migration shims.
- **Cross-process correlation-ID propagation over arbitrary transports.** 3B-α propagates inside a single JVM via `ScopedValue`. Cross-process correlation requires header propagation (3B-β/γ) and is out of 3B-α scope.

## Cross-references

- ADR-006 — Spring-Free Kernel Boundary (The Wall): `exeris-docs/adr/ADR-006-spring-free-kernel-boundary.md` — the `ThreadLocal`-on-hot-path ban that this ADR extends to the scope package
- ADR-010 — Host Runtime Model: `docs/adr/ADR-010-host-runtime-model.md` — the ownership boundary; `ExerisRequestScope` is a Spring-side affordance over the Exeris-owned request lifecycle
- ADR-011 — Pure Mode vs Compatibility Mode: `docs/adr/ADR-011-pure-mode-vs-compatibility-mode.md` — the scope package is Pure Mode; `@RequestScope` interop, if added, would be Compatibility Mode
- ADR-026 — Spring `ApplicationEventPublisher` / Exeris `EventBus` separation: `docs/adr/ADR-026-eventbus-applicationeventpublisher-boundary.md` — adjacent invariant; bus boundary stays unchanged
- ADR-030 (reserved) — Phase 3B-β/γ Scope (kernel-gated context propagation + OTel bridge): `docs/adr/ADR-030-…` (content pending; kernel-gated on `exeris-kernel` 0.8.0 W3C `traceparent` + future `PrometheusOtlpTelemetrySink`)
- `docs/roadmap-1.0-trl9.md` — 0.6.0-preview train row anchors this ADR in the release plan
- JEP 446 (Scoped Values) — JDK preview feature backing the carrier
- JEP 462 (Structured Concurrency) — JDK preview feature backing `StructuredTaskScope`

## Engineering Protocol

The ADR is forward-looking — implementation lands in the 0.6.0-preview train. Three concrete deliverables:

1. **`ExerisRequestScope` + `ExerisStructuredScope` API package.** New package `eu.exeris.spring.runtime.web.scope[.concurrent]`. Hot-path-safe; covered by `RequestScopeArchitectureTest` (no `ThreadLocal`, no `org.springframework.web.context.request..` imports) and `ExerisStructuredScopeIntegrationTest` (tenant propagation across forks, tenant isolation across concurrent requests).
2. **Lifecycle wiring in `ExerisHttpDispatcher`.** When `exeris.runtime.context.scope.enabled=true`, the dispatcher binds the `ScopedValue<RequestScope>` around `HttpHandler.handle` invocation. Property read once at lifecycle start; runtime toggling unsupported.
3. **Autoconfig opt-in.** New `@ConfigurationProperties("exeris.runtime.context.scope")` record with `enabled` field; default `false`. `@ConditionalOnProperty` gating on the dispatcher binding so the disabled path is zero-cost.

A future PR that introduces `ThreadLocal` field or static in `eu.exeris.spring.runtime.web.scope..` is an ADR-028-violating PR; the reviewer cites this ADR by number when blocking. A future PR that wires Micrometer Tracing, OTel, or W3C `traceparent` into the scope package is **not** an ADR-028 violation — it is an ADR-030 concern; the scope package is the attachment point, and ADR-030 governs what attaches there.
