/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
/**
 * Spring-side seam for the kernel Graph SPI.
 *
 * <p>This package exposes a Spring-friendly facade over the kernel
 * {@code eu.exeris.kernel.spi.graph.*} surface — {@code ExerisGraphTemplate}
 * over {@code GraphSession}, {@code @ExerisGraphQuery} for declarative
 * parameterised MATCH-DSL strings, and a {@code GraphEngineSupplier}
 * deferred-accessor seam.
 *
 * <h2>Scope</h2>
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
 *   <li>{@code ExerisGraphTemplate} — facade with
 *       {@code execute(ExerisGraphSessionCallback)}, {@code traverseBfs},
 *       {@code streamBfsJson} (caller owns the returned {@code LoanedBuffer}
 *       and releases via try-with-resources),
 *       {@code inTransaction(Consumer<GraphSession>)}, and
 *       {@code dialect()}.</li>
 *   <li>{@code @ExerisGraphQuery} + {@code ExerisGraphQueryProcessor} —
 *       declarative parameterised MATCH-DSL annotation routed at
 *       {@code BeanPostProcessor} time.</li>
 * </ul>
 *
 * <h2>Out of scope</h2>
 * <p>Per ADR-030 §"What is NOT in scope": fluent {@code GraphQueryBuilder}
 * DSL, {@code GraphCursor} unbounded traversal API, Spring Data Neo4j
 * compatibility, multi-engine fan-out, cross-resource transactions,
 * {@code @RequiresRole} integration, and native-image / AOT hints.
 *
 * <h2>Activation</h2>
 * {@snippet lang="properties" :
 * exeris.runtime.graph.enabled=true
 * exeris.runtime.graph.require-engine=false
 * }
 *
 * @since 0.7
 * @see "ADR-030: Phase 4C Spring-Side Seam for Kernel Graph SPI"
 */
package eu.exeris.spring.runtime.graph;
