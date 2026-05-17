# Phase 3 Invariants

**Status:** Locked-in (Phase 3 closed at sub-phases 3A + 3C-L2 + 3D; 3B originally deferred to 3.x — **graduated 2026-05-17 per ADR-029 (3B-α) and ADR-031 (3B-β/γ, reserved); see `phase-3b-alpha-invariants.md` for 3B-α-specific invariants**)
**Source of authority:** ADR-006 (The Wall), ADR-010 (Host Runtime Model),
ADR-011 (Pure vs Compatibility Mode), [ADR-017](../adr/ADR-017-jdbc-compact-scope.md)
(JDBC Compatibility Scope for ExerisDataSource), and the master plan
[`phase-3-tx-persistence.md`](phase-3-tx-persistence.md). This page enumerates the
non-negotiable invariants Phase 3 established for the transaction bridge and JDBC
compatibility adapter.

A change that breaks any item below is an architectural regression, not a style
issue, and requires a superseding ADR — not a workaround in code.

Phase 0 ([`phase-0-invariants.md`](phase-0-invariants.md)), Phase 1
([`phase-1-invariants.md`](phase-1-invariants.md)), and Phase 2
([`phase-2-invariants.md`](phase-2-invariants.md)) invariants still apply in full;
Phase 3 invariants are additive and tx + data specific.

---

## 1. Transaction modules are opt-in and never default

`ExerisTransactionAutoConfiguration` activates only when
`exeris.runtime.tx.enabled=true`. `ExerisDataAutoConfiguration` activates only when
`exeris.runtime.data.compat-datasource.enabled=true`. Both default to `false`. There
is no `matchIfMissing=true` on either gate.

When disabled, the modules compile in but no beans are created — zero runtime overhead
on the application's hot paths, zero classpath risk for apps that do not need
transactions or the JDBC bridge.

- **Guards:** `ExerisTransactionAutoConfigurationTest` and `ExerisDataAutoConfigurationTest`
  verify activation only when the property is present and equals `true`.

## 2. `PersistenceConnection` lives in Spring's transaction infrastructure, not in a separate `ScopedValue`

The active `PersistenceConnection` is carried inside `ExerisTransactionObject`, stored
by Spring's `AbstractPlatformTransactionManager` inside `DefaultTransactionStatus`. It
is **not** duplicated into a `ScopedValue` slot.

Rationale: a hybrid `ScopedValue` + Spring `TransactionSynchronizationManager` dual
binding (originally proposed in the Phase 3 plan) would have introduced a second source
of truth for the active connection — an entire category of dual-binding bugs. Connection
lifetime is bounded by the Spring transaction boundary; that boundary is the single
source of truth.

This is a deliberate divergence from the original Phase 3A plan. It is documented as
the implemented contract.

## 3. `KernelProviders.PERSISTENCE_ENGINE` is read at transaction-begin time, not at bean construction

`ExerisPlatformTransactionManager.doBegin(...)` reads `KernelProviders.PERSISTENCE_ENGINE`
each time a transaction starts. The engine reference is **never** captured as a static
singleton at bean construction.

This preserves the kernel `ScopedValue` contract — the engine is always read from the
current kernel VT scope, so the bridge respects engine-per-scope semantics rather than
freezing one engine at startup.

- **Guard:** code review; `ExerisTransactionScaffoldTest` exercises the deferred
  resolution path through `PersistenceEngineProvider`.

## 4. Propagation matrix is fixed (and any change requires an ADR)

| Spring propagation | Status | Notes |
|:---|:---|:---|
| `REQUIRED` | ✅ | Default; joins existing or starts new |
| `REQUIRES_NEW` | ✅ | Always opens a second independent connection |
| `MANDATORY` | ✅ | Throws if no active transaction |
| `SUPPORTS` | ✅ | Joins if present, runs without otherwise |
| `NEVER` | ✅ | Throws if active transaction present |
| `NESTED` | ❌ | `UnsupportedOperationException` (kernel savepoint API not exposed) |
| `NOT_SUPPORTED` | ❌ | `UnsupportedOperationException` (would require connection suspension) |

Adding support for `NESTED` or `NOT_SUPPORTED` requires a new ADR that documents the
kernel API surface relied on (savepoints, suspension) and the connection-lifecycle
implications.

- **Guards:** `ExerisPlatformTransactionManagerTest` covers all five supported
  propagation rules + the two unsupported error paths.

## 5. JDBC adapter classes must reside in `*.data.compat.*`

