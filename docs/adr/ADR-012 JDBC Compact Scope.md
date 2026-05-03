# ADR-012: JDBC Compatibility Scope for ExerisDataSource

| Attribute    | Value                                                                                                |
|:-------------|:-----------------------------------------------------------------------------------------------------|
| **Status**   | Accepted                                                                                             |
| **Date**     | 2026-04-07                                                                                           |
| **Authors**  | Arkadiusz Przychocki                                                                                 |
| **Related**  | ADR-010 (Host Runtime Model), `docs/architecture/module-boundaries.md`, `docs/phases/phase-3-tx-persistence.md` |
| **Repo**     | `exeris-spring-runtime`                                                                              |

---

## 1. Context

`exeris-spring-runtime-data` exists as the optional, high-risk persistence integration
module. It currently contains `ExerisDataSource` as a scaffold stub in
`eu.exeris.spring.runtime.data.compat` (all methods throw `UnsupportedOperationException`).

`docs/architecture/module-boundaries.md` requires every public class added to this module
to reference the ADR or Phase 3 plan entry that justifies its existence. No
`java.sql.Connection` adapter implementation may land before that ADR is written.

This ADR fulfills that precondition.

### Background

JPA/Hibernate and Spring Data JPA require a `javax.sql.DataSource` to build a
`SessionFactory` or `EntityManagerFactory`. The standard connection acquisition path
for these frameworks is `DataSource.getConnection()`, which returns a `java.sql.Connection`.

Exeris, as the host runtime, owns the persistence connection lifecycle through
`PersistenceEngine.openConnection()` (SPI seam: `KernelProviders.PERSISTENCE_ENGINE`).
These two ownership models are in direct structural tension.

Phase 3 of the delivery plan (3C — Persistence Integration) defines two levels:

- **Level 1 — Exeris Native Repositories (recommended):** application code calls
  `PersistenceEngine` / `QueryExecutor` directly. No `DataSource`. No JPA.
- **Level 2 — JDBC Compatibility Bridge (requires ADR approval):** a `DataSource` adapter
  backed by `PersistenceEngine.openConnection()` for frameworks that cannot target Level 1.

This ADR governs Level 2. It does not change or weaken the status of Level 1 as the
recommended path.

---

## 2. Problem Statement

The following constraint set defines the problem:

1. **JPA/Hibernate require `DataSource.getConnection()`.**  
   There is no supported path to plug an arbitrary connection model into JPA without
   implementing `javax.sql.DataSource`.

2. **`DataSource.getConnection()` as a naive primary entry point inverts runtime ownership.**  
   If every persistence call flows through `DataSource.getConnection()` → `Connection.prepareStatement()`
   with Exeris acting only as a pool behind those calls, then JDBC gravity — not Exeris — determines
   connection lifecycle. This directly contradicts the ownership model established in ADR-010, §3.1.

3. **`java.sql.Connection` is a wide contract.**  
   `java.sql.Connection` exposes approximately 200 method signatures. JPA/Hibernate requires a
   subset of roughly 20–30 of those. Full JDBC 4.3 implementation is not the goal; scoped
   emulation for the JPA/Hibernate use case is.

4. **No external pool (HikariCP) may own connection lifecycle.**  
   `module-boundaries.md` explicitly bans HikariCP from `exeris-spring-runtime-data`.
   Connection pooling at the `DataSource` level is Exeris' concern, not a third-party pool's.

5. **Transaction boundaries must remain with `ExerisPlatformTransactionManager`.**  
   The existing `exeris-spring-runtime-tx` design binds `PersistenceConnection` to transaction
   scope. The JDBC adapter must not open or close connections independent of transaction lifecycle.

---

## 3. Options Considered

### Option A — No JDBC Bridge

**DEFERRED (pure-mode default)**

JPA/Hibernate and Spring Data JPA are not supported in Exeris host-runtime mode.
Applications that require JPA use the kernel's native query model (`QueryExecutor`)
via Level 1 repositories.

**Rationale for deferral, not selection as sole decision:**

- This is already the canonical recommendation. `PERSISTENCE_ENGINE` is available
  via `KernelProviders.PERSISTENCE_ENGINE` and `QueryExecutor`. There is no missing seam.
