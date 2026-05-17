# ADR-030: Phase 4C Spring-Side Seam for Kernel Graph SPI

| Attribute       | Value                                                                                                                                                                                                                                            |
|:----------------|:-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **Status**      | **ACCEPTED** (drafted and accepted 2026-05-17; single decider — no future gating event; ratified by the PR that introduces this file; convention documented in `CLAUDE.md` §"ADR status convention in this repo")                                |
| **Deciders**    | Arkadiusz Przychocki                                                                                                                                                                                                                             |
| **Date**        | 2026-05-17                                                                                                                                                                                                                                       |
| **Scope**       | spring/graph (introduces `exeris-spring-runtime-graph` module; binds `KernelProviders.GRAPH_ENGINE` and exposes `GraphSession` to Spring beans; no kernel SPI changes)                                                                            |
| **Owning Repo** | `exeris-spring-runtime`                                                                                                                                                                                                                          |
| **Driven By**   | Phase 4C was originally post-1.0; graduated to 1.0 preview on 2026-05-17 per the downstream migration review that surfaced graph-shaped query demand on net-worth-style data with a ~6–8-week horizon (see `docs/roadmap-1.0-trl9.md` §"Why 4C promotion is acceptable now"). |
| **Compliance**  | [Roadmap to 1.0 and TRL-9](../roadmap-1.0-trl9.md), [Module Boundaries](../architecture/module-boundaries.md), [Phase 4A Events Invariants](../phases/phase-4a-events-invariants.md) (lifecycle-capture pattern)                                  |

## Context and Problem Statement

The kernel ships a Graph subsystem at TRL-3 — *Validated Architectural Prototype* per `exeris-kernel/docs/subsystems/graph.md:11`. Its public SPI (`eu.exeris.kernel.spi.graph.*`) exposes:

| Type            | Surface                                                                                                                                                          |
|:----------------|:----------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `GraphProvider` | `ServiceLoader`-discovered factory for `GraphEngine`; Community ships `PostgreSqlPgqProvider` and `Neo4jBoltProvider` and `MemgraphBoltProvider`.                |
| `GraphEngine`   | Per-application engine: `openSession()`, `dialect()`, `registerNodes/Edges(...)`, `engineName()`, `isRunning()`, `close()`.                                     |
| `GraphSession`  | Per-session API: `traverseBreadthFirst(GraphTraversal)`, `streamBfsJson(GraphTraversal)` (zero-copy `LoanedBuffer`), `upsertNode`, `deleteNode`, `findShortestPath`, `beginTransaction`/`commit`/`rollback`. |
| `GraphDialect`  | Driver-specific SQL/PGQ vs Cypher transpilation; not user-facing on the Spring path.                                                                            |
| `GraphTraversal`, `GraphNodeDescriptor`, `GraphEdgeDescriptor`, `PathResult` | Model records.                                                                          |

The kernel surface is **usable today** in real product scenarios (Community PGQ/Bolt drivers exist and work), but the operational gate — `GraphChurnRatioTck` Community binding + CI enforcement of `EX-GRPH-5005` — is on kernel v0.8 Sprint 7. The drivers themselves are production-ready per `subsystems/graph.md:139-141`; what's missing is the kernel-side CI evidence to call the subsystem production-grade.

Two questions naturally follow:

1. **Does the Spring-side seam land before kernel hardening?** If the seam is kernel-independent in *landing* (exposes whatever the kernel ships today) but kernel-gated in *graduation* (GA waits for the kernel Sprint 7 hardening), the seam can land at 0.7.0-preview without holding the spring-runtime train hostage to the kernel-Sprint-7 timeline.
2. **What does the seam look like to a Spring developer?** The Spring side should not re-shape the kernel SPI; it should bridge it through standard Spring affordances (`@Bean` engine, declarative `@ExerisGraphQuery` for parameterised MATCH-DSL strings, idiomatic `try`-with-resources for `GraphSession`).

The downstream demand identified 2026-05-17 (graph-shaped queries on net-worth-style data, ~6–8-week horizon) makes the answer "land the seam now, gate graduation on kernel TCK" the right call. Without the Spring-side seam, the downstream service falls back to denormalised columns — workable but not the architectural target, and migration cost compounds if the seam lands later.

