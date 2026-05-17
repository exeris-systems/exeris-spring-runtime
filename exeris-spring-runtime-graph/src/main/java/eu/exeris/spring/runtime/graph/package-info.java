/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
/**
 * Phase 4C — Spring-side seam for the kernel Graph SPI, per ADR-030
 * (1.0 preview, default-off).
 *
 * <p>This package exposes a Spring-friendly facade over the kernel
 * {@code eu.exeris.kernel.spi.graph.*} surface — {@code ExerisGraphTemplate}
 * over {@code GraphSession}, {@code @ExerisGraphQuery} for declarative
 * parameterised MATCH-DSL strings, and a {@code GraphEngineSupplier}
 * deferred-accessor seam analogous to the {@code EventEngineSupplier} /
 * {@code FlowEngineSupplier} patterns from Phase 4A/4B.
 *
 * <h2>Scope (ADR-030)</h2>
 *
 * <p>What this module delivers in the 0.7.0-preview train:
 * <ul>
 *   <li>{@code GraphEngineSupplier} — interface seam backed by
 *       {@code ExerisRuntimeLifecycle}'s captured {@code GraphEngine}
 *       reference.</li>
 *   <li>{@code ExerisGraphProperties} — {@code exeris.runtime.graph.enabled}
 *       (default {@code false}) + {@code require-engine} (default
 *       {@code true}).</li>
 *   <li>{@code ExerisGraphAutoConfiguration} — opt-in autoconfig that wires
 *       the supplier, template, and {@code @ExerisGraphQuery}
 *       {@code BeanPostProcessor}.</li>
 *   <li>{@code ExerisGraphTemplate} — JdbcTemplate-shaped facade with
 *       {@code execute(ExerisGraphSessionCallback)}, {@code traverseBfs},
 *       {@code streamBfsJson} (caller owns the returned {@code LoanedBuffer}
 *       and releases via try-with-resources),
 *       {@code inTransaction(Consumer<GraphSession>)}, and
 *       {@code dialect()}.</li>
 *   <li>{@code @ExerisGraphQuery} + {@code ExerisGraphQueryProcessor} —
 *       declarative parameterised MATCH-DSL annotation routed at
 *       {@code BeanPostProcessor} time; unsupported return types fail fast
 *       before context refresh completes.</li>
 * </ul>
 *
 * <h2>Out of scope</h2>
 *
 * <p>Per ADR-030 §"What is NOT in scope": fluent {@code GraphQueryBuilder}
 * DSL, {@code GraphCursor} unbounded traversal API, Spring Data Neo4j
 * compatibility, multi-engine fan-out, cross-resource transactions,
 * {@code @RequiresRole} integration, and native-image / AOT hints.
 *
 * <h2>Activation</h2>
 *
 * <pre>{@code
 * exeris.runtime.graph.enabled=true            # default false
 * exeris.runtime.graph.require-engine=false    # default true; opt-in for dev/test
 * }</pre>
 *
 * <h2>Graduation</h2>
 *
 * <p>GA graduation from 1.0 preview to 1.0.x bounded GA is <strong>kernel-gated</strong>
 * on the kernel-side Community {@code GraphChurnRatioTck} binding green in
 * CI (kernel Sprint 7, ETA inside the {@code exeris-kernel} v0.8 cycle).
 * The Spring-side seam is decoupled from that gating for <em>landing</em>;
 * the seam exposes whatever the kernel ships today.
 *
 * @see <a href="../../../../../../../../docs/adr/ADR-030-phase-4c-spring-side-seam-for-kernel-graph-spi.md">ADR-030</a>
 * @since 0.6.0
 */
package eu.exeris.spring.runtime.graph;
