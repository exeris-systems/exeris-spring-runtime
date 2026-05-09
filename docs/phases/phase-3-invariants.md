# Phase 3 Invariants

**Status:** Locked-in (Phase 3 closed at sub-phases 3A + 3C-L2 + 3D; 3B explicitly deferred to 3.x)
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

## 10. Phase 3B (request scope, tracing) is explicitly deferred — no half-implementation

Phase 3B in the original plan covered three areas: request scope, security context,
observability tracing. Of these:

- **Security context** is partially delivered through Phase 2c
  (`ExerisSecurityContextFilter` in `web/compat/filter`) for `@RestController` flows.
- **Request scope** is **not** implemented. Spring `@RequestScope`-annotated beans
  are not supported on the Exeris path.
- **Observability tracing** is **not** implemented. No Micrometer Tracing dependency
  is wired.

Adding partial / speculative bridges before downstream demand is explicitly forbidden
by this invariant. Phase 3.x lands the missing pieces with concrete shape driven by
real requirements; until then, the absence is documented, not papered over.

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
| Phase 3B deferred, no half-impl | Documented in this file + master Phase 3 doc; absence is the contract |

These tests must stay green. A failure indicates a real architectural regression;
the test is not the bug.
