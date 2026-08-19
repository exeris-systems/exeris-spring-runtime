# Changelog

All notable changes to `exeris-spring-runtime` are documented here.

This project is a **host-runtime integration layer**, not a Spring Boot starter. Spring is the
application framework; Exeris is the runtime owner. See [`docs/architecture/overview.md`](docs/architecture/overview.md).

---

## 0.7.0 — first published release — 2026-08-19

**Frozen.** This section said "not yet tagged" for as long as the release was held, and stated that it
would freeze when the tag was cut. This is that freeze: subsequent changes belong in a new section,
not here. Both conditions the release waited on were met before the cut — the kernel pin moved to
**0.11.0**, and the in-flight-drain gap is **closed** rather than documented: the kernel reordered its
shutdown, and the wire-level coverage that was disabled against 0.10.2 is active and green again.

**Status: preview.** No module in this release is GA. Every phase bridge beyond the Pure Mode request
path ships default-off, and "the code landed" is explicitly *not* the graduation criterion — see
[Preview status](#preview-status--what-07x-does-not-promise) below.

This will be the first tagged release of the repository. Prior work was consumed downstream as
`0.5.0-SNAPSHOT` from GitHub Packages. The version number jumps to `0.7.0` because the snapshot line had
already accumulated three planned release trains: `0.5.0` (Phase 4A + 4B), `0.6.0` (Phase 3B-α), and
`0.7.0` (Phase 4C). Tagging it `0.5.0` would have published 0.6.0 and 0.7.0 content under a label that
understates it. `0.7.0` is the highest train that had landed when the number was chosen. The Spring
Boot 4 dual matrix (ADR-028) has since landed here too, rather than in the `0.8.0` train the ADR
scheduled it for: 0.7.0 stayed untagged for weeks while it was held for the kernel drain fix, so work
merged in the meantime ships in it. The ADR's train label is a scheduling estimate, not a decision.

Train names in `docs/roadmap-1.0-trl9.md` carry a `-preview` suffix; **published Maven coordinates do
not**. Maven's `ComparableVersion` sorts the unknown qualifier `preview` as *newer* than the bare
release, which inverts the intent. Preview status is carried by the roadmap, this file, and the
default-off flags instead.

### Requirements

| | |
|:---|:---|
| Java | **25 (LTS)**, and **no preview flag anywhere** — not to compile against this runtime, not to run it. Published classes are class-file major 69, minor 0, enforced in CI against the built jars rather than inferred from the compiler configuration. Building on a newer JDK is fine. |
| `exeris-kernel` | **0.11.0** (released coordinate, not a snapshot). This is the kernel's **GA line**; the separate `0.11.0-preview` coordinate is a different artefact on a newer JDK and is not what this release consumes. |
| Spring Boot | **3.5.14** (default) or **4.1.0** — see the note below |
| Resolution | GitHub Packages — `maven.pkg.github.com/exeris-systems/*`, not Maven Central |

**Spring Boot 4 — nominal compatibility (ADR-028).** Both lines build and pass the full test suite in
CI on every push, and a failure on either blocks merge. Two caveats worth reading before you take this
as a support commitment:

- **One artefact serves both lines**, built against Spring Boot 3. There are no per-line classifiers.
  Because the two Spring Framework versions differ in ways that are invisible to a per-line build — a
  call site can compile on both and bind to a different method on each — a dedicated CI job compares
  every Spring call site's compiled descriptor across the two lines and fails the build on any
  divergence. Two such defects were found in Compatibility Mode by a downstream Spring Boot 4
  application before that job existed, and both are fixed here; see
  [ADR-067](docs/adr/ADR-067-binary-neutrality-of-the-published-artefact.md) for the decision and
  [`docs/architecture/spring-boot-4-matrix.md`](docs/architecture/spring-boot-4-matrix.md)
  §"Binary neutrality" for the measurements.
- **Spring Security 7 is a separate axis** (ADR-028 obligation 6) and is not covered by that claim.

**Java 25, and the preview flag is gone.** Earlier drafts of this section required JDK 26 with
`--enable-preview` at *runtime*, because the kernel was itself preview-compiled. That was never a
per-library opt-in: `--enable-preview` is whole-compilation and whole-JVM, preview class files are
stamped and pinned to one exact class-file major, and the requirement therefore propagated to every
consumer's entire build — their code included. Kernel 0.11.0 removed the stamp and this release
followed, moving the baseline **26 → 25 LTS**. For anyone already on 26 this is a widening, not a
restriction.

### Kernel 0.11.0 — what the bump changes for you

Four items reach a consumer. Only the first can change who gets served.

**🔒 The kernel's `/secure` path convention is gone (kernel ADR-061), and this release ships no
replacement.** Until kernel 0.11.0 the Community HTTP dispatcher hardcoded a `/secure` prefix:
anything beneath it required a token and a scope. That convention has been replaced by a declared
`HttpRoutePolicy` which the application binds — and **this runtime does not bind one yet**. So a
deployment that served routes under `/secure/...` and relied on the kernel to answer `401` for an
anonymous caller now gets `200` instead. Nothing in this repository referenced the convention, so
nothing here broke; the exposure is entirely on the deployment side, which is why it is stated here
rather than buried in a list.

What still protects those routes: Compatibility Mode's bearer-token authentication (ADR-041) is
unaffected and still runs, and `@PreAuthorize` inside handlers is unaffected. What does not exist yet
is a **per-path rule surface**. [ADR-063](docs/adr/ADR-063-exeris-http-security-route-policy-binding.md)
(`ExerisHttpSecurity`) is accepted and specifies exactly that — it was blocked on this very pin and is
now unblocked, but it is not in this release. Until it lands, express path-level authorization inside
your handlers.

Also note the kernel closed a related hole in the same release: on kernel 0.10.0/0.10.2, a route
resolved to a **stream** handler never reached the admission check at all, so an SSE route under
`/secure/...` was served to anonymous callers with no principal bound. If you ran streaming routes
there on an earlier kernel, treat them as having been public.

**`EventBus.publishAndAwait` runs handlers on the calling thread**, in subscription order. Durations
now sum rather than overlap, and a slow handler delays its successors. The method always blocked until
every handler finished — it now does that work instead of delegating it. `publish` is unchanged and
remains the fan-out path. This preserves the `ScopedValue` contract: a handler observes every binding
the publisher made, including ones the kernel cannot enumerate because they are yours, which no
fork-based mechanism built on GA APIs can deliver.

**`ImmutableStorageContext` gained a sixth component** (`sharedScopeKey`) without retaining the
five-argument constructor, so code constructing that record directly must be recompiled. Prefer the
`withSharedScope()` composer over the canonical constructor while the surface is `preview`.

**A failed subsystem in a parallel boot phase throws `BootstrapException`** rather than the preview
type `StructuredTaskScope.FailedException`. If you catch that preview type, remove it.

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

**Request scope (`exeris.runtime.context.scope.enabled=true`, ADR-029).** `ScopedValue<RequestScope>`
bound around `HttpHandler.handle`, carrying tenant and correlation identity with no `ThreadLocal`
copying. The disabled path is zero-cost. This is the substrate a future OTel bridge attaches to — it is
**not** tracing.

No fan-out helper ships. `ExerisStructuredScope` was withdrawn before this release (ADR-029 obligations
2 and 6, [RFC-2026-08-08](docs/rfc/RFC-2026-08-08-two-track-jdk-line.md)): it wrapped
`StructuredTaskScope` to rebind the scope into forks, and `StructuredTaskScope` was measured to do that
by itself — so the wrapper's only effect was an allocation per fork and a JDK **preview** type in this
runtime's public API, which would have forced `--enable-preview` on every consumer's entire build. Use
`StructuredTaskScope` directly and the propagation still holds; off the request thread, rebind with
`ExerisRequestScope.runWith` / `callWith`. Removing it makes this reactor compile with no preview flag
and ship zero preview-pinned classes.

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

- **The kernel pin moved to 0.11.0, which closed the shutdown gap that held this release.** See
  [Shutdown drains in-flight requests](#shutdown-drains-in-flight-requests) for what now holds and
  [Kernel 0.11.0 — what the bump changes for you](#kernel-0110--what-the-bump-changes-for-you) for the
  four consumer-visible changes it carries, one of them a security item.

- **The JDK baseline moved 26 → 25 LTS and the preview flag is gone from the whole chain.** The old
  requirement was inherited from a preview-compiled kernel rather than chosen, and it did not stop at
  this runtime's boundary — `--enable-preview` is whole-compilation and whole-JVM, so it reached every
  consumer's entire build and pinned them to one exact JDK. A CI gate now reads the published jars and
  fails on any class off class-file major 69 or carrying a preview stamp, because that is a property
  of what ships rather than of the compiler configuration; it fails rather than passes when it finds
  no jars to scan.

- **`@CompatibilityMode` now covers the whole compat surface.** The marker was declared in
  `exeris-spring-runtime-web`, and `data` and `actuator` — which both hold `*.compat.*` packages —
  may not depend on `web`. Six compat classes were therefore structurally unmarkable, so ADR-011's
  stated benefit, "a grep for `@CompatibilityMode` shows the full surface of compat-only behaviour",
  silently under-reported by six. The annotation moves to
  **`eu.exeris.spring.boot.autoconfigure.compat`** (a breaking change only for code that imported the
  marker directly; it is a `@Retention(CLASS)` marker with no runtime semantics). Per-module guards
  now enforce the coverage in `data` and `actuator` as well as `web`, each verified by watching it
  fail on a removed marker.

- **Compatibility Mode no longer breaks on Spring Boot 4.** `ResponseEntity<T>` return values failed
  with `IncompatibleClassChangeError` and multi-valued `@RequestHeader` binding failed with
  `NoSuchMethodError`, on the SB4 line only. Both call sites compiled cleanly on both matrix lines
  while binding to a different `HttpHeaders` method on each, so neither axis of the dual matrix
  objected — Spring Framework 7 stopped `HttpHeaders` implementing `MultiValueMap`. Both now use
  members whose signature is identical on both lines, and a new `binary-neutrality` CI job compares
  every Spring call site's compiled descriptor across the two lines so this class of defect cannot
  recur silently.

- Compat security under `web-application-type=none` (ADR-041) — Exeris runs with no servlet web
  application type, so every Spring Boot `@ConditionalOnWebApplication(SERVLET)` auto-config silently
  does not run. `JwtDecoder` was the first concrete casualty and is now re-activated explicitly. The
  general re-activation policy is deferred to an RFC; expect further instances of this class of problem.
- Compat security filter and flow step actions now execute inside the kernel provider scope, so
  providers resolved from `ScopedValue` slots are visible on the request/step path.
- **Compatibility Mode no longer serves unauthenticated traffic silently.** Three fail-open paths in
  the Spring Security compat surface are closed, all with escape hatches and all documented in the
  new [`compat-spring-security-support.md`](docs/architecture/compat-spring-security-support.md):
  - A `SecurityFilterChain` bean now **fails startup**. It could never be executed (no servlet
    container → no `FilterChainProxy`), and its presence also stood the compat fallback filter down,
    so a migrating application whose authorization lived in the chain was served with neither
    authentication nor authorization — and started cleanly. Acknowledge deliberately with
    `exeris.runtime.web.compat.security.allow-unenforced-filter-chain=true`; that silences the
    failure and does not make the chain run.
  - An **invalid Bearer token is answered with `401`** and `WWW-Authenticate: Bearer` instead of
    being downgraded to an anonymous request. Every rejection emits the JFR event
    `eu.exeris.spring.runtime.web.BearerTokenRejected`; previously a rotated key or clock skew left
    no trace at all. Opt out with
    `exeris.runtime.web.compat.security.reject-invalid-token=false`.
  - `AuthenticationException` → **401**, `AccessDeniedException` → **403**, replacing the blanket
    500. One known deviation from servlet Spring is recorded rather than hidden: an *anonymous*
    caller refused by method security gets 403 where Spring's `ExceptionTranslationFilter` would
    upgrade to 401.
- Compat datasource made usable under load: request-path scope re-binding, SPI unwrap, pool warmup and
  connection-timeout plumbing.
- **A JPA application no longer has to configure Hibernate around the bootstrap order.** Hibernate
  opens a JDBC connection during `EntityManagerFactory` construction to infer its dialect; that
  happens inside Spring `refresh()`, before the kernel exists, so the compat datasource cannot serve
  it. Applications had to know this and set two Hibernate internals by hand — a runtime ordering
  constraint living in application configuration, and the last thing standing between Compatibility
  Mode and "add the dependency, maybe change configuration". `ExerisHibernateBootstrapCustomizer`,
  registered by the same `exeris.runtime.data.compat-datasource.enabled` opt-in, now sets
  `hibernate.boot.allow_jdbc_metadata_access=false` and derives `hibernate.dialect` from
  `exeris.runtime.persistence.jdbc-url`. An application that states either itself — via
  `spring.jpa.properties.hibernate.*` or `spring.jpa.database-platform` — is left untouched. Dialects
  are derived for PostgreSQL and H2 only; any other URL fails startup with a message naming
  `spring.jpa.database-platform` rather than guessing, because a wrong dialect does not fail, it
  generates subtly wrong SQL.
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
  kernel work that has not shipped: kernel 0.11.0 has no `TraceContext` carrier and no
  `PrometheusOtlpTelemetrySink`, and the kernel places the carrier around Sprint 0.12 of its 1.0 GA
  roadmap, so this is not near-term. `exeris.runtime.telemetry.tracing-enabled` is forwarded to the
  kernel as configuration, but there is no Spring-side propagation or span-emission bridge in this
  release — setting it does not produce spans.
- **Spring Boot 4 is nominal compatibility, not a support commitment.** Both lines are built and
  tested in CI and one artefact serves both, but Spring Security 7 is out of scope (ADR-028
  obligation 6) and Compatibility Mode coverage on the SB4 line is only as broad as this repo's own
  test suite. See the Requirements section above.
- **No edge gateway.** Phase 5 / `exeris-spring-runtime-gateway` does not exist yet (0.9.0 train). It
  will not be a Spring Cloud Gateway compatibility bridge (ADR-021) — SCG DSL workloads run native SCG
  outside Exeris.
- **Compatibility Mode is bounded, not "all of Spring works."** It covers what this repo consumes from
  the framework, nothing broader.
- **No per-path route authorization.** ADR-063 (`ExerisHttpSecurity`) is accepted and unblocked but
  not implemented, and kernel 0.11.0 removed the `/secure` prefix convention it would replace. See
  the kernel-bump section above — this is the one gap in this release with a security consequence.

### Shutdown drains in-flight requests

A request in flight when shutdown begins **completes and is answered**. The kernel's stop path runs as
three phases — close ingress, drain while the write path is still alive, then tear down — with a
distinct `draining` state so the reactors keep serving after the listening socket has closed. Ingress
stops answering new requests as soon as shutdown has run.

This is worth stating explicitly because it was the gap that held this release, and because the
guarantee is what deployment sizing depends on. Verified end-to-end on a real socket by
`ExerisWireLevelRuntimeIntegrationTest#pureMode_shutdownDrainsInFlightRequest_beforeIngressBecomesUnavailable`,
re-enabled at the 0.11.0 pin and confirmed across repeated runs rather than a single pass — against
the previous kernel it failed 7 of 12 runs, so one green run was never going to be the evidence.

**If you are pinning an older kernel yourself, this does not hold.** Up to and including 0.10.2 the
drain ran *last* — after the transport had closed the listening socket and every live channel, and
after the reactor threads that write responses had exited — so it waited on work that could no longer
respond, and the request was dropped without one. On such a build, take the instance out of load
balancer rotation and let in-flight work finish *before* sending SIGTERM, rather than sizing
`terminationGracePeriodSeconds` against a drain window that will not protect it.

One operational note that survives the fix: the drain carries a kernel-side 60 s hard deadline and is
not configurable from Spring. `exeris.runtime.shutdown.timeout-seconds` bounds how long Spring waits to
join the kernel boot thread — it is not an in-flight request budget.

### Known operational note — version skew across modules

The flow-step provider-scope fix is only correct when the **whole reactor** is redeployed together.
A downstream bundle mixing pre-fix and post-fix modules produces sagas that never resolve, with no
application-level ERROR to point at it. Pin all `eu.exeris:exeris-spring-runtime-*` artefacts to the same
version via the BOM.
