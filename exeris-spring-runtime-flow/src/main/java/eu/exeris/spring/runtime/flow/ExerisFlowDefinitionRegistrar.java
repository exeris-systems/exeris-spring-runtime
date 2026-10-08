/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.flow;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.ApplicationContext;
import org.springframework.context.SmartLifecycle;

import eu.exeris.kernel.spi.flow.FlowDefinitionBuilder;
import eu.exeris.kernel.spi.flow.FlowEngine;
import eu.exeris.kernel.spi.flow.FlowExecutionPlanFactory;
import eu.exeris.kernel.spi.flow.model.FlowDefinition;
import eu.exeris.kernel.spi.flow.model.FlowExecutionPlan;
import eu.exeris.spring.boot.autoconfigure.KernelProviderScope;

/**
 * Discovers {@link ExerisFlowDefinition} beans, builds and compiles their kernel
 * {@code FlowDefinition}s, and populates the {@link ExerisFlowTemplate} plan registry.
 *
 * <h2>Lifecycle</h2>
 * <p>Implements both {@link SmartInitializingSingleton} (collects definition beans at
 * the end of context refresh, before any kernel boot) and {@link SmartLifecycle} (does
 * the actual builder + compile + register at start, clears the template registry at
 * stop). Splitting the two phases is necessary because the kernel {@link FlowEngine} is
 * not available until {@code ExerisRuntimeLifecycle} has booted, which happens during
 * the {@code SmartLifecycle} start sequence after context refresh has completed.
 *
 * <h2>Phase Ordering</h2>
 * <p>Phase {@code Integer.MAX_VALUE - 99} runs immediately after the kernel lifecycle
 * (which sits at {@code Integer.MAX_VALUE - 100}), so the kernel is booted and the
 * {@link FlowEngine} reference is captured by the time {@link #start()} fires. This is
 * the same phase as {@code ExerisEventListenerRegistrar} — the two run in the same
 * batch but neither depends on the other (events and flow are independent subsystems
 * at the bridge layer; choreography wiring couples them via {@code ExerisFlowChoreographyBridge}).
 *
 * <h2>Engine Requirement</h2>
 * <p>When {@code exeris.runtime.flow.require-engine=true} (the default), lifecycle start fails
 * if {@code ExerisFlowDefinition} beans are declared but no {@code FlowEngine} is bound.
 * Setting {@code exeris.runtime.flow.require-engine=false} disables this check.
 *
 * @since 0.5
 */
public final class ExerisFlowDefinitionRegistrar implements SmartInitializingSingleton, SmartLifecycle {

    private static final int PHASE = Integer.MAX_VALUE - 99;
    private static final Logger LOG = System.getLogger(ExerisFlowDefinitionRegistrar.class.getName());

    private final ApplicationContext applicationContext;
    private final FlowEngineSupplier engineSupplier;
    private final ExerisFlowTemplate template;
    private final ExerisFlowProperties properties;
    private final KernelProviderScope providerScope;
    private final Object lifecycleLock = new Object();

    private final List<DefinitionBinding> bindings = new ArrayList<>();
    private volatile boolean running = false;

    /**
     * Creates a new registrar instance.
     *
     * @param applicationContext the application context used to discover definition beans
     * @param engineSupplier the accessor for the kernel flow engine
     * @param template the flow template whose plan registry is populated
     * @param properties flow configuration properties
     * @param providerScope the kernel provider scope captured from lifecycle
     */
    public ExerisFlowDefinitionRegistrar(ApplicationContext applicationContext,
                                          FlowEngineSupplier engineSupplier,
                                          ExerisFlowTemplate template,
                                          ExerisFlowProperties properties,
                                          KernelProviderScope providerScope) {
        this.applicationContext = Objects.requireNonNull(applicationContext, "applicationContext");
        this.engineSupplier = Objects.requireNonNull(engineSupplier, "engineSupplier");
        this.template = Objects.requireNonNull(template, "template");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.providerScope = Objects.requireNonNull(providerScope, "providerScope");
    }

