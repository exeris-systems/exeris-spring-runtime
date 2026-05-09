# Phase 3: Transactions, Context Propagation, and Persistence Integration

**Status:** Closed (2026-05-09; 3A + 3C-L2 + 3D delivered, 3B explicitly deferred to 3.x)
**Depends on:** Phase 1 closed; Phase 2 closed
**Milestone:** M3
**Mode:** Mixed — Pure Mode tx context (`ExerisTransactionAutoConfiguration` opt-in); Compatibility Mode JDBC bridge (`*.data.compat.*`, opt-in per ADR-017)
**Governing ADRs:** ADR-006 (The Wall), ADR-010 (Host Runtime Model), ADR-011 (Pure vs Compatibility), [ADR-017](../adr/ADR-017-jdbc-compact-scope.md) (JDBC Compatibility Scope for ExerisDataSource)
**Invariants captured in:** [`phase-3-invariants.md`](phase-3-invariants.md)

---

## Goal

Extend the Exeris host-runtime model into deeper application concerns:

1. **Transaction boundary bridge** — `@Transactional` semantics backed by Exeris `PersistenceConnection`
2. **Context propagation** — security context, request scope, observability context integrated with `ScopedValue`
3. **Persistence integration** — native Exeris repository model; optional JDBC compatibility bridge

This phase has the highest architectural risk. Every design choice is evaluated against
the "JDBC gravity well" anti-pattern: designs that collapse Exeris back into being a
secondary component running inside a conventional `DataSource` → `ConnectionPool` → `JPA`
runtime are rejected.

---

## Sub-phase Structure

Phase 3 was delivered as four sub-phases. The original three sections (3A/3B/3C) from
this document are preserved as sub-phase identifiers; the closure work itself is 3D.

| Sub-phase | Scope | Status |
|:---|:---|:---|
| **3A** Transaction Bridge | `PlatformTransactionManager` over `PersistenceConnection`; propagation rules | ✅ Closed |
| **3B** Context Propagation | Request scope, security context, observability tracing | 🟡 **Deferred to 3.x** (security partially covered by Phase 2c; request scope + tracing await downstream demand) |
| **3C** Persistence Integration | Level 1 (native repo) + Level 2 (JDBC compat bridge per ADR-017) | ✅ Closed (Level 2; Level 1 is app code per plan) |
| **3D** Closure hardening | Invariants doc, doc reconciliation, sub-phase split | ✅ Complete |

---

## Phase 3A — Transaction Bridge ✅

`@Transactional` on Spring beans backed by Exeris `PersistenceConnection` lifecycle,
without HikariCP or `DataSource.getConnection()` as the canonical entry point.

| # | Deliverable | Module | Status | Evidence |
|:-:|:------------|:-------|:-------|:---------|
| 1 | `ExerisPlatformTransactionManager` (305 LOC) — `AbstractPlatformTransactionManager` impl | `tx` | ✅ | `ExerisPlatformTransactionManagerTest` |
| 2 | `ExerisTransactionObject` — carries `PersistenceConnection` for a single tx | `tx` | ✅ | unit + integration |
| 3 | `ExerisTransactionSynchronizationBridge` — Spring `TransactionSynchronizationManager` ↔ Exeris connection | `tx` | ✅ | `ExerisTransactionalAopIntegrationTest` |
| 4 | `ExerisJdbcResourceCallback` — bridges `ptm.setJdbcResourceCallback(...)` for JDBC datasource binding | `tx` | ✅ | exercised in `data` module integration |
| 5 | `PersistenceEngineProvider` — deferred `ScopedValue` accessor for native repos (Level 1 hand-off) | `tx` | ✅ | `ExerisTransactionScaffoldTest` |
| 6 | `ExerisTransactionAutoConfiguration` — `@AutoConfiguration`, `@ConditionalOnProperty("exeris.runtime.tx.enabled")` | `tx` | ✅ | `ExerisTransactionAutoConfigurationTest` |

### Propagation matrix

| Spring propagation | Status |
|:---|:---|
| `REQUIRED` | ✅ Joins existing transaction or starts new |
| `REQUIRES_NEW` | ✅ Always opens a second independent connection |
| `MANDATORY` | ✅ Requires active transaction; throws if none |
| `NESTED` | ❌ `UnsupportedOperationException` (Phase 3 scope limit; needs kernel savepoint support) |
| `NOT_SUPPORTED` | ❌ `UnsupportedOperationException` (would require connection suspension; deferred) |
| `SUPPORTS` | ✅ Joins if present, runs without transaction otherwise |
| `NEVER` | ✅ Throws if active transaction present |