- However, the JPA/Hibernate ecosystem is too large to dismiss without an explicit bridge
  option. Teams migrating from Spring Data JPA will encounter immediate adoption friction
  with no migration path.

**Consequences if this were the only option:**

- No `ExerisDataSource` implementation. Scaffold stub remains permanently.
- `exeris-spring-runtime-data` is effectively dormant until a native Spring Data
  `Repository` implementation is written against `PersistenceEngine` (Phase 4+).
- Reduces architectural risk to zero for the JDBC gravity well problem.
- Significantly limits adoption by teams with existing JPA codebases.

**Pure-mode recommendation:** Teams building greenfield services on Exeris should
treat Option A as the default and avoid JPA entirely.

---

### Option B — `ExerisDataSource` as a Scoped `DataSource` Adapter

**ACCEPTED — with explicit constraints defined in §4 and §5**

`ExerisDataSource` implements `javax.sql.DataSource`. `getConnection()` delegates to
`ExerisPlatformTransactionManager`'s active transaction state:

- If a transaction managed by `ExerisPlatformTransactionManager` is active: the
  `PersistenceConnection`-backed `Connection` adapter already bound via
  `TransactionSynchronizationManager.getResource(ExerisDataSource.class)` is returned
  (connection reuse — not a second open).
- If no transaction is active: a new connection is opened via
  `PersistenceEngine.openConnection()` and a `ExerisConnectionAdapter` wrapping that
  connection is returned. The caller is responsible for closing it (resource-per-call
  semantics, no pooling at the `DataSource` layer).

`ExerisDataSource.getConnection()` delegates to `ExerisPlatformTransactionManager`'s active
transaction state (described in §5). The connection returned to JPA/Hibernate is a thin proxy
wrapping the **real `java.sql.Connection`** obtained by casting the `PersistenceConnection` to
`JdbcPersistenceConnection` and calling `.rawJdbcConnection()`.

This means the real JDBC driver (pgjdbc, H2, etc.) handles all `PreparedStatement`, `ResultSet`,
metadata, and type handling. `ExerisDataSource` does **not** re-implement the JDBC surface.

The proxy (`ExerisConnectionProxy`) intercepts only three lifecycle concerns:

| Method          | Behaviour in managed tx scope                         | Behaviour outside tx scope              |
|:----------------|:------------------------------------------------------|:----------------------------------------|
| `close()`       | No-op — close managed by `ExerisPlatformTransactionManager` | Delegates to `PersistenceConnection.close()` |
| `commit()`      | No-op — commit managed by `ExerisPlatformTransactionManager` | Delegates to real connection `commit()` |
| `rollback()`    | No-op — rollback managed by `ExerisPlatformTransactionManager` | Delegates to real connection `rollback()` |

All other `java.sql.Connection` method calls pass through directly to the real JDBC connection.

**Fail-fast for non-JDBC engines:**

If `PersistenceEngine.openConnection()` returns a connection that is not a
`JdbcPersistenceConnection` (i.e., the Enterprise engine is bound), `ExerisDataSource.getConnection()`
must throw immediately:

```
throw new UnsupportedOperationException(
    "ExerisDataSource requires a JDBC-backed PersistenceEngine (Community tier). " +
    "The bound engine does not provide JDBC connections. " +
    "Enterprise-tier JDBC compatibility requires exeris-spring-runtime-enterprise.");
```

This guard prevents silent `ClassCastException` at deep JPA/Hibernate call sites.

**Cost and trade-offs (honest accounting):**

| Dimension                    | Cost                                                                                    |
|:-----------------------------|:----------------------------------------------------------------------------------------|
| Implementation surface        | Minimal — proxy for 3 lifecycle methods only; all other methods delegate to real JDBC   |
| JPA compatibility             | High for Community tier — real JDBC driver is unchanged                                  |
| Connection lifecycle cost     | Each non-transactional `getConnection()` incurs a real `openConnection()` call          |
| Object allocation             | `ExerisConnectionProxy` allocated per connection acquisition (thin wrapper only)         |
| JDBC gravity well risk        | Elevated — teams that ignore Level 1 will organically drift toward this layer            |
| Enterprise compatibility      | None — Enterprise engine is not JDBC-backed; requires `exeris-spring-runtime-enterprise` |
| `rawJdbcConnection()` contract status | **Resolved** — `JdbcPersistenceConnection.rawJdbcConnection()` Javadoc updated to explicitly list integration bridges as an approved caller category alongside pool/eviction paths. No new kernel method required. |

