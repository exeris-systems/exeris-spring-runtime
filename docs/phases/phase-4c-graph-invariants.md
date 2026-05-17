# Phase 4C Invariants — Spring-Side Seam for Kernel Graph SPI

**Status:** Locked-in (Phase 4C closed 2026-05-17; preview-track, default-off, kernel-gated GA)
**Source of authority:** ADR-006 (The Wall), ADR-010 (Host Runtime Model), ADR-011 (Pure vs
Compatibility Mode), and **ADR-030** (Phase 4C Spring-Side Seam for Kernel Graph SPI). This
page enumerates the non-negotiable invariants Phase 4C established for the
`exeris-spring-runtime-graph` module.

A change that breaks any item below is an architectural regression, not a style issue, and
requires a superseding ADR — not a workaround in code.

Phase 0–3, 4A, 4B, and 3B-α invariants
([`phase-0-invariants.md`](phase-0-invariants.md),
[`phase-1-invariants.md`](phase-1-invariants.md),
[`phase-2-invariants.md`](phase-2-invariants.md),
[`phase-3-invariants.md`](phase-3-invariants.md),
[`phase-3b-alpha-invariants.md`](phase-3b-alpha-invariants.md),
[`phase-4a-events-invariants.md`](phase-4a-events-invariants.md))
still apply in full; Phase 4C invariants are additive and graph-specific.

These are also the **graduation gate** for promoting `exeris-spring-runtime-graph` from
1.0 preview to bounded GA in 1.0.x. Per ADR-030 §"Decision" and §"Trade-offs", graduation
is **kernel-gated**: the spring-side seam ships in preview at 0.7.0-preview, but the GA
promotion requires the Community `GraphChurnRatioTck` binding to be green in `exeris-kernel`
CI (kernel Sprint 7). A regression on any of these invariants blocks graduation; a regression
on the kernel side blocks graduation even if every Spring-side invariant is green.

---

## 1. Graph bridge is opt-in and never default

`ExerisGraphAutoConfiguration` activates only when `exeris.runtime.graph.enabled=true`. The
conditional is `@ConditionalOnProperty(prefix = "exeris.runtime.graph", name = "enabled",
havingValue = "true", matchIfMissing = false)` — there is **no `matchIfMissing=true`** and
no implicit activation path. Applications that do not opt in pay zero cost — the
autoconfig stands down, no supplier / template / processor beans are registered.

- **Guards:** `ExerisGraphAutoConfigurationTest#propertyDisabled_autoconfigStandsDown_noSupplierBean`,
  `ExerisGraphBridgeRuntimeIntegrationTest#seamActivates_whenPropertyEnabled_supplierBeanPresentSubscribesEmptyAtBoot`.

## 2. Autoconfig is ordered after `ExerisRuntimeAutoConfiguration`

`ExerisGraphAutoConfiguration` declares `@AutoConfiguration(after = ExerisRuntimeAutoConfiguration.class)`
so Spring Boot's topological sort processes the runtime autoconfig (which registers
`ExerisRuntimeLifecycle`) before the graph autoconfig evaluates `@ConditionalOnBean
(ExerisRuntimeLifecycle.class)`. Without the explicit `after` ordering, the conditional
could fire false in the evaluation window and the graph beans would silently never
register. The defensive `@ConditionalOnBean` stays alongside as a belt-and-braces guard
matching the Phase 4B precedent.

- **Guard:** code review against the autoconfig file; runtime IT exercises the wiring.

## 3. `GraphEngine` resolution is per-call via `GraphEngineSupplier`, never captured at bean construction

`GraphEngineSupplier.tryGet()` reads the captured `AtomicReference` on each call;
`ExerisRuntimeLifecycle.capturedGraphEngine` is the storage layer populated from the boot
thread inside the kernel's `ScopedValue` scope where `KernelProviders.GRAPH_ENGINE` is
bound. The supplier is the bean — the engine itself is read per call. This mirrors
Phase 4A invariant §7 (`EventEngineSupplier`) and Phase 4B's `FlowEngineSupplier` precedent.

A future PR that captures a `GraphEngine` reference at bean construction time (in any class
under `eu.exeris.spring.runtime.graph..`) is an ADR-030-violating PR.

- **Guards:** code review against `ExerisGraphAutoConfiguration` (`exerisGraphEngineSupplier`
  is a method reference to `lifecycle::getGraphEngine`, not a captured `GraphEngine`),
  `ExerisGraphAutoConfigurationTest#propertyEnabled_engineCaptured_supplierReturnsIt`.

## 4. Three-state activation matrix is operator-visible

