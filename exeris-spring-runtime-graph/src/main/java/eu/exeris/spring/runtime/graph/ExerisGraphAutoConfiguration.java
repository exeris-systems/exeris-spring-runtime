/*
 * Copyright (C) 2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
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
import eu.exeris.spring.boot.autoconfigure.KernelProviderScope;

/**
 * Autoconfiguration for the Exeris Graph bridge module.
 *
 * <p>Activates only when {@code exeris.runtime.graph.enabled=true} is set explicitly
 * ({@code matchIfMissing = false}); the conditional is also gated on {@link GraphEngine}
 * being on the classpath and an {@link ExerisRuntimeLifecycle} bean being available to
 * wire the {@link GraphEngineSupplier}.
 *
 * <h2>Components Configured</h2>
 * <ul>
 *   <li>{@link GraphEngineSupplier} — deferred accessor wired to
 *       {@link ExerisRuntimeLifecycle#getGraphEngine()}.</li>
 *   <li>{@link KernelProviderScope} — re-binds the kernel persistence engine and memory
 *       allocator around each template session on threads outside the kernel scope.</li>
 *   <li>{@link ExerisGraphProperties} — two-property activation matrix
 *       ({@code enabled} + {@code require-engine}).</li>
 *   <li>{@link ExerisGraphTemplate} — imperative facade with
 *       {@code execute} / {@code traverseBfs} / {@code streamBfsJson} (caller-owns-buffer
 *       contract) / {@code inTransaction} / {@code dialect}.</li>
 *   <li>{@link ExerisGraphQuery} + {@link ExerisGraphQueryProcessor} — declarative
 *       annotation + {@code BeanPostProcessor} that validates at post-processing time
 *       (fail-fast) and routes annotated method calls through the template by return type.</li>
 * </ul>
 *
 * <h2>What This Does NOT Do</h2>
 * <p>Does not own transport, web handling, transactions, or persistence. Does not bridge
 * Spring Data Neo4j. Does not provide a fluent {@code GraphQueryBuilder} DSL or a {@code GraphCursor}
 * unbounded-traversal API — both are explicitly out of scope per ADR-030 §"What is NOT in scope".
 *
 * @since 0.7
 * @see "ADR-030: Phase 4C Spring-Side Seam for Kernel Graph SPI"
 */
@AutoConfiguration(after = ExerisRuntimeAutoConfiguration.class)
@ConditionalOnClass(GraphEngine.class)
@ConditionalOnBean(ExerisRuntimeLifecycle.class)
@ConditionalOnProperty(prefix = "exeris.runtime.graph", name = "enabled", havingValue = "true", matchIfMissing = false)
@EnableConfigurationProperties(ExerisGraphProperties.class)
public class ExerisGraphAutoConfiguration {

    /**
     * Default constructor for auto-configuration.
     */
    public ExerisGraphAutoConfiguration() {
    }

    /**
     * Default {@link GraphEngineSupplier} backed by {@link ExerisRuntimeLifecycle}'s
     * captured {@code GraphEngine} reference.
     *
     * @param lifecycle the runtime lifecycle providing the graph engine reference
     * @return the configured graph engine supplier
     */
    @Bean
    @ConditionalOnMissingBean
    public GraphEngineSupplier exerisGraphEngineSupplier(ExerisRuntimeLifecycle lifecycle) {
        return lifecycle::getGraphEngine;
    }

    /**
     * Provider scope for template sessions. Kernel graph backends read the persistence engine
     * and the memory allocator from their {@code ScopedValue} slots, which are unbound on Spring
     * threads; the scope re-binds them from the lifecycle's captured references, read lazily at
     * call time, and leaves already-bound slots untouched.
     *
     * @param lifecycle the runtime lifecycle holding the captured references
     * @return the {@link KernelProviderScope}
     */
    @Bean
    @ConditionalOnMissingBean
    public KernelProviderScope exerisGraphKernelProviderScope(ExerisRuntimeLifecycle lifecycle) {
        return KernelProviderScope.fromLifecycle(lifecycle);
    }

    /**
     * Imperative facade over the kernel {@link GraphEngine} / {@code GraphSession} SPI.
     *
     * @param engineSupplier supplier for the graph engine
     * @param properties     graph configuration properties
     * @param providerScope  provider scope wrapping every template session
     * @return the configured graph template
     */
    @Bean
    @ConditionalOnMissingBean
    public ExerisGraphTemplate exerisGraphTemplate(GraphEngineSupplier engineSupplier,
                                                    ExerisGraphProperties properties,
                                                    KernelProviderScope providerScope) {
        return new ExerisGraphTemplate(engineSupplier, properties, providerScope);
    }

    /**
     * {@code BeanPostProcessor} that validates {@link ExerisGraphQuery}-annotated methods at
     * post-processing time and installs a Spring AOP proxy routing annotated calls through
     * {@link ExerisGraphTemplate}.
     *
     * @param template the graph template to route queries through
     * @return the configured graph query processor
     */
    @Bean
    @ConditionalOnMissingBean
    public ExerisGraphQueryProcessor exerisGraphQueryProcessor(ExerisGraphTemplate template) {
        return new ExerisGraphQueryProcessor(template);
    }
}