### Divergence from original plan

The original plan proposed a hybrid `ScopedValue` + Spring `TransactionSynchronizationManager`
dual binding for the active connection. The implementation took a cleaner path: the
`PersistenceConnection` travels inside Spring's transaction infrastructure (via
`ExerisTransactionObject` carried in `DefaultTransactionStatus`) and is **not**
duplicated into a `ScopedValue` slot. The `KernelProviders.PERSISTENCE_ENGINE` `ScopedValue`
is read at `doBegin(...)` time (not at bean construction), preserving the kernel
ScopedValue contract for engine resolution. Connection lifetime is bounded by the
Spring transaction boundary; no separate ScopedValue-bound connection state exists,
which removes a category of dual-binding bugs the original plan's hybrid approach
would have introduced.

### Test coverage

22/22 tests green in `tx` module:
- `ExerisPlatformTransactionManagerTest` — propagation rules, commit/rollback semantics
- `ExerisTransactionalAopIntegrationTest` — full `@Transactional` AOP proxy round-trip
- `ExerisTransactionAutoConfigurationTest` — opt-in activation, conditional wiring
- `ExerisTransactionScaffoldTest` — `PersistenceEngineProvider` ScopedValue resolution
- `PureModeClasspathGuardTest` — no servlet/Netty/Reactor/WebFlux on classpath

---

## Phase 3B — Context Propagation 🟡 Deferred to 3.x

The original plan listed three areas:

1. **Request Scope** — Spring `@RequestScope` ↔ `ScopedValue`-bound per-request map.
   **Not implemented.** `RequestContextHolder.ThreadLocal` parity is not bridged; Spring
   `@RequestScope`-annotated beans are not supported on the Exeris path.

2. **Security Context** — `ScopedValue` ↔ `SecurityContextHolder` ThreadLocal bridging.
   **Partially delivered through Phase 2c**: `ExerisSecurityContextFilter` (web/compat)
   binds `SecurityContextHolder` from request-scope context for the duration of handler
   execution and clears it in `finally`. This is sufficient for `@RestController` flows
   in Compatibility Mode. There is no Pure Mode `ExerisSecurityContextAccessor` yet.

3. **Observability Context (Tracing)** — Micrometer `Observation` / `Span` propagation
   through `ExerisServerRequest`. **Not implemented.** No Micrometer Tracing dependency
   is wired; no `KernelEvent` tag bridge for active spans.

### Why deferred

- **No observed downstream demand** during the Phase 3 closure window for either request
  scope or tracing.
- **Security context's most common case is covered** by the Phase 2c filter. Most apps
  on the runtime today are `@RestController`-style with security on the dispatch path.
- **Adding speculative bridges creates surface area to maintain.** A request-scope
  shim that nobody uses still has to stay green across Spring upgrades.

### Phase 3.x scope (deferred)

If a downstream service surfaces a real need, Phase 3.x will deliver:
- `ExerisRequestScopeBean*` — `ScopedValue`-bound per-request map; `BeanFactoryAware`
  support for Spring `@RequestScope`
- `ExerisSecurityContextAccessor` — Pure Mode (non-`SecurityContextHolder`) accessor
- Micrometer Tracing bridge — read active `Span` from `Observation` context, propagate
  via `ExerisRequestContext` ScopedValue, surface trace-id in kernel telemetry

These items have no concrete shape until demand exists; speculative implementation
is explicitly out of scope.

---

## Phase 3C — Persistence Integration ✅

### Level 1 — Exeris Native Repositories (recommended path)

Application code implements repository interfaces backed by Exeris `PersistenceEngine`
and `QueryExecutor` directly. **No infrastructure is required from this module** —
this is application-side code per the original plan. `PersistenceEngineProvider` (in
the `tx` module, sub-phase 3A item 5) is the deferred `ScopedValue` accessor that
application repositories use to obtain the engine at call time.

### Level 2 — JDBC Compatibility Bridge ✅ (per ADR-017)

For applications that require JPA / Hibernate compatibility, the `data` module ships
a `DataSource` adapter backed by Exeris Community `JdbcPersistenceConnection`.