The two-property matrix (`enabled` ∈ {false, true}, `requireEngine` ∈ {false, true})
produces three operator states that surface distinct diagnostics:

| `enabled` | `requireEngine` | `GraphEngine` bound? | State                                 | Diagnostic                                                                        |
|:---------:|:---------------:|:-------------------:|:--------------------------------------|:---------------------------------------------------------------------------------|
| `false`   | —               | —                   | Feature unused (default)              | No beans registered                                                              |
| `true`    | `true`          | yes                 | Feature active                        | Template returns kernel results                                                  |
| `true`    | `true`          | no                  | Fail loud at first use (prod default) | Supplier-level diagnostic: "GraphEngine is not available — kernel has not booted, or no GraphProvider was active during bootstrap" |
| `true`    | `false`         | no                  | Template constructed but unusable (dev/test only) | Template-level diagnostic: "ExerisGraphTemplate cannot operate without a kernel GraphEngine. exeris.runtime.graph.require-engine=false was set (dev/test mode)" |

A future PR that collapses the two messages, removes `requireEngine`, or alters the
operator-visible distinction is an ADR-030-violating PR.

- **Guards:** `ExerisGraphAutoConfigurationTest` (3 tests covering disabled / enabled-no-engine /
  enabled-with-engine), `ExerisGraphTemplateTest` (`requireOn_emptySupplier_throwsWithDiagnosticMessage`,
  `requireOff_emptySupplier_stillThrowsAtTemplateLevel`),
  `ExerisGraphBridgeRuntimeIntegrationTest#requireEngineFalse_templateConstructed_methodsStillThrowUntilEngineBinds`.

## 5. `LoanedBuffer` ownership: caller-owns, try-with-resources mandatory

`ExerisGraphTemplate.streamBfsJson(GraphTraversal)` is the only `LoanedBuffer`-returning
surface on the template. The buffer is owned by the caller, who must release it via
try-with-resources. The template does not retain a reference and does not transfer
ownership.

The flow is **fully-materialised**, not cursor-streaming: the kernel session is closed by
`execute()`'s try-with-resources BEFORE the buffer is returned to the caller. Buffer
validity after session close is guaranteed by the kernel SPI contract — `LoanedBuffer` is
an independently-owned off-heap slab, not freed by `session.close()`. A future kernel
`bfsCursor()` shape (currently "Planned — not yet implemented" per
`exeris-kernel/docs/subsystems/graph.md:149`) cannot use this wrapper; it requires a
separate `streamBfsJsonCursor()`-style method that keeps the session open across batch
reads.

A future PR that adds a `LoanedBuffer`-returning surface to the template/processor without
the caller-owns Javadoc + ownership-flow documentation is an ADR-030-violating PR.

