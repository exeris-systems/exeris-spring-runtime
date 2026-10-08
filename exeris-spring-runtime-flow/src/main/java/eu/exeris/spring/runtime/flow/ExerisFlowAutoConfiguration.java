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
import eu.exeris.spring.boot.autoconfigure.KernelProviderScope;
import eu.exeris.spring.runtime.events.EventEngineSupplier;
import eu.exeris.spring.runtime.events.ExerisEventAutoConfiguration;
import eu.exeris.spring.runtime.events.ExerisEventPublisher;

/**
 * Autoconfiguration for the Exeris Flow / Saga bridge module.
 *
 * <p>Activates only when {@code exeris.runtime.flow.enabled=true} is set explicitly
 * ({@code matchIfMissing = false}); the conditional is also gated on
 * {@link FlowEngine} being on the classpath and an {@link ExerisRuntimeLifecycle}
 * bean being available to wire the {@link FlowEngineSupplier}.
 *
 * <h2>Declarative and Imperative Components</h2>
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
 * <h2>Choreography Bridge</h2>
 * <ul>
 *   <li>{@link ExerisFlowChoreographyBridge} — discovers
 *       {@link ExerisFlowChoreographyMapper} beans and registers each one with the kernel
 *       {@link FlowEngine} via {@code registerChoreographyMapper}. Activation requires
 *       {@code exeris.runtime.flow.choreography-enabled=true}, an active events module
 *       (via {@link ExerisEventPublisher}-bean presence), and the kernel engine reporting
 *       {@code FlowEngineCapabilities.choreographySupport()=true}.</li>
 * </ul>
 *
 * <h2>What This Does NOT Do</h2>
 * <p>Does not own transport, web handling, transactions, or persistence. Does not wire
 * Spring {@code ApplicationEventPublisher} into the kernel — flow choreography
 * reads from the kernel {@code EventBus} via the events module bridge. Does not provide
 * {@code @Async} compatibility — asynchronous execution is managed by the kernel flow engine.
 *
 * @since 0.1
 */
@AutoConfiguration(after = ExerisEventAutoConfiguration.class)
@ConditionalOnClass(FlowEngine.class)
@ConditionalOnBean(ExerisRuntimeLifecycle.class)
@ConditionalOnProperty(prefix = "exeris.runtime.flow", name = "enabled", havingValue = "true", matchIfMissing = false)
@EnableConfigurationProperties(ExerisFlowProperties.class)
public class ExerisFlowAutoConfiguration {

    /**
     * Default constructor for auto-configuration.
     */
    public ExerisFlowAutoConfiguration() {
    }

    /**
     * Creates the supplier for accessing the kernel flow engine.
     *
     * @param lifecycle the runtime lifecycle owner
     * @return the {@link FlowEngineSupplier}
     */
    @Bean
    @ConditionalOnMissingBean
    public FlowEngineSupplier exerisFlowEngineSupplier(ExerisRuntimeLifecycle lifecycle) {
        return lifecycle::getFlowEngine;
    }

    /**
     * Creates the imperative flow execution template.
     *
     * @param engineSupplier supplier for the kernel flow engine
     * @return the {@link ExerisFlowTemplate}
     */
    @Bean
    @ConditionalOnMissingBean
    public ExerisFlowTemplate exerisFlowTemplate(FlowEngineSupplier engineSupplier) {
        return new ExerisFlowTemplate(engineSupplier);
    }

    /**
     * Kernel provider scope used to re-bind {@code KernelProviders} {@code ScopedValue} slots
     * (persistence engine, memory allocator) around each {@code FlowStepAction} execution. Flow
     * steps run on kernel flow scheduler worker virtual threads, which do not inherit the
     * bootstrap scope &mdash; without this, application step bodies that reach the kernel persistence
     * engine through slot readers (the compat {@code ExerisDataSource} path in particular) fail
     * with "PersistenceEngine is not bound in the current scope". Mirrors the request-path
     * {@code KernelProviderBinder} wiring in the web module.
     *
     * <p>The accessors read the lifecycle's captured references lazily at step execution time,
     * so wiring order relative to kernel boot does not matter.
     *
     * @param lifecycle the runtime lifecycle owner
     * @return the {@link KernelProviderScope}
     */
    @Bean
    @ConditionalOnMissingBean
    public KernelProviderScope exerisFlowKernelProviderScope(ExerisRuntimeLifecycle lifecycle) {
        return KernelProviderScope.fromLifecycle(lifecycle);
    }

    /**
     * Creates the flow definition registrar lifecycle bean.
     *
     * @param applicationContext the application context
     * @param engineSupplier supplier for the kernel flow engine
     * @param template the flow template
     * @param properties configuration properties
     * @param providerScope provider scope for context propagation
     * @return the {@link ExerisFlowDefinitionRegistrar}
     */
    @Bean
    @ConditionalOnMissingBean
    public ExerisFlowDefinitionRegistrar exerisFlowDefinitionRegistrar(ApplicationContext applicationContext,
                                                                        FlowEngineSupplier engineSupplier,
                                                                        ExerisFlowTemplate template,
                                                                        ExerisFlowProperties properties,
                                                                        KernelProviderScope providerScope) {
        return new ExerisFlowDefinitionRegistrar(applicationContext, engineSupplier, template, properties,
                providerScope);
    }

    /**
     * Choreography bridge bean. Activated only when:
     * <ul>
     *   <li>{@code exeris.runtime.flow.choreography-enabled=true} (opt-in; default {@code false}),</li>
     *   <li>an {@link ExerisEventPublisher} bean is present (the events module is active and
     *       {@code exeris.runtime.events.enabled=true} resolved successfully),</li>
     *   <li>no user-supplied {@link ExerisFlowChoreographyBridge} bean already exists.</li>
     * </ul>
     *
     * <p>The kernel-side capability ({@code FlowEngineCapabilities.choreographySupport()}) is
     * checked at lifecycle {@code start()} rather than as a bean condition: capabilities
     * cannot be probed until the kernel has booted, but bean wiring runs during refresh.
     *
     * @param applicationContext the application context
     * @param flowEngineSupplier supplier for the kernel flow engine
     * @param eventEngineSupplier supplier for the kernel event engine
     * @param properties configuration properties
     * @return the {@link ExerisFlowChoreographyBridge}
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(ExerisEventPublisher.class)
    @ConditionalOnProperty(prefix = "exeris.runtime.flow", name = "choreography-enabled",
            havingValue = "true", matchIfMissing = false)
    public ExerisFlowChoreographyBridge exerisFlowChoreographyBridge(ApplicationContext applicationContext,
                                                                      FlowEngineSupplier flowEngineSupplier,
                                                                      EventEngineSupplier eventEngineSupplier,
                                                                      ExerisFlowProperties properties) {
        return new ExerisFlowChoreographyBridge(
                applicationContext, flowEngineSupplier, eventEngineSupplier, properties);
    }
}