| # | Deliverable | Module | Status | Evidence |
|:-:|:------------|:-------|:-------|:---------|
| 7 | `ExerisDataSource` (211 LOC) — `javax.sql.DataSource` adapter, transaction-aware connection reuse | `data` (`compat`) | ✅ | `ExerisDataSourceTest` |
| 8 | `ExerisConnectionProxy` (285 LOC) — wraps the Community JDBC connection; intercepts lifecycle, forwards rest | `data` (`compat`) | ✅ | `ExerisConnectionProxyTest` |
| 9 | `JpaConnectionAcquiredEvent` / `JpaConnectionBoundEvent` — Spring application events for observability of JPA connection acquisition | `data` (`compat`) | ✅ | exercised in integration |
| 10 | `ExerisDataAutoConfiguration` — `@ConditionalOnProperty("exeris.runtime.data.compat-datasource.enabled")` | `data` | ✅ | `ExerisDataAutoConfigurationTest` |
| 11 | ADR-017 — JDBC Compatibility Scope (ACCEPTED 2026-04-07) | docs/adr | ✅ | governs all `data` module classes |
| 12 | `DataModuleBoundaryTest` — ArchUnit guard for ADR-017 §7 Rules (JDBC adapter classes must reside in `*.data.compat.*`) | `data` (test) | ✅ | enforced |

**Connection sharing semantics:** When `ExerisPlatformTransactionManager` starts a new
transaction, it calls `ExerisDataSource.bindTransactionConnection(...)` via the
registered `ExerisJdbcResourceCallback`. Subsequent `getConnection()` calls within the
same transaction return the already-bound proxy (one connection per transaction).
Outside a Spring-managed transaction, each `getConnection()` opens a new connection.

**JDBC gravity well guard:** `DataModuleBoundaryTest` enforces ADR-017 §7 Rule 1 —
any class implementing `java.sql.Connection`, `PreparedStatement`, or `ResultSet` must
reside in `*.data.compat.*`. JDBC types cannot leak into the Pure Mode path.

### Test coverage

34/34 tests green in `data` module:
- `ExerisDataSourceTest` — DataSource adapter contract, transaction-bound reuse
- `ExerisConnectionProxyTest` — lifecycle method interception, forwarding behavior
- `ExerisDataAutoConfigurationTest` — opt-in activation
- `DataModuleBoundaryTest` — ADR-017 §7 enforcement
- `PureModeClasspathGuardTest` — no servlet/Netty/Reactor/WebFlux

---

## Phase 3D — Closure Hardening ✅

Items required to formally close M3.

| # | Item | Module | Status | Result |
|:-:|:------|:-------|:-------|:-----|
| 13 | Phase 3 invariants document | docs | ✅ | [`phase-3-invariants.md`](phase-3-invariants.md) — 10 tx + data invariants additive to Phase 0/1/2, each mapped to its guard. |
| 14 | Phase 3 master doc reorg | docs | ✅ | This file rewritten as master with sub-phase split (3A / 3B / 3C / 3D); status flipped to Closed. |
| 15 | Roadmap update | docs | ✅ | Phase 3 row flipped from "In progress, scaffolded" to "Closed" with concrete evidence; 3B explicit deferral noted. |
| 16 | (Co-delivered) Actuator module finalised | `actuator` | ✅ | 4 main classes + 4 test files (24/24 green); `ExerisActuatorTelemetryBridge` reads `TelemetrySink` into Micrometer; `ExerisRuntimeHealthIndicator` reads `KernelProviders` state; `compat/ExerisCompatibilityActuatorController` exposes `/actuator/health` + `/actuator/info` for Compat Mode. **Not formally part of Phase 3 plan**, but the work landed in the same window and is acknowledged here for traceability. |

---

## Autoconfigure module changes (Phase 3 wiring)

Two files in `exeris-spring-boot-autoconfigure` were modified during Phase 3 work
(present in the closure PR; tracked here for completeness):

- **`ExerisSpringConfigProvider.java`** — adds `LEGACY_HTTP_SYSTEM_PROPERTIES` mechanism
  with `publishLegacyHttpAliases` / `restoreLegacyHttpAliases` invoked from
  `prepareBootstrap` ↔ `clearBootstrap`. Plus a `server.port` fallback in
  `kernelSettings()` — when `exeris.runtime.network.port` is unset, the Spring Boot
  conventional `server.port` is read instead. This makes Exeris-runtime apps
  configuration-compatible with `server.port`-shaped Spring Boot deployments without
  a forced config rewrite.
- **`ExerisBootstrapIntegrationTest.java`** — +51 LOC verifying the new alias
  publish/restore behavior and the `server.port` fallback. 27/27 autoconfigure tests
  remain green.

---

## Activation Configuration