---

### Option C — Side-Car HikariCP with Exeris Transaction Ownership

**REJECTED**

`ExerisDataSource` delegates to a HikariCP pool configured with a real JDBC driver
(e.g., `postgresql` JDBC driver) pointing at the same database backend. Transactions
are still managed by `ExerisPlatformTransactionManager`, which flushes/commits via
the JDBC `Connection` obtained from HikariCP.

**Why rejected:**

1. **Exeris loses direct connection lifecycle ownership.** HikariCP becomes the effective
   pool owner. `PersistenceEngine.openConnection()` is bypassed. This is the "JDBC gravity
   well" anti-pattern that ADR-010 and `module-boundaries.md` both explicitly forbid.
2. **`module-boundaries.md` bans `com.zaxxer:HikariCP` on the classpath of this module.**
   Introducing HikariCP as a `compile` or `runtime` dependency would be a direct
   architectural violation.
3. **Runtime ownership claim becomes false.** If HikariCP manages the connection pool and
   a JDBC driver manages the wire protocol, then "Exeris owns persistence connection
   lifecycle" is marketing language, not an architectural truth.
4. **Violates ADR-010 §3.1**: Exeris must own the data-plane. Option C makes Exeris the
   transaction coordinator on top of an independently owned pool — a split-brain model.

---

## 4. Decision

**Option B is accepted** for Phase 3, subject to all constraints below.  
**Option C is permanently rejected.**  
**Option A remains the canonical recommendation** for greenfield Exeris-native services.

### 4.1 Connection Acquisition Path

```
DataSource.getConnection()
    → check TransactionSynchronizationManager.getResource(ExerisDataSource.class)
    → if resource present (transaction active):
          return the existing ExerisConnectionProxy (no new open)
    → if resource absent (no active transaction):
          KernelProviders.PERSISTENCE_ENGINE.get().openConnection()
          → cast to JdbcPersistenceConnection — fail fast if not JDBC-backed
          → .rawJdbcConnection()            ← approved caller: integration bridge (see Javadoc)
          → wrap in ExerisConnectionProxy (lifecycle intercept only)
          → return proxy (caller owns close)
```

### 4.2 Package Placement

All JDBC adapter implementation classes must reside in:

```
eu.exeris.spring.runtime.data.compat.*
```

No JDBC adapter class may be placed in the root `eu.exeris.spring.runtime.data.*` package.
`ExerisDataAutoConfiguration` in the root package is already established and references the
compat package; this constraint governs implementation classes only.

### 4.3 Dependency Constraints

`exeris-spring-runtime-data/pom.xml` must NOT include:

- `com.zaxxer:HikariCP` at any scope
- Any JDBC driver (`org.postgresql:postgresql`, `com.mysql:mysql-connector-j`, etc.) at
  `compile` or `runtime` scope

JDBC driver selection is the application consumer's responsibility and must not be
prescribed by this integration layer.

### 4.4 Transaction Integration

`ExerisConnectionAdapter` must not call `PersistenceConnection.commit()`,
`PersistenceConnection.rollback()`, or `PersistenceConnection.close()` when a managed
transaction is active. Transaction lifecycle is owned by `ExerisPlatformTransactionManager`
in `exeris-spring-runtime-tx`. The connection adapter must be passive with respect to
transaction boundaries.

---

## 5. Connection Reuse Contract

This section documents the exact interaction between `DataSource.getConnection()` and
an active `ExerisPlatformTransactionManager`-managed transaction. This is modeled on
Spring's `DataSourceUtils.getConnection()` binding pattern.

### 5.1 When `@Transactional` is Active

```
@Transactional is entered
    → ExerisPlatformTransactionManager.getTransaction(definition)
    → PersistenceEngine.openConnection()
    → ExerisConnectionAdapter created and wrapped
    → TransactionSynchronizationManager.bindResource(ExerisDataSource.class, adapter)
    → ExerisTransactionSynchronizationBridge registered for cleanup

@Transactional body executes
    → JPA/Hibernate calls DataSource.getConnection()
    → ExerisDataSource.getConnection()
    → TransactionSynchronizationManager.getResource(ExerisDataSource.class) → returns existing adapter
    → same ExerisConnectionAdapter returned (no second open)

@Transactional exits (commit or rollback)
    → ExerisPlatformTransactionManager.commit() / rollback()
    → PersistenceConnection.commit() / rollback()
    → ExerisTransactionSynchronizationBridge.afterCompletion()
    → TransactionSynchronizationManager.unbindResource(ExerisDataSource.class)
    → ExerisConnectionAdapter released
```