This ADR answers: **what does `exeris-spring-runtime-graph` deliver in 0.7.0-preview, and where does the seam stop?**

## 🏁 The Decision

**Phase 4C delivers, in 0.7.0-preview, a new opt-in module `exeris-spring-runtime-graph` that captures `KernelProviders.GRAPH_ENGINE` from the kernel scope, exposes a Spring-friendly `ExerisGraphTemplate` facade over `GraphSession`, supports declarative MATCH-DSL queries via `@ExerisGraphQuery`, and ships a module-boundary architecture guard. No fluent DSL builder. No cursor API. No Spring Data Neo4j compatibility. GA graduation is kernel-gated on Community baseline TCK.**

**Concrete obligations:**

1. **New module `exeris-spring-runtime-graph`.** Maven artefact `eu.exeris:exeris-spring-runtime-graph`, Java package root `eu.exeris.spring.runtime.graph`. Module depends on `exeris-spring-boot-autoconfigure` (lifecycle), `exeris-kernel-spi` (Graph SPI), and `spring-context` (DI/lifecycle annotations). No dependency on `exeris-kernel-community` or any concrete kernel implementation outside test scope.
2. **Opt-in via `exeris.runtime.graph.enabled` (default `false`).** Activation must be explicit — applications that do not opt in pay zero cost. The conditional is `@ConditionalOnProperty(prefix = "exeris.runtime.graph", name = "enabled", havingValue = "true")` mirroring the Phase 4A/4B autoconfig discipline. There is no `matchIfMissing=true` and no implicit activation path.
3. **`ExerisGraphTemplate` Spring facade.** A `@Bean` exposed by the autoconfig that wraps the captured `GraphEngine` and provides session-scoped helpers:
   - `<T> T execute(ExerisGraphSessionCallback<T> action)` — opens a session, invokes the callback, closes the session in a `finally` block; the JdbcTemplate-style pattern for callers that want fine-grained control. `ExerisGraphSessionCallback` is a custom `@FunctionalInterface` (not `java.util.function.Function`) because the callback's `withSession(GraphSession)` declares `throws Exception`, allowing application code to propagate kernel `RuntimeException`s without unchecked-cast boilerplate at every call site while still accommodating any future checked-exception introduction in the kernel SPI.
   - `List<UUID> traverseBfs(GraphTraversal traversal)` — convenience for the most common operation.
   - `LoanedBuffer streamBfsJson(GraphTraversal traversal)` — zero-copy streaming variant. **Ownership contract:** the returned `LoanedBuffer` is owned by the caller, who **must** release it via try-with-resources (`LoanedBuffer` implements `AutoCloseable` per kernel SPI). The template does not retain a reference and does not transfer ownership to any other party. This is the only `LoanedBuffer`-returning method on the template surface; the contract is local and operator-visible at every call site.
   - `void inTransaction(Consumer<GraphSession> action)` — opens a session, calls `beginTransaction()`, runs the callback, `commit()` on success / `rollback()` on exception. `Consumer<GraphSession>` is sufficient here because the kernel `GraphSession` SPI throws `GraphQueryException` (and all `EX-GRPH-*` codes) as subclasses of `RuntimeException` (`ExerisKernelException extends RuntimeException`); the kernel does not throw checked exceptions from any `GraphSession` method, so the `Consumer` JDK type does not block exception propagation.
   - The template does **not** introduce a new error-code namespace — `EX-GRPH-*` codes from the kernel surface directly to the caller; Spring beans handle them like any other kernel exception.
4. **`@ExerisGraphQuery` annotation for declarative parameterised MATCH-DSL strings.** A `@Retention(RUNTIME)` method-level annotation whose `value()` is a MATCH-DSL string and whose `dialect()` defaults to the engine's `dialect()`. An `ExerisGraphQueryProcessor` (autoconfig-registered `BeanPostProcessor`) scans Spring beans for annotated methods and routes calls through the template. Parameters are bound by name (`{paramName}` placeholders); the binding is allocation-light. The annotation is **not** a Spring Data repository abstraction — it is a thin declarative wrapper over `GraphSession.streamBfsJson(GraphTraversal)` or `traverseBreadthFirst(GraphTraversal)` depending on the method's return type:
   - `LoanedBuffer` → stream JSON (caller releases via try-with-resources per obligation 3 ownership contract).
   - `List<UUID>` → BFS (returns the traversal result directly).
   - **Any other return type → fail-fast at `BeanPostProcessor` time**, before the application context finishes refreshing. The error message names the offending bean, method, and supported return types. Failing at post-processing time (not runtime invocation) is consistent with the `-parameters`-compilation-flag fail-fast precedent in §"Trade-offs" — build-time errors over runtime surprises is the discipline. The processor also rejects `@ExerisGraphQuery` on non-public methods at the same gate.