- **Guards:** Javadoc on `ExerisGraphTemplate.streamBfsJson` (§"Session-close-before-return
  semantics"); `ExerisGraphQueryProcessorTest#loanedBufferReturnType_routesToStreamBfsJson`
  asserts the proxy passes the buffer through unchanged.

## 6. `@ExerisGraphQuery` validation is fail-fast at `BeanPostProcessor` time

`ExerisGraphQueryProcessor` validates annotated methods at post-processing time, before
the application context finishes refreshing. Failures throw `IllegalStateException` with
an error message naming the bean + method + supported alternatives. The three validation
rules are:

1. Method must be `public` (non-public methods cannot be Spring-AOP-proxied).
2. Return type must be `List<UUID>` (routes to `traverseBfs`) or `LoanedBuffer` (routes to
   `streamBfsJson`).
3. Method must declare exactly one parameter of type `GraphTraversal`.

Plus the final-class guard (4th fail-fast in `wrap()`): CGLIB subclass proxying requires a
non-final class.

A future PR that defers any of these checks to runtime invocation, or that adds new
supported method shapes without extending the validator, is an ADR-030-violating PR.

- **Guards:** `ExerisGraphQueryProcessorTest` (8 tests:
  `unsupportedReturnType_failsFastAtPostProcessing`,
  `wrongParameterShape_failsFastAtPostProcessing`,
  `nonPublicMethod_failsFastAtPostProcessing`,
  `finalClassWithAnnotatedMethod_failsFastWithOperatorReadableMessage`, plus 4 happy-path
  routing tests).

## 7. Module-boundary discipline — eight banned edges enforced at ArchUnit

The graph module imports only kernel SPI + autoconfigure + Spring autoconfig/context. The
following edges are banned, enforced at every PR by ArchUnit:

| Banned source package          | Why                                                                                       |
|:-------------------------------|:------------------------------------------------------------------------------------------|
| `org.springframework.data..`   | Spring Data Neo4j compatibility is explicitly out of scope (ADR-030 §"What is NOT in scope") |
| `org.springframework.web..`    | Graph operations are not request-path-bound                                              |
| `eu.exeris.kernel.community..` (production scope) | Concrete drivers (PGQ / Bolt / Memgraph) stay test-scope only          |
| `jakarta.servlet..`, `javax.servlet..`, `eu.exeris.kernel.spi.http..` | Graph is not a request handler                       |
| `org.springframework.transaction..`, `javax.sql..`, `java.sql..` | Graph session transactions are kernel-local; cross-resource transactions are not bridged at Phase 4C |
| `jakarta.persistence..`, `org.hibernate..`, `eu.exeris.kernel.spi.persistence..` | Persistence ports are kernel-internal                |
| `org.springframework.scheduling..`, `org.springframework.core.task..`, `org.springframework.context.event..`, `org.springframework.context.ApplicationEventPublisher` | Graph operations are direct kernel-SPI calls; no Spring async / app-event bridging |
| `eu.exeris.spring.runtime.{web,tx,data,actuator,events,flow}..` | Cross-runtime-module imports forbidden — graph reaches only `kernel-spi + autoconfigure + spring-boot-autoconfigure + spring-context` |

- **Guards:** `GraphModuleBoundaryTest` (9 ArchUnit rules); `PureModeClasspathGuardTest`
  (4 rules — servlet / Netty+Reactor / WebFlux server abstractions / DispatcherServlet,
  matching the Phase 1 invariant #10 per-module discipline).

## 8. No `ThreadLocal` on hot paths

The graph package does not use `ThreadLocal` as a carrier. Tenant / correlation propagation
into graph operations is the application's job through `ExerisRequestScope` from Phase
3B-α (ADR-029); the graph module reads no Spring scope and binds none. This invariant is
implicit from the Phase 1 narrative ban in CLAUDE.md and from the Phase 4C dependency-graph
(no `org.springframework.web.context.request..` imports allowed); it does not need its own
ArchUnit rule beyond what the boundary guard already enforces.

- **Guards:** `GraphModuleBoundaryTest#doesNotImportSpringWeb` (covers
  `org.springframework.web..` including `web.context.request..`); code review.

---

## Graduation criterion to 1.0.x bounded GA — kernel-gated

Per ADR-030 §"Decision" and the roadmap §"Recommended 1.0 Scope" entry for Phase 4C, the
graduation criterion is the conjunction of three conditions, all of which must hold:

1. All invariants on this page stay green at every PR (Spring-side discipline).
2. **Kernel `GraphChurnRatioTck` Community binding green in `exeris-kernel` CI** — the
   kernel-side test that asserts `EX-GRPH-5005` (excessive allocation) fires for Community
   drivers when allocation exceeds the declared churn-to-data ratio. Currently scheduled
   for kernel v0.8 Sprint 7 (per `exeris-kernel/docs/v0.8-sprint-and-implementation-map.md`
   Sprint 7 entry "Production correctness — Transport, Flow, Graph"). Without this kernel-
   side gate, the Spring-side seam stays preview — Spring beans calling
   `ExerisGraphTemplate.traverseBfs` against a Community PGQ driver have no CI guarantee
   that allocation excess fails loud instead of accumulating silently.
3. At least one downstream service has run 4C-Spring-seam in production for a
   representative period.

Either Spring-side regression OR missing kernel TCK keeps the module preview; both halves
of the kernel gate must clear before GA. Downstream adoption is the demand signal that
unblocks the kernel-gating timing — without it the kernel Sprint 7 ordering can slip
without breaking the 1.0 train.

---

## What this phase explicitly did NOT deliver (ADR-030 §"What is NOT in scope")

These remain out of scope for Phase 4C; pulling them in without an ADR amendment is a
discipline violation:

- Fluent `GraphQueryBuilder` DSL (kernel ships `GraphTraversal` record only)
- `GraphCursor` / unbounded traversal API (kernel "Planned — not yet implemented")
- Spring Data Neo4j compatibility (different abstraction)
- Multi-engine fan-out / engine-per-request
- Cross-resource transactions (`@Transactional` over graph + JDBC)
- `@RequiresRole` (ADR-014) integration on `@ExerisGraphQuery` methods
- Native-image / AOT compilation hints
- MATCH-DSL string parsing in `@ExerisGraphQuery.value()` — the attribute is reserved for
  a future kernel-side parser; Phase 4C Step 3 does not interpret it
