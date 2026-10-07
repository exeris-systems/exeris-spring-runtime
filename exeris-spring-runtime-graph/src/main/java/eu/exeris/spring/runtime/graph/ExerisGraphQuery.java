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
 * <p>Supports two annotated-method shapes; the
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
 *   <li>It does <strong>not</strong> parse a custom MATCH-DSL string directly. The
 *       {@link #value()} attribute is reserved for compatibility with the kernel graph engine;
 *       the annotated method's parameter carries the traversal configuration.</li>
 * </ul>
 *
 * <h2>Compilation requirement</h2>
 *
 * <p>Spring Boot already requires {@code -parameters} for {@code @ConfigurationProperties}
 * record binding; the graph bridge inherits that requirement and exposes a clear error
 * message at post-processing time if the offending method is on a class compiled without the
 * flag.
 *
 * @since 0.7
 * @see "ADR-030: Phase 4C Spring-Side Seam for Kernel Graph SPI"
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface ExerisGraphQuery {

    /**
     * Optional MATCH-DSL string. Currently informational; dispatch state is carried
     * by the {@code GraphTraversal} parameter.
     *
     * @return the MATCH-DSL query string
     */
    String value() default "";

    /**
     * Optional dialect override. Defaults to the engine's
     * {@link eu.exeris.kernel.spi.graph.GraphEngine#dialect() dialect()}.
     *
     * @return the dialect class
     */
    Class<? extends GraphDialect> dialect() default GraphDialect.class;
}