5. **Lifecycle wiring: `GraphEngineSupplier` interface seam backed by lifecycle capture (Phase 4A pattern).** The autoconfig introduces a `GraphEngineSupplier` interface analogous to Phase 4A's `EventEngineSupplier`:
   ```java
   public interface GraphEngineSupplier {
       Optional<GraphEngine> tryGet();
       default GraphEngine requireEngine() { /* throws IllegalStateException with operator-readable diagnostic */ }
   }
   ```
   The lifecycle capture (an `AtomicReference<GraphEngine>` on `ExerisRuntimeLifecycle`, populated once when `holdKernelScopeOpen` reads `KernelProviders.GRAPH_ENGINE` at kernel-scope entry) is the **storage** layer; the supplier is the **resolution** layer — each `tryGet()` call reads the captured reference. This matches Phase 4A invariant §7 ("resolved per call, never captured at bean construction or autoconfiguration time"): the bean is the supplier, not the engine itself; the engine is read per call from the lifecycle's captured ref. `ExerisGraphTemplate` receives the supplier (not the engine) via constructor injection.
   When the supplier's `tryGet()` returns empty (kernel did not provide an engine, or the kernel scope never opened), the autoconfig fails loud at first use of `ExerisGraphTemplate` via `requireEngine()` unless `exeris.runtime.graph.require-engine=false` is set (default `true`, matching Phase 4A). With `requireEngine=false`, `ExerisGraphTemplate` is still constructed but every method throws `IllegalStateException` until an engine becomes available — the use case is downstream dev / test environments where the kernel graph provider is intentionally absent.
6. **Module-boundary architecture guard `GraphModuleBoundaryTest`.** ArchUnit assertions, all merge-blocking:
   - No `jakarta.servlet..` imports.
   - No `io.netty..` or `io.projectreactor..` imports (Pure Mode classpath baseline).
   - No `org.springframework.web..` imports — the graph module is not request-path-bound.
   - No `org.springframework.data..` imports — Spring Data Neo4j compatibility is explicitly out of scope (see §"What is NOT in scope" below); a future PR that adds Spring Data Graph repository support is an ADR-030-amendment-worthy decision, not a silent shim.
   - No `eu.exeris.kernel.community..` imports in production scope — concrete drivers stay test-scope only.
7. **`PureModeClasspathGuardTest` ships per the per-module discipline.** Same banned-coordinate baseline as the other modules: Tomcat/Jetty/Undertow/Netty/Reactor/HikariCP/servlet API/SCG/WebFlux.
8. **Test scope uses kernel-community PGQ.** Integration tests bring up an in-memory or container-backed PostgreSQL with PGQ extension; the spring-runtime side covers seam wiring (autoconfig stand-down when engine absent; template happy-path; annotation-processor routing). Kernel-side correctness (graph algorithms, dialect transpilation, churn-to-data ratio) is the kernel's responsibility per `subsystems/graph.md`.

## Module surface at a glance

```java
package eu.exeris.spring.runtime.graph;

@FunctionalInterface
public interface ExerisGraphSessionCallback<T> {
    T withSession(GraphSession session) throws Exception;
}

public interface GraphEngineSupplier {
    Optional<GraphEngine> tryGet();
    default GraphEngine requireEngine() {
        return tryGet().orElseThrow(() -> new IllegalStateException(
                "Exeris kernel GraphEngine is not available — kernel has not booted, "
                        + "or no GraphProvider was active during bootstrap."));
    }
}

public final class ExerisGraphTemplate {
    public <T> T execute(ExerisGraphSessionCallback<T> action);
    public List<UUID> traverseBfs(GraphTraversal traversal);
    /** Caller owns the returned {@link LoanedBuffer}; release via try-with-resources. */
    public LoanedBuffer streamBfsJson(GraphTraversal traversal);
    public void inTransaction(Consumer<GraphSession> action);
    public GraphDialect dialect();
}

@Retention(RUNTIME)
@Target(METHOD)
public @interface ExerisGraphQuery {
    /** MATCH-DSL string; supports {paramName} placeholders bound by method parameter names. */
    String value();

    /** Optional dialect override; defaults to engine's dialect(). */
    Class<? extends GraphDialect> dialect() default GraphDialect.class;
}

@ConfigurationProperties(prefix = "exeris.runtime.graph")
public record ExerisGraphProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("true") boolean requireEngine
) { }
```

