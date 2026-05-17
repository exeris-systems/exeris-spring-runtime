/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.graph;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import eu.exeris.kernel.spi.graph.GraphEngine;
import eu.exeris.spring.boot.autoconfigure.ExerisRuntimeAutoConfiguration;
import eu.exeris.spring.boot.autoconfigure.ExerisRuntimeLifecycle;

/**
 * Autoconfiguration for the Exeris Graph bridge module (Phase 4C, per ADR-030).
 *
 * <p>Activates only when {@code exeris.runtime.graph.enabled=true} is set explicitly
 * ({@code matchIfMissing = false}); the conditional is also gated on {@link GraphEngine}
 * being on the classpath and an {@link ExerisRuntimeLifecycle} bean being available to
 * wire the {@link GraphEngineSupplier}.
 *
 * <h2>Step 2 (this PR) — autoconfig + properties + lifecycle capture</h2>
 *
 * <ul>
 *   <li>{@link GraphEngineSupplier} — deferred accessor wired to
 *       {@link ExerisRuntimeLifecycle#getGraphEngine()}.</li>
 *   <li>{@link ExerisGraphProperties} — two-property activation matrix
 *       ({@code enabled} + {@code require-engine}).</li>
 * </ul>
 *
 * <h2>Future steps in the 0.7.0-preview train (per ADR-030 Engineering Protocol)</h2>
 *
 * <ul>
 *   <li>Step 3 — {@code ExerisGraphTemplate} (JdbcTemplate-shaped facade) +
 *       {@code @ExerisGraphQuery} (declarative MATCH-DSL) + {@code BeanPostProcessor}.</li>
 *   <li>Step 4 — Architecture guards ({@code GraphModuleBoundaryTest},
 *       {@code PureModeClasspathGuardTest}).</li>
 *   <li>Step 5 — Integration tests with kernel-community PGQ test-scope.</li>
 * </ul>
 *
 * <h2>What This Does NOT Do</h2>
 *
 * <p>Does not own transport, web handling, transactions, or persistence. Does not bridge
 * Spring Data Neo4j (`org.springframework.data..` banned by `GraphModuleBoundaryTest`,
 * Step 4). Does not provide a fluent {@code GraphQueryBuilder} DSL or a {@code GraphCursor}
 * unbounded-traversal API — both are explicitly out of scope per ADR-030 §"What is NOT in
 * scope".
 *
 * @since 0.7.0
 * @see <a href="../../../../../../../../docs/adr/ADR-030-phase-4c-spring-side-seam-for-kernel-graph-spi.md">ADR-030</a>
 */
@AutoConfiguration(after = ExerisRuntimeAutoConfiguration.class)
@ConditionalOnClass(GraphEngine.class)
@ConditionalOnBean(ExerisRuntimeLifecycle.class)
@ConditionalOnProperty(prefix = "exeris.runtime.graph", name = "enabled", havingValue = "true", matchIfMissing = false)
@EnableConfigurationProperties(ExerisGraphProperties.class)
public class ExerisGraphAutoConfiguration {

    /**
     * Default {@link GraphEngineSupplier} backed by {@link ExerisRuntimeLifecycle}'s
     * captured {@code GraphEngine} reference. Implements the deferred-accessor pattern
     * per Phase 4A invariant §7 — the engine itself is read per call, the supplier is the
     * bean.
     */
    @Bean
    @ConditionalOnMissingBean
    public GraphEngineSupplier exerisGraphEngineSupplier(ExerisRuntimeLifecycle lifecycle) {
        return lifecycle::getGraphEngine;
    }

    /**
     * JdbcTemplate-shaped facade over the kernel {@link GraphEngine} / {@code GraphSession}
     * SPI (Step 3 per ADR-030 obligation 3). Consumes both the supplier (for per-call engine
     * resolution per Phase 4A invariant §7) and the properties record (for
     * {@code requireEngine} behaviour).
     */
    @Bean
    @ConditionalOnMissingBean
    public ExerisGraphTemplate exerisGraphTemplate(GraphEngineSupplier engineSupplier,
                                                    ExerisGraphProperties properties) {
        return new ExerisGraphTemplate(engineSupplier, properties);
    }

    /**
     * {@code BeanPostProcessor} that validates {@link ExerisGraphQuery}-annotated methods at
     * post-processing time and installs a Spring AOP proxy routing annotated calls through
     * {@link ExerisGraphTemplate} (Step 3 per ADR-030 obligation 4 — fail-fast at
     * post-processing, never at runtime invocation).
     */
    @Bean
    @ConditionalOnMissingBean
    public ExerisGraphQueryProcessor exerisGraphQueryProcessor(ExerisGraphTemplate template) {
        return new ExerisGraphQueryProcessor(template);
    }
}