There is exactly **one** `PersistenceConnection` per transaction, regardless of how many
times `DataSource.getConnection()` is called within that transaction scope.

### 5.2 When No Transaction is Active (Resource-Per-Call Semantics)

```
Code calls DataSource.getConnection() without @Transactional
    → TransactionSynchronizationManager.getResource(ExerisDataSource.class) → null
    → PersistenceEngine.openConnection()
    → new ExerisConnectionAdapter returned
    → caller must close() the adapter when done
    → ExerisConnectionAdapter.close() → PersistenceConnection.close()
```

This is **not pooled** at the `DataSource` level. Every non-transactional
`getConnection()` call incurs a real connection open. Applications relying on this
path for high-frequency non-transactional queries must be aware of the latency cost.
Exeris `PersistenceProvider` may implement pooling internally at the kernel level,
but `ExerisDataSource` does not add a pool on top of it.

---

## 6. Scope Boundaries

The `ExerisConnectionProxy` implements `java.sql.Connection` as a **pass-through proxy**
over the real JDBC connection obtained from `JdbcPersistenceConnection.rawJdbcConnection()`
(approved caller category — see §6.4 for bypass accounting).

### 6.1 What ExerisConnectionProxy handles directly

| Concern                    | Handling                                                          |
|:---------------------------|:------------------------------------------------------------------|
| `close()` in tx scope      | No-op — lifecycle owned by `ExerisPlatformTransactionManager`    |
| `commit()` in tx scope     | No-op — commit owned by `ExerisPlatformTransactionManager`       |
| `rollback()` in tx scope   | No-op — rollback owned by `ExerisPlatformTransactionManager`     |
| `close()` outside tx       | Delegates to `PersistenceConnection.close()`                     |
| `isClosed()`               | Reflects true connection state from `PersistenceConnection.isOpen()` |
| `setAutoCommit(false)` | Pass-through to raw connection — aligns with `JdbcPersistenceConnection` baseline behaviour; benign | Pass-through |
| `setAutoCommit(true)` | Throws `SQLException` — calling `setAutoCommit(true)` triggers an implicit PostgreSQL COMMIT that would corrupt `JdbcPersistenceConnection.inTransaction` state | Pass-through to raw connection |
| `setTransactionIsolation()` | Throws `SQLException` — isolation level cannot be changed after a transaction has started without corrupting baseline restore in `JdbcPersistenceConnection` | Pass-through to raw connection |
| `unwrap(Connection.class)` | Throws `SQLException` — the raw JDBC connection must not be exposed through the proxy; the proxy is the boundary | Throws `SQLException` — same reason; raw connection must never be accessible to callers |
| All other methods | Pass-through to real `java.sql.Connection` from `rawJdbcConnection()` | Pass-through |

### 6.2 What the real JDBC driver handles (no Exeris involvement)

- `prepareStatement(sql)` and all overloads
- `PreparedStatement` binding and execution
- `ResultSet` navigation, column access, type mapping
- `DatabaseMetaData`
- Stored procedures (`prepareCall`) — supported if the underlying JDBC driver supports them
- Savepoints — supported if the underlying JDBC driver supports them
- `getAutoCommit()` — passed through directly
- `setAutoCommit(false)` — passed through (see §6.1 for `setAutoCommit(true)` interception)

This is the principal advantage over the original naïve "emulation" approach: there is no
JDBC re-implementation effort, no surface to maintain, and no risk of subtle JDBC method
incompatibilities with Hibernate internals. Note that `setAutoCommit(true)`, `setTransactionIsolation()`,
and `unwrap(Connection.class)` are **not** pass-through calls — they are intercepted
by `ExerisConnectionProxy` (see §6.1).

### 6.3 Community-Only Constraint