The dispatcher seam is **not** in this module — graph operations are independent of the HTTP request path. A handler may use `@Autowired ExerisGraphTemplate` from within an `ExerisRequestHandler` (or any other Spring bean), but the template itself has no request-context coupling.

## Consequences

### ✅ Positive Outcomes

- **[+] Spring-side seam lands at 0.7.0-preview without waiting on kernel Sprint 7.** The seam exposes whatever the kernel ships today; users who can tolerate the kernel TRL-3 status of the underlying drivers can adopt 4C in preview.
- **[+] Downstream migration unblocked.** The graph-shaped-query downstream service (~6–8-week horizon per the 2026-05-17 review) gets a Spring-side option in 1.0 preview rather than a 1.1.x deferral, removing the denormalised-columns fallback.
- **[+] No new kernel SPI surface.** The seam is pure Spring-side; it doesn't ask the kernel to add methods, change signatures, or shift TRL claims. Community kernel impls stay black-box per `feedback_community_kernel_public_surface.md`.
- **[+] Reuses Phase 4A lifecycle-capture precedent.** `KernelProviders.GRAPH_ENGINE` capture works the same way `KernelProviders.EVENT_ENGINE` capture did in PR #11 / Phase 4A — no new lifecycle machinery, just an additional `AtomicReference<GraphEngine>` on `ExerisRuntimeLifecycle`.
- **[+] Idiomatic Spring API.** `ExerisGraphTemplate` mirrors `JdbcTemplate`'s shape; `@ExerisGraphQuery` mirrors Spring Data's `@Query` shape (without becoming a Spring Data repository). Adoption cost is low for Spring developers.
- **[+] Default-off + require-engine separation.** Two distinct properties (`enabled` and `requireEngine`) match Phase 4A's three-state decision matrix — the operator distinction between "feature unused" and "feature configured but kernel-incomplete" stays operator-visible.

### ⚠️ Trade-offs

- **[-] GA graduation is kernel-gated.** Preview status persists until the kernel ships `GraphChurnRatioTck` Community binding with CI enforcement of `EX-GRPH-5005`. If kernel Sprint 7 slips, 4C stays preview through 1.0.x without affecting the 3B-α / 4A / 4B / 5 GA stories.
- **[-] No fluent DSL builder.** A `GraphQueryBuilder` would feel idiomatic to Spring developers, but the kernel SPI doesn't expose one — it ships the `GraphTraversal` record directly. Building a Spring-side fluent builder that transpiles to `GraphTraversal` is feasible but doubles the surface to maintain; deferred to a follow-up ADR if downstream demand surfaces.
- **[-] No `GraphCursor` API for unbounded traversals.** Kernel `GraphCursor` and `GraphSession.bfsCursor()` are "Planned — not yet implemented" per `subsystems/graph.md:149`. Until they land in kernel, the Spring-side seam exposes only single-result `traverseBreadthFirst` and zero-copy `streamBfsJson` (slab-bounded). Unbounded traversal would emit `EX-GRPH-5005` (excessive allocation); applications doing unbounded work must paginate themselves until the kernel API arrives.
- **[-] `@ExerisGraphQuery` parameter binding by name requires `-parameters` compilation flag.** Methods compiled without `-parameters` have erased argument names (`arg0`, `arg1`, …). The autoconfig fails fast at bean post-processing time if it finds a `@ExerisGraphQuery` method on a class compiled without `-parameters`, with a clear error message pointing at the Maven/Gradle compiler flag. This is consistent with the kernel's own `--enable-preview` requirement: build-time flags are part of the contract.
- **[-] No transactional Spring `@Transactional` interop.** `ExerisGraphTemplate.inTransaction(...)` uses the `GraphSession`'s own `beginTransaction`/`commit`/`rollback`. It does not enrol in a Spring `PlatformTransactionManager` (`ExerisPlatformTransactionManager` from `exeris-spring-runtime-tx` exists, but the kernel `GraphSession` transaction is a different kernel-side resource from the relational connection that `tx` manages). Cross-resource transactions are an ADR-NNN follow-up if downstream demand surfaces — for 4C, the kernel-graph transaction is operator-visible at the call site only.