Per ADR-017 §7 Rule 1: any class implementing `java.sql.Connection`,
`PreparedStatement`, or `ResultSet` must live in
`eu.exeris.spring.runtime.data.compat.*`. JDBC types cannot leak into the Pure Mode
path or into other modules.

- **Guard:** `DataModuleBoundaryTest#jdbcAdapterClasses_mustResideInCompatPackage`
  (ArchUnit, enforced).

## 6. Level 1 (native repositories) is application code, not infrastructure

The recommended persistence path — application repositories implemented directly
against `PersistenceEngine` and `QueryExecutor` — does not require any infrastructure
class shipped from `exeris-spring-runtime-data`. The `data` module ships Level 2
(JDBC compat) only.

`PersistenceEngineProvider` (in the `tx` module) is the hand-off point: a deferred
`ScopedValue` accessor that application repositories use to obtain the engine at call
time. This deliberately shifts the recommended persistence pattern out of "framework
infrastructure" and into "small, owned application code".

## 7. Connection sharing under `@Transactional` is one-per-transaction

When `ExerisPlatformTransactionManager` starts a transaction, it calls
`ExerisDataSource.bindTransactionConnection(...)` via the registered
`ExerisJdbcResourceCallback`. Subsequent `getConnection()` calls within the same
transaction return the already-bound `ExerisConnectionProxy`.

Outside a Spring-managed transaction, each `getConnection()` opens a new connection.

This is the rule that makes JPA / Hibernate compatible with the bridge without
reinventing connection pooling — connection lifecycle is owned by the transaction
scope, not by an internal pool.

## 8. HikariCP and other JDBC connection pools are banned on the runtime classpath

`com.zaxxer:HikariCP` is in the Pure-Mode classpath ban list (Phase 0 invariant) and
remains banned with the `data` module activated. The kernel's `PersistenceProvider`
owns connection pooling at its layer; layering a second pool on top would invert
ownership.

- **Guard:** `PureModeClasspathGuardTest` × 5 modules (autoconfigure / web / tx /
  data / actuator) — all enforce no HikariCP, no Tomcat / Jetty / Undertow embed,
  no Netty, no Reactor, no servlet API, no `DispatcherServlet`.

## 9. `server.port` fallback is read-only compatibility, not Spring ownership inversion

`ExerisSpringConfigProvider.kernelSettings()` reads `server.port` as a fallback when
`exeris.runtime.network.port` is unset. This is a **read-only** value lookup —
Exeris remains the runtime owner of the bound socket; the kernel's
`HttpServerEngine` does the actual binding. Spring Boot's convention of setting
`server.port` is honored for configuration ergonomics, not as a delegation of
ingress ownership.

If Spring Boot were ever to add semantics around `server.port` that imply ownership
(servlet container start, port reservation), the fallback must be removed — not the
servlet container added.

## 10. Phase 3B graduated from "deferred to 3.x" to 1.0 preview on 2026-05-17

Phase 3B in the original plan covered three areas: request scope, security context,
observability tracing. The 2026-05-17 downstream observability review reversed the
"deferred to 3.x" deferral and split 3B into kernel-independent and kernel-gated halves:

- **Phase 3B-α — Request Scope + Structured Concurrency (kernel-independent, ADR-029).**
  Lands at 0.6.0-preview. Delivers `ExerisRequestScope` (`ScopedValue<RequestScope>`-backed
  facade with typed `tenantId()` / `correlationId()` / `attribute(key, type)` accessors
  and `require*` variants), `ExerisStructuredScope` (JDK 26 `StructuredTaskScope` wrapper
  that propagates the bound scope across forked virtual threads), and the
  `RequestScopeBinder` / `RequestScopeResolver` extension points. Default-off via
  `exeris.runtime.context.scope.enabled`. **3B-α-specific invariants live in
  `phase-3b-alpha-invariants.md` (created alongside the implementation PR);** the present
  doc preserves the Phase-3-closure record and points forward.
- **Phase 3B-β — W3C `traceparent` context propagation (kernel-gated, ADR-031).**
  Targets 0.9.0-preview, gated on `exeris-kernel` 0.8.0 Sprint 0.12 shipping the kernel
  `TraceContext` carrier via `ScopedValue`.
- **Phase 3B-γ — OTel span / metric emission via OTLP (kernel-gated, ADR-031).**
  Gated on a future kernel `PrometheusOtlpTelemetrySink`; may slip to 1.0.x.
