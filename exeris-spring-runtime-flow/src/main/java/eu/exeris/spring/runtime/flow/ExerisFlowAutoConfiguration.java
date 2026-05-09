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
 * <h2>Step 1 (this commit) — module skeleton only</h2>
 * <p>Currently exposes only:
 * <ul>
 *   <li>{@link FlowEngineSupplier} bean — wired to
 *       {@link ExerisRuntimeLifecycle#getFlowEngine()}.</li>
 * </ul>
 *
 * <p>Subsequent Phase 4B steps will add:
 * <ul>
 *   <li>{@code ExerisFlowDefinition} — declarative flow DSL,</li>
 *   <li>{@code ExerisFlowTemplate} — imperative flow invocation surface,</li>
 *   <li>{@code ExerisFlowChoreographyBridge} — opt-in event-driven flow trigger,
 *       gated additionally on {@code FlowEngineCapabilities.choreographySupport()}.</li>
 * </ul>
 *
 * <h2>What This Does NOT Do</h2>
 * <p>Does not own transport, web handling, transactions, or persistence. Does not wire
 * Spring {@code ApplicationEventPublisher} into the kernel — flow choreography (when
 * enabled in a later step) reads from the kernel {@code EventBus} via the events
 * module's bridge. Does not provide {@code @Async} compatibility — {@code @Async} is
 * explicitly NOT a workaround for missing flow capability.
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
}