### 📋 What is NOT in scope

- **Fluent `GraphQueryBuilder` DSL.** The kernel SPI ships `GraphTraversal` as a record (not a builder); building a Spring-side fluent DSL that transpiles to it is plausible but doubles maintenance surface. Deferred to a follow-up ADR if/when downstream demand surfaces.
- **`GraphCursor` / `bfsCursor()` API.** Kernel "Planned — not yet implemented" per `subsystems/graph.md:149`. Spring-side seam will expose `bfsCursor()` automatically when the kernel ships it, without an ADR amendment (the surface addition is mechanical).
- **Spring Data Neo4j compatibility.** `org.springframework.data.neo4j..` (or `spring-data-jpa` repository abstractions over graph backends) is a different abstraction — repository / entity / `findById` / dynamic query derivation. The kernel SPI is intent-based MATCH DSL; the gap is too wide for a thin bridge. Out of scope for 4C; if downstream demand for Spring Data Graph emerges, a separate ADR scopes that bridge.
- **Multi-engine fan-out / engine-per-request.** One `GraphEngine` per application context. Applications that need multiple engines configure them outside this autoconfig (manual `@Bean` registration). Multi-tenant fan-out via Spring proxies is a 1.0.x candidate at earliest.
- **Cross-resource transactions** (`@Transactional` spanning kernel graph + JDBC). See Trade-offs §4 above. Not in this ADR.
- **Application-side data-modelling abstractions.** `@Node` / `@Relationship` annotations for Spring beans (turning POJOs into graph entities) is the application's job, possibly with a tooling layer in `exeris-tooling`. Out of scope for the runtime-side seam.
- **`@RequiresRole` (ADR-014) integration on `@ExerisGraphQuery` methods.** RBAC on graph queries is a meaningful concern but is kernel-side (the kernel `RoleCheckEnforcer` already exists). A graph-query RBAC story belongs to a kernel-side ADR amendment, not this Spring-side seam.
- **Native-image / AOT compilation hints for the graph module.** SB4 (per ADR-028) does not promise AOT support; 4C inherits that limitation. AOT hints are a follow-up axis if/when downstream demand materialises.

## Cross-references

- ADR-006 — Spring-Free Kernel Boundary (The Wall): `exeris-docs/adr/ADR-006-spring-free-kernel-boundary.md` — the parent invariant; the graph module imports kernel SPI types only, never community internals.
- ADR-010 — Host Runtime Model: `docs/adr/ADR-010-host-runtime-model.md` — the ownership boundary; `ExerisGraphTemplate` is a Spring-side affordance over the Exeris-owned `GraphEngine`, not a re-implementation.
- ADR-011 — Pure Mode vs Compatibility Mode: `docs/adr/ADR-011-pure-mode-vs-compatibility-mode.md` — the graph module is Pure Mode throughout; there is no compatibility variant.
- ADR-017 — JDBC Compatibility Scope for `ExerisDataSource`: `docs/adr/ADR-017-jdbc-compact-scope.md` — adjacent compatibility-scope ADR; the graph module is **not** subject to ADR-017's JDBC-on-the-Exeris-path rules because the kernel `GraphEngine` owns its own connection lifecycle.
- ADR-022 — Persistence SPI Extension (Instant Binders): `exeris-kernel/docs/adr/ADR-022-persistence-spi-extension-instant-binders.md` — kernel-side persistence ADR; the graph module does not consume the persistence SPI directly (the graph engine owns its own backend connection).
- ADR-027 — Spring `ApplicationEventPublisher` / Exeris `EventBus` separation: `docs/adr/ADR-027-eventbus-applicationeventpublisher-boundary.md` — adjacent boundary invariant.
- ADR-029 — Phase 3B-α Scope: `docs/adr/ADR-029-phase-3b-alpha-scope-request-scope-and-structured-concurrency.md` — `ExerisRequestScope.tenantId()` is available from within an `ExerisGraphTemplate` callback, allowing tenant-scoped graph queries; the seam does not couple the two but they compose naturally.
- `exeris-kernel/docs/subsystems/graph.md` — kernel-side authoritative reference for Graph SPI behaviour, TRL claims, and the "Planned — not yet implemented" surface gaps that 4C-Spring-seam inherits.
- `docs/roadmap-1.0-trl9.md` §"0.7.0-preview" and §"Why 4C promotion is acceptable now" — the resequence-time rationale for promoting 4C from post-1.0 to 1.0 preview.