    @Override
    public void afterSingletonsInstantiated() {
        bindings.clear();
        Map<String, ExerisFlowDefinition> beans =
                applicationContext.getBeansOfType(ExerisFlowDefinition.class);
        Set<String> seenNames = new HashSet<>();
        for (Map.Entry<String, ExerisFlowDefinition> entry : beans.entrySet()) {
            String beanName = entry.getKey();
            ExerisFlowDefinition def = entry.getValue();
            String flowName = def.name();
            if (flowName == null || flowName.isBlank()) {
                throw new IllegalStateException(
                        "ExerisFlowDefinition bean '" + beanName
                                + "' returned a null/blank name() — flow names must be non-empty.");
            }
            if (!seenNames.add(flowName)) {
                throw new IllegalStateException(
                        "Duplicate ExerisFlowDefinition.name()='" + flowName
                                + "'. Each flow definition must have a unique name within the "
                                + "application context. Conflicting bean: '" + beanName + "'.");
            }
            bindings.add(new DefinitionBinding(beanName, flowName, def));
        }
    }

    @Override
    public void start() {
        synchronized (lifecycleLock) {
            if (running) {
                return;
            }
            // Two cases when the kernel did not bind a FlowEngine:
            //   - bindings.isEmpty(): no definitions declared; transition to running.
            //   - bindings.isNotEmpty(): definitions declared; fail start when requireEngine is true,
            //     or log diagnostic if requireEngine is false.
            Optional<FlowEngine> engine = engineSupplier.tryGet();
            if (engine.isEmpty()) {
                if (!bindings.isEmpty()) {
                    if (properties.requireEngine()) {
                        throw new IllegalStateException(
                                "Exeris flow definition registrar cannot start: "
                                        + bindings.size() + " ExerisFlowDefinition bean(s) declared "
                                        + "but no kernel FlowEngine is available. Confirm the kernel "
                                        + "has a FlowProvider on the classpath and "
                                        + "exeris.runtime.auto-start is enabled. To explicitly tolerate "
                                        + "this in tests/dev, set "
                                        + "exeris.runtime.flow.require-engine=false.");
                    }
                    LOG.log(Level.WARNING,
                            "Exeris flow definition registrar starting without a kernel FlowEngine — "
                                    + "{0} ExerisFlowDefinition bean(s) will not be compiled. "
                                    + "exeris.runtime.flow.require-engine=false has been set; this is "
                                    + "intended for test/dev only.",
                            bindings.size());
                }
                running = true;
                return;
            }
            FlowExecutionPlanFactory plans = engine.get().plans();
            for (DefinitionBinding binding : bindings) {
                // Decorate the kernel builder so every step action / compensation executes
                // inside the kernel provider scope — step bodies run on flow scheduler worker
                // virtual threads that do not inherit the bootstrap ScopedValue bindings.
                FlowDefinitionBuilder builder = new ProviderScopedFlowDefinitionBuilder(
                        plans.newDefinition(binding.flowName()), providerScope);
                FlowDefinition definition = binding.definition().define(builder);
                if (definition == null) {
                    throw new IllegalStateException(
                            "ExerisFlowDefinition bean '" + binding.beanName()
                                    + "' returned null from define(...) — must return builder.build().");
                }
                if (!binding.flowName().equals(definition.name())) {
                    throw new IllegalStateException(
                            "ExerisFlowDefinition bean '" + binding.beanName()
                                    + "' declares name()='" + binding.flowName()
                                    + "' but its FlowDefinition has name='" + definition.name()
                                    + "'. The two must match — define(...) MUST use the supplied builder, "
                                    + "which carries the registrar-provided name.");
                }
                FlowExecutionPlan plan = plans.compile(definition);
                template.registerPlan(binding.flowName(), plan);
            }
            running = true;
        }
    }

    @Override
    public void stop() {
        synchronized (lifecycleLock) {
            if (!running) {
                return;
            }
            // Drop compiled plans so a re-bootstrap (rare, but supported by ExerisRuntimeLifecycle)
            // starts from an empty registry. Kernel-owned plan storage is independent and is
            // torn down by FlowEngine.close() on the kernel side.
            template.clearPlans();
            running = false;
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return PHASE;
    }

    int boundDefinitionCount() {
        return bindings.size();
    }

    private record DefinitionBinding(String beanName, String flowName, ExerisFlowDefinition definition) {
        DefinitionBinding {
            Objects.requireNonNull(beanName, "beanName");
            Objects.requireNonNull(flowName, "flowName");
            Objects.requireNonNull(definition, "definition");
        }
    }
}
