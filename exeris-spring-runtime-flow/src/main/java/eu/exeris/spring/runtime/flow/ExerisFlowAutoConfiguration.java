/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.flow;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;

import eu.exeris.kernel.spi.flow.FlowEngine;
import eu.exeris.spring.boot.autoconfigure.ExerisRuntimeLifecycle;

/**
 * Autoconfiguration for the Exeris Flow / Saga bridge module (Phase 4B).
 *
 * <p>Activates only when {@code exeris.runtime.flow.enabled=true} is set explicitly
 * ({@code matchIfMissing = false}); the conditional is also gated on
 * {@link FlowEngine} being on the classpath and an {@link ExerisRuntimeLifecycle}
 * bean being available to wire the {@link FlowEngineSupplier}.
 *
 * <h2>Step 2 (current) — declarative + imperative surface</h2>
 * <ul>
 *   <li>{@link FlowEngineSupplier} — deferred {@code ScopedValue} accessor wired to
 *       {@link ExerisRuntimeLifecycle#getFlowEngine()}.</li>
 *   <li>{@link ExerisFlowTemplate} — imperative invocation facade (schedule, park, wake,
 *       lookupParked, stats, plan registry).</li>
 *   <li>{@link ExerisFlowDefinitionRegistrar} — discovers {@link ExerisFlowDefinition}
 *       beans, compiles their {@code FlowExecutionPlan}s at lifecycle start, and
 *       populates the template's plan registry. Tolerates a missing engine when
 *       {@code exeris.runtime.flow.require-engine=false} (test/dev only).</li>
 * </ul>
 *
 * <p>Step 3 will add {@code ExerisFlowChoreographyBridge} — opt-in event-driven flow
 * trigger, gated additionally on {@code FlowEngineCapabilities.choreographySupport()}.
 *
 * <h2>What This Does NOT Do</h2>
 * <p>Does not own transport, web handling, transactions, or persistence. Does not wire
 * Spring {@code ApplicationEventPublisher} into the kernel — flow choreography (Step 3)
 * reads from the kernel {@code EventBus} via the events module bridge. Does not provide
 * {@code @Async} compatibility — {@code @Async} is explicitly NOT a workaround for
 * missing flow capability.
 *
 * @since 0.1.0
 */
@AutoConfiguration
@ConditionalOnClass(FlowEngine.class)
@ConditionalOnBean(ExerisRuntimeLifecycle.class)
@ConditionalOnProperty(prefix = "exeris.runtime.flow", name = "enabled", havingValue = "true", matchIfMissing = false)
@EnableConfigurationProperties(ExerisFlowProperties.class)
public class ExerisFlowAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public FlowEngineSupplier exerisFlowEngineSupplier(ExerisRuntimeLifecycle lifecycle) {
        return lifecycle::getFlowEngine;
    }

    @Bean
    @ConditionalOnMissingBean
    public ExerisFlowTemplate exerisFlowTemplate(FlowEngineSupplier engineSupplier) {
        return new ExerisFlowTemplate(engineSupplier);
    }

    @Bean
    @ConditionalOnMissingBean
    public ExerisFlowDefinitionRegistrar exerisFlowDefinitionRegistrar(ApplicationContext applicationContext,
                                                                        FlowEngineSupplier engineSupplier,
                                                                        ExerisFlowTemplate template,
                                                                        ExerisFlowProperties properties) {
        return new ExerisFlowDefinitionRegistrar(applicationContext, engineSupplier, template, properties);
    }
}
