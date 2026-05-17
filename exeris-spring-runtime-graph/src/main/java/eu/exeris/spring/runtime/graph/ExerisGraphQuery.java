/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.graph;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import eu.exeris.kernel.spi.graph.GraphDialect;

/**
 * Declarative marker for a Spring-bean method that performs a graph traversal through
 * {@link ExerisGraphTemplate}, per ADR-030 obligation 4.
 *
 * <h2>Method shape</h2>
 *
 * <p>Phase 4C Step 3 supports two annotated-method shapes; the
 * {@link ExerisGraphQueryProcessor} validates each at {@code BeanPostProcessor} time and fails
 * fast (before context refresh completes) on any other shape:
 *
 * <ul>
 *   <li><strong>Return type {@code List<UUID>}</strong> — routes to
 *       {@link ExerisGraphTemplate#traverseBfs}. The method must declare exactly one parameter
 *       of type {@link eu.exeris.kernel.spi.graph.model.GraphTraversal} carrying the start node,
 *       max depth, and edge type; the processor passes that argument straight through.</li>
 *   <li><strong>Return type {@link eu.exeris.kernel.spi.memory.LoanedBuffer}</strong> — routes
 *       to {@link ExerisGraphTemplate#streamBfsJson}. Same one-parameter
 *       {@link eu.exeris.kernel.spi.graph.model.GraphTraversal} contract. The caller owns the
 *       returned buffer per the ownership contract in {@link ExerisGraphTemplate#streamBfsJson}'s
 *       Javadoc.</li>
 * </ul>
 *
 * <h2>What this annotation is NOT</h2>
 *
 * <p>Per ADR-030 §"What is NOT in scope" / module-boundaries §"Forbidden":
 *
 * <ul>
 *   <li>It is <strong>not</strong> a Spring Data repository abstraction — no entity manager,
 *       no dynamic query derivation from method names, no {@code findByXxxAndYyy}-style magic.</li>
 *   <li>It does <strong>not</strong> parse a custom MATCH-DSL string at this step. The
 *       {@link #value()} attribute is reserved for forward compatibility with a kernel-side
 *       parser (kernel currently exposes only the {@link
 *       eu.exeris.kernel.spi.graph.model.GraphTraversal} record); for Phase 4C Step 3 the
 *       processor does not interpret {@link #value()} and applications can leave it empty.
 *       When the kernel adds a parser, the processor will be extended to pass the value through
 *       — without an API surface change visible to callers.</li>
 * </ul>
 *
 * <h2>Compilation requirement</h2>
 *
 * <p>Spring Boot already requires {@code -parameters} for {@code @ConfigurationProperties}
 * record binding; the Phase 4C bridge inherits that requirement and exposes a clear error
 * message at post-processing time if the offending method is on a class compiled without the
 * flag.
 *
 * @since 0.7.0
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface ExerisGraphQuery {

    /**
     * Optional MATCH-DSL string for a future kernel-side parser. Phase 4C Step 3 does not
     * interpret this — the annotated method's single {@code GraphTraversal} parameter carries
     * all dispatch state. The attribute is reserved so application code that writes
     * placeholder annotations now does not need to re-annotate when a parser arrives.
     */
    String value() default "";

    /**
     * Optional dialect override. Defaults to the engine's
     * {@link eu.exeris.kernel.spi.graph.GraphEngine#dialect() dialect()}.
     *
     * <p>Phase 4C Step 3 does not switch dialects per call — the engine's dialect is the only
     * effective one and this attribute is informational. When multi-dialect routing arrives
     * (post-1.0), the processor will honour this override.
     */
    Class<? extends GraphDialect> dialect() default GraphDialect.class;
}
