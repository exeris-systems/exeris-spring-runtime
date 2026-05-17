/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.graph;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import eu.exeris.kernel.spi.graph.GraphDialect;
import eu.exeris.kernel.spi.graph.GraphEngine;
import eu.exeris.kernel.spi.graph.GraphSession;
import eu.exeris.kernel.spi.graph.model.GraphTraversal;
import eu.exeris.kernel.spi.memory.LoanedBuffer;

/**
 * JdbcTemplate-shaped Spring-side facade over the kernel {@link GraphEngine} / {@link GraphSession}
 * SPI, per ADR-030 obligation 3.
 *
 * <h2>API surface</h2>
 *
 * <ul>
 *   <li>{@link #execute(ExerisGraphSessionCallback)} — opens a session, invokes the callback,
 *       closes the session in a {@code finally} block. The application controls everything that
 *       happens between open and close.</li>
 *   <li>{@link #traverseBfs(GraphTraversal)} — convenience for the most common operation, equivalent
 *       to {@code execute(s -> s.traverseBreadthFirst(traversal))}.</li>
 *   <li>{@link #streamBfsJson(GraphTraversal)} — zero-copy streaming variant; <strong>caller owns
 *       the returned {@link LoanedBuffer} and MUST release it via try-with-resources</strong>
 *       (per ADR-030 obligation 3 ownership contract). The template does not retain a reference
 *       and does not transfer ownership.</li>
 *   <li>{@link #inTransaction(Consumer)} — opens a session, calls {@code beginTransaction()}, runs
 *       the action, {@code commit()} on success / {@code rollback()} on exception.</li>
 *   <li>{@link #dialect()} — returns the active engine's {@link GraphDialect}.</li>
 * </ul>
 *
 * <h2>Engine resolution</h2>
 *
 * <p>The template holds a {@link GraphEngineSupplier} (per Phase 4A invariant §7 — engine is
 * resolved per call, not captured at construction). {@link ExerisGraphProperties#requireEngine()}
 * controls the missing-engine behaviour:
 *
 * <ul>
 *   <li>{@code requireEngine=true} (default) — every method calls
 *       {@link GraphEngineSupplier#requireEngine()}, which throws {@link IllegalStateException}
 *       with an operator-readable diagnostic if no engine is bound.</li>
 *   <li>{@code requireEngine=false} (dev/test only) — every method still throws
 *       {@link IllegalStateException} when no engine is bound, but the supplier's
 *       {@code tryGet()} is consulted first so future-non-throwing semantics (e.g. health-check
 *       endpoints that want to inspect availability without bombing) have a hook. Step 3
 *       intentionally does not add a separate non-throwing branch; the property is recorded for
 *       documentation symmetry with the Phase 4A/4B precedent and is consumed by the
 *       {@code BeanPostProcessor} validation gate (see {@link ExerisGraphQueryProcessor}).</li>
 * </ul>
 *
 * @since 0.7.0
 * @see <a href="../../../../../../../../docs/adr/ADR-030-phase-4c-spring-side-seam-for-kernel-graph-spi.md">ADR-030</a>
 */
public final class ExerisGraphTemplate {

    private final GraphEngineSupplier engineSupplier;
    private final ExerisGraphProperties properties;

