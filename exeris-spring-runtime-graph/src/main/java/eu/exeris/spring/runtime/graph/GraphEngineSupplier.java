/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.graph;

import java.util.Optional;

import eu.exeris.kernel.spi.graph.GraphEngine;

/**
 * Deferred accessor seam for the kernel-captured {@link GraphEngine}, per ADR-030
 * obligation 5.
 *
 * <p>Mirrors the {@code EventEngineSupplier} (Phase 4A) and {@code FlowEngineSupplier}
 * (Phase 4B) interface pattern: the supplier is a Spring bean, the engine itself is read
 * per call from {@code ExerisRuntimeLifecycle}'s captured {@code AtomicReference}.
 * Per Phase 4A invariant §7, the kernel engine is "resolved per call, never captured at
 * bean construction or autoconfiguration time" — the supplier is the resolution layer
 * over the lifecycle's storage layer.
 *
 * <p>Two methods:
 *
 * <ul>
 *   <li>{@link #tryGet()} — tolerant accessor; returns {@link Optional#empty()} when the
 *       kernel did not provide an engine (no {@code GraphProvider} on the classpath, or
 *       the kernel scope never opened). Implementations must not throw.</li>
 *   <li>{@link #requireEngine()} — fail-loud variant; throws
 *       {@link IllegalStateException} with an operator-readable diagnostic message when
 *       {@link #tryGet()} returns empty. The default implementation handles the
 *       throwing; concrete suppliers do not override it.</li>
 * </ul>
 *
 * @since 0.7.0
 */
@FunctionalInterface
public interface GraphEngineSupplier {

    /**
     * Returns the captured engine if one is available, or empty if the kernel ran
     * without a graph subsystem. Implementations must not throw.
     */
    Optional<GraphEngine> tryGet();

    /**
     * Returns the captured engine, throwing {@link IllegalStateException} if no
     * engine is available. Use when an operator has opted into the bridge
     * ({@code exeris.runtime.graph.enabled=true}) with {@code require-engine=true}
     * (default) and the absence of a kernel engine is a configuration error rather
     * than a tolerable dev-environment state.
     */
    default GraphEngine requireEngine() {
        return tryGet().orElseThrow(() -> new IllegalStateException(
                "Exeris kernel GraphEngine is not available — kernel has not booted, "
                        + "or no GraphProvider was active during bootstrap. The "
                        + "exeris-spring-runtime-graph autoconfig opted into the graph "
                        + "bridge (exeris.runtime.graph.enabled=true) with require-engine=true "
                        + "(default). Either provide a GraphProvider on the classpath "
                        + "(e.g. exeris-kernel-community with PostgreSQL PGQ / Neo4j Bolt / "
                        + "Memgraph Bolt drivers), or set "
                        + "exeris.runtime.graph.require-engine=false in dev / test environments "
                        + "where the kernel graph provider is intentionally absent (the template "
                        + "will be constructed but every method will throw until an engine "
                        + "becomes available)."));
    }
}