- **Security context** remains as before — partially delivered through Phase 2c
  (`ExerisSecurityContextFilter` in `web/compat/filter`) for `@RestController` flows.
  Not part of the 3B-α/β/γ graduation; ADR-029 does NOT regulate it.

The previous deferral text on this row read: "Adding partial / speculative bridges before
downstream demand is explicitly forbidden by this invariant." That principle still holds;
the 2026-05-17 graduation was driven by concrete in-flight downstream demand for
3B-α (tenant isolation + structured concurrency across kernel calls), which makes the
"partial bridge" concern moot for that scope. 3B-β/γ remain gated on kernel work
precisely so they don't ship as speculative bridges.

## 11. When opted in, the Exeris JDBC adapter wins over Spring Boot's `DataSourceAutoConfiguration`

This invariant is additive to invariant #1 (opt-in activation). It governs what happens
**after** the application sets `exeris.runtime.data.compat-datasource.enabled=true`.

`ExerisDataAutoConfiguration` must:

- Declare
  `@AutoConfiguration(beforeName = "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration")`
  so the Exeris adapter is evaluated and its bean is registered before Spring Boot's
  default `DataSource` autoconfiguration runs.
- Register `ExerisDataSource` with `@ConditionalOnMissingBean(DataSource.class)` so any
  application-supplied `DataSource` bean takes precedence — the property opt-in
  expresses intent; an explicit user bean expresses stronger intent.
- Mark the `exerisDataSource` bean `@Primary` as belt-and-braces against unusual wiring
  orders where two `DataSource` beans co-reside.

Rationale: the opt-in property is a runtime-ownership claim. Without explicit ordering,
Spring Boot's `DataSourceAutoConfiguration` could land a Hikari-backed `DataSource`
ahead of the Exeris adapter — and the resulting application would have "opted into the
Exeris bridge" while in fact being served by Hikari. That inverts the ownership model
the opt-in claims to establish.

The `beforeName` string form is required because `spring-jdbc` is intentionally absent
from this module's compile classpath (ADR-017 §4.3); a class-literal reference would
not load.

Downstream migration review surfaced the precedence ambiguity in 0.5.0-SNAPSHOT, where
opting in did not in fact guarantee Exeris-owned `DataSource` precedence over Spring
Boot's default. This invariant locks in the fix and is the precedence contract callers
can rely on.

- **Guards:** `ExerisDataAutoConfigurationTest` —
  `autoConfigurationDeclaresOrderingBeforeDataSourceAutoConfiguration`,
  `exerisDataSourceBeanIsMarkedPrimary`,
  `exerisAdapterStandsDownWhenUserProvidesOwnDataSource`.

---

## How invariants are enforced

| Invariant | Primary guard |
|:---|:---|
| Opt-in activation, never default | `ExerisTransactionAutoConfigurationTest`, `ExerisDataAutoConfigurationTest` |
| Connection lives in Spring's transaction infrastructure | Code review + `ExerisTransactionScaffoldTest` (no `ScopedValue.where(...)` for the connection) |
| `PERSISTENCE_ENGINE` resolved per call | `ExerisTransactionScaffoldTest`, `ExerisPlatformTransactionManagerTest` |
| Propagation matrix fixed | `ExerisPlatformTransactionManagerTest` (5 supported + 2 unsupported error paths) |
| JDBC adapters confined to `*.data.compat.*` | `DataModuleBoundaryTest#jdbcAdapterClasses_mustResideInCompatPackage` |
| Level 1 is app code | Documented in this file + master Phase 3 doc; no infrastructure classes shipped |
| One connection per transaction | `ExerisDataSourceTest`, `ExerisConnectionProxyTest` |
| HikariCP banned | `PureModeClasspathGuardTest` × 5 modules |
| `server.port` is read-only fallback | `ExerisBootstrapIntegrationTest` exercises both `exeris.runtime.network.port` and `server.port` paths |
| Phase 3B graduated 2026-05-17 — 3B-α at 0.6.0-preview (ADR-029); 3B-β/γ kernel-gated (ADR-031) | `phase-3b-alpha-invariants.md` carries 3B-α invariants; the 2026-05-17 graduation is documented above (invariant #10) |
| Exeris adapter wins over Spring Boot `DataSourceAutoConfiguration` when opted in | `ExerisDataAutoConfigurationTest` — three cases covering the annotation declaration, the `@Primary` marker, and stand-down on user-supplied `DataSource` (see invariant #11 for the named test methods) |

These tests must stay green. A failure indicates a real architectural regression;
the test is not the bug.