    public ExerisGraphTemplate(GraphEngineSupplier engineSupplier, ExerisGraphProperties properties) {
        this.engineSupplier = Objects.requireNonNull(engineSupplier, "engineSupplier must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
    }

    /**
     * Open a {@link GraphSession}, run the callback, close the session.
     *
     * <p>The session is closed in a {@code finally} block regardless of whether the callback
     * threw. Any exception the callback throws (checked or unchecked) is wrapped only if it is
     * not already a {@link RuntimeException} — otherwise it surfaces as-is so kernel
     * {@code GraphQueryException} (and other {@code EX-GRPH-*} codes) reach the caller without
     * an extra wrapper layer.
     */
    public <T> T execute(ExerisGraphSessionCallback<T> action) {
        Objects.requireNonNull(action, "action must not be null");
        GraphEngine engine = resolveEngine();
        try (GraphSession session = engine.openSession()) {
            try {
                return action.withSession(session);
            } catch (RuntimeException re) {
                throw re;
            } catch (Exception ex) {
                throw new GraphTemplateExecutionException(
                        "ExerisGraphSessionCallback threw a checked exception", ex);
            }
        }
    }

    /** Convenience BFS — equivalent to {@code execute(s -> s.traverseBreadthFirst(traversal))}. */
    public List<UUID> traverseBfs(GraphTraversal traversal) {
        Objects.requireNonNull(traversal, "traversal must not be null");
        return execute(session -> session.traverseBreadthFirst(traversal));
    }

    /**
     * Zero-copy streaming BFS.
     *
     * <p><strong>Caller owns the returned {@link LoanedBuffer}.</strong> Use try-with-resources:
     * <pre>{@code
     * try (LoanedBuffer buffer = template.streamBfsJson(traversal)) {
     *     // consume buffer.segment() ...
     * }
     * }</pre>
     * The template does not retain a reference to the returned buffer and does not transfer
     * ownership to any other party.
     *
     * <h2>Session-close-before-return semantics</h2>
     *
     * <p>This method is a <strong>fully-materialised</strong> operation. The control flow is:
     *
     * <ol>
     *   <li>{@link #execute} opens a {@link GraphSession}.</li>
     *   <li>{@link GraphSession#streamBfsJson} fully populates the {@link LoanedBuffer}
     *       inside the callback.</li>
     *   <li>The {@code try}-with-resources around the session in {@link #execute} closes the
     *       session in its implicit {@code finally} block.</li>
     *   <li>{@code streamBfsJson} returns the buffer to the caller — <em>after</em> session
     *       close.</li>
     * </ol>
     *
     * <p>The buffer's validity after session close is guaranteed by the kernel SPI contract:
     * {@link LoanedBuffer} is an independently-owned off-heap slab; the session owns the DB
     * connection (and its cursor state), not the buffer's backing memory. {@code session.close()}
     * therefore does not reclaim the buffer.
     *
     * <p><strong>Lazy / cursor-based streaming requires a different API shape.</strong> A future
     * kernel implementation that materialises results lazily (e.g. a {@code bfsCursor()}
     * variant where the buffer is only partially populated and rows arrive on demand) cannot use
     * this wrapper — closing the session before the caller reads the buffer would invalidate
     * cursor state. When the kernel ships {@code GraphSession.bfsCursor()} (currently "Planned"
     * per {@code exeris-kernel/docs/subsystems/graph.md:149}), the Spring-side seam will add a
     * separate {@code streamBfsJsonCursor()}-style method that keeps the session open across
     * batch reads.
     */
    public LoanedBuffer streamBfsJson(GraphTraversal traversal) {
        Objects.requireNonNull(traversal, "traversal must not be null");
        return execute(session -> session.streamBfsJson(traversal));
    }

    /**
     * Open a session, begin a transaction, run the action, commit on success / rollback on
     * exception. Session is closed in a {@code finally} block in either case.
     *
     * <p>The transaction is the kernel {@link GraphSession}'s own — distinct from
     * {@code ExerisPlatformTransactionManager} (per ADR-030 §"What is NOT in scope" —
     * cross-resource transactions are not bridged at Phase 4C).
     */
    public void inTransaction(Consumer<GraphSession> action) {
        Objects.requireNonNull(action, "action must not be null");
        execute(session -> {
            session.beginTransaction();
            try {
                action.accept(session);
                session.commit();
            } catch (RuntimeException re) {
                safeRollback(session, re);
                throw re;
            }
            return null;
        });
    }

    /** Returns the active engine's {@link GraphDialect}. Throws if no engine is bound. */
    public GraphDialect dialect() {
        return resolveEngine().dialect();
    }

    private GraphEngine resolveEngine() {
        if (properties.requireEngine()) {
            return engineSupplier.requireEngine();
        }
        Optional<GraphEngine> engine = engineSupplier.tryGet();
        if (engine.isEmpty()) {
            throw new IllegalStateException(
                    "ExerisGraphTemplate cannot operate without a kernel GraphEngine. "
                            + "exeris.runtime.graph.require-engine=false was set (dev/test mode), "
                            + "but no engine is currently bound. Provide a GraphProvider on the "
                            + "classpath (e.g. exeris-kernel-community), or do not call template "
                            + "methods until an engine becomes available.");
        }
        return engine.get();
    }

    private static void safeRollback(GraphSession session, RuntimeException causedBy) {
        try {
            session.rollback();
        } catch (RuntimeException rollbackEx) {
            // Preserve the original failure as the primary cause; rollback failure attached as
            // suppressed so operators can see both in the stack trace.
            causedBy.addSuppressed(rollbackEx);
        }
    }

    /** Thrown only when an {@link ExerisGraphSessionCallback} surfaces a checked exception. */
    public static final class GraphTemplateExecutionException extends RuntimeException {

        public GraphTemplateExecutionException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
