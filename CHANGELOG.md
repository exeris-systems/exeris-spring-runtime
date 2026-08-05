# Changelog

All notable changes to `exeris-spring-runtime` are documented here.

This project is a **host-runtime integration layer**, not a Spring Boot starter. Spring is the
application framework; Exeris is the runtime owner. See [`docs/architecture/overview.md`](docs/architecture/overview.md).

---

## 0.7.0 — first published release

**Status: preview.** No module in this release is GA. Every phase bridge beyond the Pure Mode request
path ships default-off, and "the code landed" is explicitly *not* the graduation criterion — see
[Preview status](#preview-status-what-07x-does-not-promise) below.

This is the first tagged release of the repository. Prior work was consumed downstream as
`0.5.0-SNAPSHOT` from GitHub Packages. The version number jumps to `0.7.0` because the snapshot line had
already accumulated three planned release trains: `0.5.0` (Phase 4A + 4B), `0.6.0` (Phase 3B-α), and
`0.7.0` (Phase 4C). Tagging it `0.5.0` would have published 0.6.0 and 0.7.0 content under a label that
understates it. `0.7.0` is the highest train that has actually landed — the next train (Spring Boot 4
dual matrix, ADR-028) has not started.

Train names in `docs/roadmap-1.0-trl9.md` carry a `-preview` suffix; **published Maven coordinates do
not**. Maven's `ComparableVersion` sorts the unknown qualifier `preview` as *newer* than the bare
release, which inverts the intent. Preview status is carried by the roadmap, this file, and the
default-off flags instead.

### Requirements

| | |
|:---|:---|
| Java | **26 with `--enable-preview`** (the kernel uses preview features → class file minor version 65535) |
| `exeris-kernel` | **0.10.2** (released coordinate, not a snapshot) |
| Spring Boot | 3.5.14 |
| Resolution | GitHub Packages — `maven.pkg.github.com/exeris-systems/*`, not Maven Central |

Spring Boot 4 is **not** supported in this release; the dual matrix is the 0.8.0 train (ADR-028).

### Published artefacts

`exeris-spring-runtime-bom` · `-build-config` · `exeris-spring-boot-autoconfigure` ·
`exeris-spring-runtime-web` · `-tx` · `-data` · `-actuator` · `-events` · `-flow` · `-graph`

Consume the BOM rather than pinning module versions individually.

### What ships

**Pure Mode request path (default, the reference architecture).** Exeris owns transport ingress and the
request lifecycle. `ExerisHttpDispatcher` bridges the kernel `HttpHandler` / `HttpExchange` SPI to Spring
handler beans via `@ExerisRoute` + `ExerisRequestHandler`. No servlet container, no Netty, no Reactor —
each module ships a `PureModeClasspathGuardTest` asserting their absence. Allocation is budgeted at
≤ 1024 B/dispatch mean for empty-body GET, enforced by `ExerisDispatcherAllocationBaselineTest`.

**Compatibility Mode (opt-in, `exeris.runtime.web.mode=compatibility`).** A bounded
`@RestController` / `@RequestMapping` bridge without `DispatcherServlet` or the servlet API. Isolated in
`*.compat.*` packages under `CompatibilityIsolationGuardTest`; pure-mode code may not import from it.
The allocation cost is real and deliberately published, not hidden — at Phase 2 closure
`ExerisCompatAllocationCostReportTest` recorded ≈ 176 B/dispatch pure vs ≈ 5095 B/dispatch compat
(≈ 29×). Gateway-class workloads are out of scope by decision (ADR-021).

**Transactions (`exeris.runtime.tx.enabled=true`).** `ExerisPlatformTransactionManager` over the kernel
`PersistenceConnection`, using `ScopedValue` rather than `ThreadLocal` as the context carrier.
Propagation: `REQUIRED`, `REQUIRES_NEW`, `MANDATORY`, `SUPPORTS`, `NEVER` supported; `NESTED` throws
`UnsupportedOperationException` (no savepoints on the kernel connection contract). `NOT_SUPPORTED` is
**not** rejected — Spring suspends the transaction before this manager is reached, so the call simply
runs non-transactionally. No `DataSource`/HikariCP ownership. Note the documented **retry gap**:
unlike the kernel's `TransactionalExecutor#executeManaged`, this manager does not auto-retry on
serialization/deadlock failure — callers handle `40001`/`40P01` themselves.

**Compat JDBC bridge (`exeris.runtime.data.compat-datasource.enabled=true`).** A deliberately narrow
`DataSource` adapter over the kernel persistence engine, scoped by ADR-017 — a migration aid for
brownfield code, not a first-class persistence path. No HikariCP, no JPA/Hibernate.

**Request scope + structured concurrency (`exeris.runtime.context.scope.enabled=true`, ADR-029).**
`ScopedValue<RequestScope>` bound around `HttpHandler.handle`, with tenant/correlation propagation across
`StructuredTaskScope` forks and no `ThreadLocal` copying. The disabled path is zero-cost. This is the
substrate a future OTel bridge attaches to — it is **not** tracing.

**Events seam (`exeris.runtime.events.enabled=true`, ADR-027).** Kernel `EventBus` exposed to Spring
beans. Spring's `ApplicationEventPublisher` and the Exeris `EventBus` stay strictly separate — neither is
wired into the other, enforced by `EventModuleBoundaryTest`. Subscriptions are cleaned up at
`SmartLifecycle.stop()`.

**Flow/saga seam (`exeris.runtime.flow.enabled=true`).** Declarative and imperative flow invocation plus
opt-in event-driven choreography (`exeris.runtime.flow.choreography-enabled=true`, activated only when
`FlowEngineCapabilities.choreographySupport()` reports support). Durable saga state is **on by default**
once flow is enabled (`exeris.runtime.flow.persistence-enabled`, default `true`): the community kernel
auto-selects `JdbcFlowSnapshotStore` when a JDBC `PersistenceEngine` is bound, otherwise the in-memory
store. Parked flows survive a JVM restart. `@Async` is never used as a substitute for missing flow
capability.

**Graph seam (`exeris.runtime.graph.enabled=true`, ADR-030).** `ExerisGraphTemplate` + `@ExerisGraphQuery`
over the kernel `GraphEngine`, with graceful stand-down when no engine is bound. No Spring Data Neo4j —
`org.springframework.data..` is banned by `GraphModuleBoundaryTest`. Concrete drivers are test-scope only.

**Actuator bridge.** Health, info, and a Micrometer `MeterBinder` over the kernel `TelemetrySink`.
Read-only observability; it never owns a data-plane path.

**Subsystem selection (`exeris.runtime.subsystems`).** Restrict which kernel subsystems boot, so an
application does not pay for what it does not use.

### Hardening since the feature trains

These landed after the last feature train and are the reason the snapshot line moved on:

- Compat security under `web-application-type=none` (ADR-041) — Exeris runs with no servlet web
  application type, so every Spring Boot `@ConditionalOnWebApplication(SERVLET)` auto-config silently
  does not run. `JwtDecoder` was the first concrete casualty and is now re-activated explicitly. The
  general re-activation policy is deferred to an RFC; expect further instances of this class of problem.
- Compat security filter and flow step actions now execute inside the kernel provider scope, so
  providers resolved from `ScopedValue` slots are visible on the request/step path.
- Compat datasource made usable under load: request-path scope re-binding, SPI unwrap, pool warmup and
  connection-timeout plumbing.
- **`exeris.runtime.persistence.max-pool-size` now actually reaches the connection pool.** The kernel
  resolves pool sizing from raw config keys only — it does not read the typed settings record this
  runtime was populating — so the property was silently inert and the pool was sized from
  `availableProcessors()` instead. Two consequences, both fixed: on a CPU-pinned container a
  configured `min-pool-size` above the CPU-derived max failed bootstrap outright
  (`minIdleConnections (16) > maxPoolSize (8)`), and where it did not fail, the pool was sized by CPU
  pinning rather than by configuration. The remaining resolver keys (`idle-timeout-ms`,
  `max-lifetime-ms`, `max-tenant-pools`, `rls-enabled`, `per-tenant-pooling`, `use-tls`) had no path
  either and are now carried by a generic `persistence.*` tail. **Set `max-pool-size` explicitly** —
  unset, the derived value still varies with CPU pinning.
- Pure-mode route lookup strips the query string before matching — previously a request carrying `?…`
  missed its route.
- Malformed numeric config values degrade instead of throwing; config lookups answer correctly with no
  Spring `Environment` present.
- CI: pre-merge `mvn verify` gate and per-module JaCoCo line-coverage floors (autoconfigure 0.84,
  web 0.79, tx 0.72, data 0.85, actuator 0.93, events 0.90, flow 0.97, graph 0.97), ratcheting toward
  ~0.85 at 1.0 GA.

### Preview status — what 0.7.x does *not* promise

Landing code is not graduation. Every phase bridge stays preview until its invariants are green **and**
at least one downstream service has run it in production for a representative period. Concretely:

- **No GA promise** for events, flow/saga, graph, tx, or the compat JDBC bridge. All default-off.
- **Graph GA is additionally kernel-gated.** The kernel Graph SPI is at TRL-3 and the
  `GraphChurnRatioTck` Community binding is not yet gated in kernel CI. `GraphCursor` and a fluent query
  DSL do not exist in the kernel SPI and are out of scope here.
- **No tracing.** Phase 3B-β (W3C `traceparent`) and 3B-γ (OTel span/metric emission) are blocked on
  kernel work that has not shipped: kernel 0.10.2 has no `TraceContext` carrier and no
  `PrometheusOtlpTelemetrySink`. `exeris.runtime.telemetry.tracing-enabled` is forwarded to the kernel
  as configuration, but there is no Spring-side propagation or span-emission bridge in this release —
  setting it does not produce spans.
- **No Spring Boot 4.** 0.8.0 train (ADR-028).
- **No edge gateway.** Phase 5 / `exeris-spring-runtime-gateway` does not exist yet (0.9.0 train). It
  will not be a Spring Cloud Gateway compatibility bridge (ADR-021) — SCG DSL workloads run native SCG
  outside Exeris.
- **Compatibility Mode is bounded, not "all of Spring works."** It covers what this repo consumes from
  the framework, nothing broader.

### Known operational note

The flow-step provider-scope fix is only correct when the **whole reactor** is redeployed together.
A downstream bundle mixing pre-fix and post-fix modules produces sagas that never resolve, with no
application-level ERROR to point at it. Pin all `eu.exeris:exeris-spring-runtime-*` artefacts to the same
version via the BOM.