`ExerisConnectionProxy` is only valid when the bound `PersistenceEngine` produces
`JdbcPersistenceConnection` instances (Community tier). If the Enterprise engine is bound,
`ExerisDataSource.getConnection()` throws `UnsupportedOperationException` immediately
(see §3 Option B, fail-fast clause).

There is no partial Enterprise JDBC emulation plan. Enterprise compatibility, if required,
is the scope of `exeris-spring-runtime-enterprise` (not this repository).

### 6.4 Known Bypasses and Compatibility Mode Constraints

The following Exeris kernel observability and lifecycle features are intentionally bypassed
for the JPA/Hibernate path in Compatibility Mode. These are accepted trade-offs of Option B,
not defects. They must be disclosed to operators using Exeris JFR tooling.

| Bypass | Scope | Observability substitute |
|:---|:---|:---|
| `PersistenceAdmissionStageEvent` (JFR) | All JPA-path statements | None — query telemetry belongs to Hibernate/JPA in compat mode |
| `ConnectionAcquireEvent` (JFR per-statement) | All JPA-path statements | `JpaConnectionAcquired` / `JpaConnectionBound` JFR events at `ExerisDataSource.getConnection()` call site |
| `PersistenceErrorTranslator` (Exeris error codes) | All JPA-path SQL exceptions | Raw `SQLException` propagated via JPA/Hibernate exception translation layer |
| `JdbcPersistenceStatement` wrapping | All prepared statements | None — real JDBC driver used directly for statement execution |
| `rawJdbcConnection()` contract intent | Integration bridge | **Resolved** — Javadoc updated to explicitly approve integration bridge callers alongside pool/eviction paths; no new kernel method required |

JPA-path queries are **invisible** to Exeris per-statement telemetry. Operators must be
informed that absence of per-statement Exeris JFR events does not indicate zero persistence
activity when JPA Compatibility Mode is active. `ExerisDataSource.getConnection()` MUST
emit two JFR events to provide minimum observability:

- `JpaConnectionAcquired` — emitted when a new `PersistenceEngine.openConnection()` call is
  made (non-transactional path)
- `JpaConnectionBound` — emitted when an existing transaction-bound proxy is returned (no
  new open)

These two events must be distinct JFR event types to allow independent filtering in recordings.
They are NOT equivalents of `ConnectionAcquireEvent` (which covers per-statement admission).

**State divergence — known scenarios (Compatibility Mode):**

Standard JPA/Hibernate CRUD operations within `@Transactional` do not trigger divergence.
The following scenarios are explicitly **unsupported** in Exeris Compatibility Mode:

- Direct DDL operations that manipulate autocommit state outside the proxy boundary
- Application code obtaining the raw JDBC connection via `unwrap()` (blocked by proxy; throws `SQLException`)
- Hibernate `StatelessSession` or bulk operations that bypass standard connection lifecycle
- Hibernate version-specific connection session resets that overwrite PostgreSQL session-level
  variables established by `RlsConnectionInterceptor`

**RLS / row-level security context note:**

`RlsConnectionInterceptor` uses `set_config('exeris.tenant_id', ?, false)` (third arg `false`
= session-level) and `SET search_path TO <schema>, public` (also session-level). Both persist
for the full connection lifetime, not just the current transaction boundary. RLS context
therefore **survives** JPA transaction boundary manipulation via `rawJdbcConnection()`.

However, Hibernate version-specific connection handling (session variable resets on checkout)
is not an Exeris-controlled invariant and must be validated against the target Hibernate version
before enabling RLS in Compatibility Mode.

**Scope expansion for this module:**

Future JPA feature requests (savepoints, stored procedures, scrollable cursors, batch operations)
require a new ADR. This module does not accept scope expansion to those surfaces without explicit
architectural review.

---

## 7. Architecture Guards

The following ArchUnit rules must be added to the `exeris-spring-runtime-data` test suite
before any JDBC adapter implementation class is committed.

### Rule 1 — JDBC Adapter Package Isolation

```java
// Enforces that all JDBC adapter classes stay in *.data.compat.*, not root *.data.*
noClasses()
    .that().implement(Connection.class)
    .or().implement(PreparedStatement.class)
    .or().implement(ResultSet.class)
    .should().resideOutsideOfPackage("eu.exeris.spring.runtime.data.compat..")
    .because("JDBC compatibility adapters must be isolated in *.data.compat.* (ADR-012)");
```