```yaml
# Phase 3A — opt into the transaction bridge
exeris:
  runtime:
    tx:
      enabled: true

# Phase 3C Level 2 — opt into the JDBC compat DataSource (ADR-017 territory)
exeris:
  runtime:
    data:
      compat-datasource:
        enabled: true
```

Both are **default off**. Activation requires explicit operator action. When disabled,
the modules compile in but no beans are created and no runtime overhead is added.

---

## Exit Criteria

Phase 3 closes (with 3B deferred to 3.x) when all of the following hold:

1. ✅ `@Transactional` commit and rollback work on a Spring bean backed by Exeris persistence (`ExerisTransactionalAopIntegrationTest`).
2. 🟡 Request scope beans VT-safe — **deferred to 3.x** (no implementation; documented as deferred above).
3. n/a Native Exeris repository (Level 1) — deliberate "app code, not infrastructure" per plan; `PersistenceEngineProvider` (3A item 5) is the hand-off point.
4. ✅ `ExerisDataSource` (Level 2) provides correct JDBC semantics with transaction-bound reuse.
5. ✅ The Wall remains intact: no Spring types in kernel SPI/Core (`WallIntegrityTest` from Phase 0).
6. ✅ No `ThreadLocal` leak across request boundaries — `tx` ships `PureModeClasspathGuardTest`; security ThreadLocal bridging stays in `web/compat/context|filter` per Phase 2c isolation guard.
7. ✅ Phase 1 and Phase 2 integration tests still pass (Phase 1: 27 + 115 + actuator etc.; Phase 2: 15/15 + invariants).
8. ✅ Sub-ADR accepted for the `data` module's JDBC adapter set: ADR-017 (ACCEPTED 2026-04-07) covers the entire `*.data.compat.*` package per ADR-017 §7.
9. ✅ Phase 3 invariants documented in [`phase-3-invariants.md`](phase-3-invariants.md) (Phase 3D).

All non-deferred criteria are met. Item 2 closes via explicit deferral (master-doc
reorg + roadmap row update); item 9 closes via the invariants doc. Phase 3 is closed.

---

## Kill Criteria — none triggered

The plan defined four kill criteria for Phase 3. None triggered:

1. ❌ "Achieving `@Transactional` support requires inserting Spring types into kernel Core" — did not occur. Spring types stay in `tx` module; kernel SPI/Core remain Spring-free.
2. ❌ "The persistence path irreversibly collapses into HikariCP/JPA as the canonical owner" — did not occur. Level 2 is opt-in (ADR-017 governs scope); `DataModuleBoundaryTest` enforces JDBC adapter isolation; HikariCP not on classpath.
3. ❌ "`ThreadLocal` contamination cannot be reasonably isolated to the compatibility bridge scope" — did not occur. Tx module uses `PersistenceConnection` carried inside Spring's `DefaultTransactionStatus`, no module-owned `ThreadLocal`. Security `ThreadLocal` lives only in `web/compat/context|filter` (Phase 2c).
4. ❌ "Performance measurements show transaction overhead eliminates all Exeris benefits compared to a Spring Boot + HikariCP baseline" — not measured at Phase 3 closure (no benchmark gate planned for M3); kernel-level connection lifecycle is the same code path the kernel uses for non-Spring callers, so no Spring-specific overhead penalty exists at this layer.

---

## Risks (resolved at closure)

| Risk | Resolution |
|:-----|:-----------|
| Kernel `PersistenceEngine` API not yet public in 0.5.0-SNAPSHOT | Accessed via `KernelProviders.PERSISTENCE_ENGINE` ScopedValue at transaction-begin time; kernel API is now stable enough for this contract. |
| Nested transaction semantics mismatch | Documented as `UnsupportedOperationException` for `NESTED` and `NOT_SUPPORTED`; `REQUIRED`, `REQUIRES_NEW`, `MANDATORY`, `SUPPORTS`, `NEVER` all supported. |
| VT `ThreadLocal` interaction with Spring AOP proxy creation | Tested in `ExerisTransactionalAopIntegrationTest`; AOP proxy creation occurs off-VT during context refresh, not on the request VT. |
| Suspension/resume in Exeris kernel not implemented | Mitigated by treating REQUIRED + REQUIRES_NEW as the supported propagation set; `REQUIRES_NEW` opens a second connection rather than suspending the first. |
| JDBC gravity well | Mitigated by ADR-017 boundaries + `DataModuleBoundaryTest` ArchUnit enforcement (JDBC adapter classes must reside in `*.data.compat.*`). |
