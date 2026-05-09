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
 * at the bridge layer; choreography wiring will couple them in a separate Step 3 bridge).
 *
 * <h2>Posture: fail loud when half-configured</h2>
 * <p>If the application has declared {@code ExerisFlowDefinition} beans but the kernel
 * did not bind a {@code FlowEngine} at bootstrap (no {@code FlowProvider} on the
 * classpath, kernel flow subsystem disabled, etc.), the registrar fails the lifecycle
 * start when {@code exeris.runtime.flow.require-engine=true} (the default). Operators
 * see the misconfiguration immediately rather than discovering it through silent
 * scheduling failures later.
 *
 * <p>Test harnesses that intentionally skip kernel bootstrap (e.g. autoconfig context
 * tests with {@code exeris.runtime.auto-start=false}) opt out by setting
 * {@code exeris.runtime.flow.require-engine=false}.
 *
 * @since 0.5.0
 */
public final class ExerisFlowDefinitionRegistrar implements SmartInitializingSingleton, SmartLifecycle {

    private static final int PHASE = Integer.MAX_VALUE - 99;
    private static final Logger LOG = System.getLogger(ExerisFlowDefinitionRegistrar.class.getName());

    private final ApplicationContext applicationContext;
    private final FlowEngineSupplier engineSupplier;
    private final ExerisFlowTemplate template;
    private final ExerisFlowProperties properties;
    private final Object lifecycleLock = new Object();

    private final List<DefinitionBinding> bindings = new ArrayList<>();
    private volatile boolean running = false;

    public ExerisFlowDefinitionRegistrar(ApplicationContext applicationContext,
                                          FlowEngineSupplier engineSupplier,
                                          ExerisFlowTemplate template,
                                          ExerisFlowProperties properties) {
        this.applicationContext = Objects.requireNonNull(applicationContext, "applicationContext");
        this.engineSupplier = Objects.requireNonNull(engineSupplier, "engineSupplier");
        this.template = Objects.requireNonNull(template, "template");
        this.properties = Objects.requireNonNull(properties, "properties");
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
            // Two failure modes when the kernel did not bind a FlowEngine:
            //   - bindings.isEmpty(): no ExerisFlowDefinition beans declared, so the
            //     missing engine is irrelevant for THIS bean's responsibility. Transition
            //     to running and let the template fail loud at first use if the
            //     application actually calls schedule()/wake() etc.
            //   - bindings.isNotEmpty(): definitions were declared but cannot be compiled.
            //     If exeris.runtime.flow.require-engine=true (default) this is a real
            //     misconfiguration and we fail loud at lifecycle start; if explicitly
            //     opted out (test harness with auto-start=false, dev fallback) we log
            //     a diagnostic and let the bean transition to running so shutdown
            //     ordering stays consistent.
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
                FlowDefinitionBuilder builder = plans.newDefinition(binding.flowName());
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