### Rule 2 — No HikariCP Dependency

The Maven Enforcer Plugin `banned-dependencies` rule in `exeris-spring-runtime-data/pom.xml`
must include:

```xml
<bannedDependencies>
  <excludes>
    <exclude>com.zaxxer:HikariCP</exclude>
    <exclude>org.postgresql:postgresql</exclude>
    <exclude>com.mysql:mysql-connector-j</exclude>
    <exclude>com.h2database:h2:*:compile</exclude>
    <exclude>com.h2database:h2:*:runtime</exclude>
  </excludes>
</bannedDependencies>
```

`h2` is permitted as `test` scope (for unit testing JDBC surface behavior) but must not
appear in `compile` or `runtime`.

### Rule 3 — No Cross-Module Contamination

```java
// Enforces *.data.* does not import *.web.* or *.actuator.*
noClasses()
    .that().resideInAPackage("eu.exeris.spring.runtime.data..")
    .should().dependOnClassesThat()
    .resideInAnyPackage(
        "eu.exeris.spring.runtime.web..",
        "eu.exeris.spring.runtime.actuator.."
    )
    .because("data module must not import web or actuator concerns (module-boundaries.md)");
```

### Rule 4 — Connection Adapter Must Not Self-Manage Transaction Lifecycle

```java
// ExerisConnectionAdapter must not call commit/rollback/close directly
// (enforced by ensuring no direct import of PersistenceConnection lifecycle methods)
// This rule is documentation-level; verify in code review that adapter methods
// for commit/rollback/close are no-ops in transaction scope and delegate only
// when no transaction is active.
```

Rule 4 is a code-review gate, not an ArchUnit rule, because ArchUnit cannot easily
distinguish method-body behavior. It is documented here so that reviewers treating
`ExerisConnectionAdapter` changes must confirm no-op semantics for managed scope.

### Rule 5 — `rawJdbcConnection()` Usage Audit

The `rawJdbcConnection()` Javadoc has been updated to explicitly approve integration bridge callers. Every call site must still carry the annotation comment to maintain audit traceability.

This rule cannot be enforced by ArchUnit (no method-call-site API available). It is a
**mandatory code review checklist item**. Any `ExerisConnectionProxy` or `ExerisDataSource`
change that touches `rawJdbcConnection()` must be reviewed to confirm the annotation comment is present.

```java
// rawJdbcConnection() approved caller: integration bridge that maintains connection lifecycle externally.
// Approved caller category added to JdbcPersistenceConnection Javadoc. See ADR-012 §6.4.
Connection raw = jdbcConn.rawJdbcConnection(); // <-- every call site must carry this comment
```

---

## 8. Phase Scope

| Phase     | Deliverable                                                                 | Notes                                                                                       |
|:----------|:----------------------------------------------------------------------------|:--------------------------------------------------------------------------------------------|
| **Phase 3** | `ExerisDataSource` — full scoped implementation (§5, §6)                  | Replaces scaffold stub. Backed by `PersistenceEngine.openConnection()`.                     |
| **Phase 3** | `ExerisConnectionProxy` — pass-through `java.sql.Connection` proxy | In `eu.exeris.spring.runtime.data.compat`. Intercepts: `close`, `commit`, `rollback` (no-ops in tx scope); `setAutoCommit(true)` and `setTransactionIsolation()` (throws `SQLException` in tx scope); `unwrap(Connection.class)` (always throws). All other methods pass-through. See §6.1. |
| **Phase 3** | ArchUnit rules (§7) added to `exeris-spring-runtime-data` test suite      | Must be present before any adapter implementation is committed.                             |
| **Phase 4+** | Exeris-native Spring Data `Repository` implementation                    | Bypasses JDBC entirely. Targets `PersistenceEngine` + `QueryExecutor` directly (Level 1).  |
| **Phase 4+** | Evaluate `@Query` DSL over `QueryExecutor` for Spring Data integration    | No JDBC adapter involvement. Separate ADR required.                                         |
| **Future** | `exeris-spring-runtime-enterprise` — Enterprise persistence integration | Separate repository. Native wire protocol, H3/QUIC, no JDBC. Out of scope for this ADR. |

### Phase 3 Non-Goals (Explicit)