## Engineering Protocol

The ADR is forward-looking — implementation lands in the 0.7.0-preview train. Five concrete deliverables:

1. **Module skeleton + POM + structural doc reconciliation.** New `exeris-spring-runtime-graph` Maven module with the dependency edges in obligation 1; root POM `<modules>` entry; BOM entry in `exeris-spring-runtime-bom`. The same PR **must** also reconcile the structural-contract docs (category-2 per `CLAUDE.md` §"Documentation precedence" — these are load-bearing for reviewers and AI tooling, and silently omitting them would mean the seam is undocumented at the canonical doc layer):
   - **`docs/architecture/module-boundaries.md`**: add an `exeris-spring-runtime-graph` row to the responsibility matrix, list the allowed dependency edges (`autoconfigure`, `kernel-spi`, `spring-context`; test-only `kernel-community`), and add the row to the cross-module dependency graph.
   - **`docs/architecture/kernel-integration-seams.md`**: add a Graph seam row to the summary table — kernel SPI side `KernelProviders.GRAPH_ENGINE` / `GraphEngine` / `GraphSession`; Spring-side bridge `ExerisGraphTemplate` + `@ExerisGraphQuery` + `GraphEngineSupplier`; module `graph`.
   - **`CLAUDE.md` §"Kernel SPI seams (where the bridge happens)"** table: add a `GraphEngine / GraphSession` row mapping to `ExerisGraphTemplate` + `@ExerisGraphQuery` in module `graph`.
   - **`docs/phases/phase-3-invariants.md`** is **not** affected (Phase 4C does not touch Phase 3 surface); the Phase 3B-α invariant trail already established by ADR-029 stands unchanged.
   These three doc edits are deliberately scoped to Deliverable 1 (not deferred to a later deliverable) so the seam never lands without its canonical docs trail; the precedent is ADR-029's Engineering Protocol §4 ("Phase 3 invariants reconciliation"), which forced the corresponding doc update into the implementation PR rather than leaving it implicit.
2. **Autoconfig + properties + lifecycle capture.** `ExerisGraphAutoConfiguration` with `@ConditionalOnProperty` per obligation 2; `ExerisGraphProperties` record per the listing above; `ExerisRuntimeLifecycle` extension to capture `KernelProviders.GRAPH_ENGINE` (mirroring the `EventEngine` capture from PR #11).
3. **`ExerisGraphTemplate` + `@ExerisGraphQuery` + processor.** Per obligations 3-4. The annotation processor is a `BeanPostProcessor` registered by the autoconfig.
4. **Architecture guards.** `GraphModuleBoundaryTest` per obligation 6; `PureModeClasspathGuardTest` per obligation 7. Both merge-blocking ArchUnit tests.
5. **Integration tests.** Autoconfig stand-down when `GraphEngine` absent + `requireEngine=false`; autoconfig fail-loud when absent + `requireEngine=true` (default); happy-path template usage with kernel-community PGQ in test scope; `@ExerisGraphQuery` parameter-binding correctness.

Each deliverable can be a separate PR; they do not need to land in one. The 0.7.0-preview tag waits for all five.

A future PR that adds `org.springframework.data..` imports under `eu.exeris.spring.runtime.graph..` is an ADR-030-violating PR; the reviewer cites this ADR by number when blocking. A future PR that promotes 4C from preview to GA in 1.0 without kernel `GraphChurnRatioTck` Community binding green in CI is an ADR-030-violating PR — the GA graduation criterion is kernel-gated.
