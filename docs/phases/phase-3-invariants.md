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
freezing one engine at startup. On the request path this per-call read only resolves a bound
slot because the runtime re-binds `PERSISTENCE_ENGINE` on the carrier handler thread — see
invariant #12.

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
  Targets the 0.9.0-preview Spring-side train, gated on the kernel `TraceContext` carrier
  via `ScopedValue` — which the kernel defers to the consolidated 1.0 GA roadmap Sprint 0.12
  (~v0.12), **not** `exeris-kernel` 0.8.0 (0.8.0 shipped 2026-06-03 with no tracing; see kernel
  ADR-032 §traceparent). If the kernel slot has not landed by the 0.9.0-preview cut, 3B-β slips.
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

## 12. Request-path kernel provider slots are re-bound on the carrier handler thread

The runtime's `HttpHandler` (pure `ExerisHttpDispatcher` / compat `ExerisCompatDispatcher`,
bound via `HttpKernelProviders.HTTP_SERVER_HANDLER`) is invoked by the kernel on the transport
carrier thread, which does **not** inherit the bootstrap `ScopedValue` scope. So
`KernelProviders.PERSISTENCE_ENGINE` and `MEMORY_ALLOCATOR` are unbound on the request thread
unless re-propagated — without this, the compat datasource (`ExerisDataSource`,
`PersistenceEngineProvider`) fails on every request with "PersistenceEngine is not bound in the
current scope", and the response codec falls back to a heap buffer.

`ExerisRuntimeLifecycle` captures both engines at bootstrap (`getPersistenceEngine()` /
`getMemoryAllocator()`, alongside the event/flow/graph captures). The mode-neutral
`web.scope.KernelProviderBinder` re-binds them around each dispatch **only when the slot is
currently unbound** — a kernel-bound, carrier-affine value is never overridden (the allocator is
a single kernel-wide instance; affinity flows via the separate `CARRIER_INDEX` slot). This is
what makes invariants #3 and #6 hold on the request path. It is re-propagation of kernel-owned
references via `ScopedValue` (never `ThreadLocal`), not ownership inversion. The bean is wired
with a lazy `ObjectProvider<ExerisRuntimeLifecycle>` lookup to avoid the construction-time cycle
`lifecycle → HttpHandler → binder → lifecycle`.

- **Guard:** `KernelProviderBinderTest` (re-bind when unbound; no override when already bound;
  pass-through when no captured reference). `ExerisPureModeRequestPathIntegrationTest` and
  `ExerisCompatMvcIntegrationTest` exercise the binder wiring (that it sits on the dispatch path);
  the re-bind/no-override/pass-through behaviour is covered by `KernelProviderBinderTest`.

## 13. Compat datasource unwraps the request-session connection via the SPI (kernel ≥ 0.8.1)

The kernel HTTP dispatcher binds a per-request `PersistenceSessionBox`, so
`PersistenceEngine.openConnection()` returns a request-scoped *forwarding* `PersistenceConnection`,
not the concrete JDBC connection. `ExerisDataSource` obtains the raw `java.sql.Connection` through
the SPI `PersistenceConnection.unwrap(java.sql.Connection.class)` (added in kernel 0.8.1, forwarded
by the wrapper) — it no longer casts to the community-concrete `JdbcPersistenceConnection`. This
keeps the JDBC compatibility bridge swappable across engines and is why the runtime requires
`exeris-kernel` **0.8.1+**. When unwrap yields empty (a genuinely non-JDBC engine), `ExerisDataSource`
throws `UnsupportedOperationException` directing to the enterprise tier.

- **Guard:** `ExerisDataSourceTest` (non-JDBC stub → `UnsupportedOperationException`),
  `ExerisConnectionProxyTest`.

## 14. Shared-pool min-idle and warmup are plumbed through the config provider

The kernel's `CommunityPersistenceConfigResolver` builds its `PersistenceConfig` by asking the
active `ConfigProvider` for raw keys, including `persistence.minIdleConnections` (alias
`persistence.pool.minSize`) and `persistence.pool.warmup.{enabled,connections}` (bare aliases
`pool.warmup.*`). The typed `ConfigProvider.PersistenceSettings` bridge record only carries
`maxPoolSize` — so `max-pool-size` flows through `kernelSettings()`, but min-idle and warmup have
**no typed field** and their only path is the raw-key API. Without an alias, those lookups miss,
the kernel falls back to its default min-idle (~1), and the shared pool (`exeris-community-shared`)
starts **cold**: a startup burst of concurrent virtual threads races pool growth from 1 → max and
some acquisitions time out (`PersistenceProviderException.connectionExhausted` → 500) until it warms.

`ExerisSpringConfigProvider.persistenceKernelKeyAlias` maps those raw kernel keys onto the Spring
surface `exeris.runtime.persistence.{min-pool-size, pool-warmup-enabled, pool-warmup-connections,
connection-timeout-ms}` (symmetric with the `flowKernelKeyAlias` bridge). This lets an application
pre-warm the shared pool through its own min-idle knob, the same way a Spring/Hikari or
Quarkus/Agroal deployment does — the fix is config plumbing in the runtime, not a kernel change and
not an app-side workaround. The knob is mode-neutral (`MIXED`): it governs the kernel-owned pool
identically for pure and compat paths.

**Connection-timeout and fair-leveling.** Pre-warm blunts cold-start, but a *sustained* error spike
can remain under load. The kernel pool fail-fasts on acquisition (a short acquire timeout →
`connectionExhausted` → 500) where a default Spring/Hikari pool **blocks** ~30s — contention surfaces
as latency, not errors. `persistence.connectionTimeoutMs` is read by the kernel resolver via
`getLong` but had no record field and no alias, so the compat acquire timeout could not be raised
from configuration; comparisons against a JDBC-native target were unfair (compat = 500s, native =
latency). The alias now maps it to `exeris.runtime.persistence.connection-timeout-ms`, so a
deployment can set the same acquire timeout on both sides and contention shows up as latency, not
compat-only 500s.

- **Guard:** `ExerisSpringConfigProviderTest` (min-idle, warmup, and connection-timeout raw keys +
  bare aliases resolve to the `exeris.runtime.persistence.*` properties; `getLong` path for the
  timeout; literal-key precedence; empty when unset; null-environment safety).

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
| Request-path provider slots re-bound on carrier thread | `KernelProviderBinderTest`; `ExerisPureModeRequestPathIntegrationTest`, `ExerisCompatMvcIntegrationTest` |
| Shared-pool min-idle / warmup plumbed through the config provider | `ExerisSpringConfigProviderTest` (persistence raw-key aliases → `exeris.runtime.persistence.*`) |

These tests must stay green. A failure indicates a real architectural regression;
the test is not the bug.