- Full JPA/Hibernate compatibility (only the `SessionFactory` bootstrap path and core
  CRUD operations are targeted; advanced JPA features may not work)
- Connection pooling at the `DataSource` layer (pooling, if any, is internal to `PersistenceProvider`)
- Stored procedures, savepoints, scrollable cursors, JDBC advanced types
- Multiple simultaneous persistence providers (single `PersistenceEngine` only)
- Multi-tenancy or credential-parameterised connection acquisition

---

## 9. Enterprise Path Separation

The Exeris Enterprise persistence engine differs fundamentally from the Community tier
and is **incompatible with this module's JDBC bridge**:

| Dimension             | Community (this module)                              | Enterprise (future)                             |
|:----------------------|:-----------------------------------------------------|:------------------------------------------------|
| Wire protocol         | JDBC (pgjdbc, H2, etc.)                              | Native PostgreSQL wire protocol (no JDBC)       |
| Transport             | TCP via JDBC driver                                  | H3/QUIC (no TCP fallback)                       |
| Connection type       | `JdbcPersistenceConnection` + `rawJdbcConnection()`  | No `rawJdbcConnection()` — no JDBC              |
| Serialisation         | Jackson (via Spring's standard codec stack)          | Custom off-heap codec (no Jackson)              |
| Auth / JWKS           | Nimbus/JWKS via Spring Security OAuth2               | Native Exeris auth pipeline (no Nimbus)         |
| `DataSource` bridge   | `ExerisDataSource` (this ADR)                        | Not supported — requires `exeris-spring-runtime-enterprise` |

### 9.1 Implication for this Repository

`exeris-spring-runtime` targets the Community (JDBC) tier. All compatibility bridges in
this repository assume `JdbcPersistenceConnection` is available at runtime.

### 9.2 Future Enterprise Integration

If Enterprise persistence integration is required, it must be delivered in a separate
repository: `exeris-spring-runtime-enterprise`. That module will have a fundamentally
different integration strategy:

- No `javax.sql.DataSource` bridge (JPA/Hibernate are JDBC-first; incompatible with native protocol)
- Potential Hibernate SPI bypass (custom `ConnectionProvider` pointing to native engine)
- No Jackson codec dependency
- No Nimbus/JWKS dependency
- H3/QUIC-aware request dispatch path

A new ADR in `exeris-spring-runtime-enterprise` will govern the Enterprise persistence
integration strategy. That ADR is out of scope for this repository and this ADR.

### 9.3 Detection at Runtime

`ExerisDataSource.getConnection()` detects the engine type and throws `UnsupportedOperationException`
with a clear message pointing to `exeris-spring-runtime-enterprise` when a non-JDBC engine is bound.
This prevents silent runtime failures at deep Hibernate call sites.

---

## 10. Risks and Mitigations

| Risk                                                                        | Severity | Mitigation                                                                                       |
|:----------------------------------------------------------------------------|:---------|:-------------------------------------------------------------------------------------------------|
| Enterprise engine mistakenly bound with `ExerisDataSource`                  | High     | Fail-fast guard in `getConnection()` (§3, §9.3); throws `UOE` with clear message pointing to `exeris-spring-runtime-enterprise` |
| `PersistenceEngine` SPI not yet stable in 0.5.0-SNAPSHOT                   | High     | Adapter isolated behind an internal interface; swap kernel dependency without touching adapters  |
| JPA/Hibernate discovers unsupported methods and fails at startup            | Medium   | Document exactly which Hibernate bootstrapping calls are expected; add smoke test with Hibernate |
| Teams treat Option B as the default instead of Level 1 native repositories | High     | Autoconfiguration property stays `default=false`; documentation leads with Level 1              |
| `ExerisConnectionAdapter.close()` called in non-transactional scope leaks  | Medium   | Explicit try-with-resources guidance in Javadoc; close delegates to `PersistenceConnection.close()` |
| Future JPA feature request creep (savepoints, stored procs, scrollable RS) | Medium   | Explicit "out of scope" list in §6.4; scope expansion requires new ADR                          |
| `ThreadLocal` interaction between `TransactionSynchronizationManager` and VTs | Low    | Documented in Phase 3 plan as VT-safe with explicit cleanup; `ExerisTransactionSynchronizationBridge` scopes binding |
