/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.flow;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import eu.exeris.kernel.spi.flow.FlowEngine;
import eu.exeris.kernel.spi.flow.FlowEngineStats;
import eu.exeris.kernel.spi.flow.FlowScheduler;
import eu.exeris.kernel.spi.flow.model.FlowContext;
import eu.exeris.kernel.spi.flow.model.FlowExecutionPlan;
import eu.exeris.kernel.spi.flow.model.FlowState;

/**
 * Imperative invocation surface for Spring beans that interact with the Exeris
 * {@link FlowEngine}.
 *
 * <h2>Ownership</h2>
 * <p>The template owns ZERO flow state. It holds:
 * <ul>
 *   <li>A read-mostly map of compiled {@link FlowExecutionPlan}s keyed by definition name,
 *       populated by {@link ExerisFlowDefinitionRegistrar} at lifecycle start.</li>
 *   <li>A {@link FlowEngineSupplier} resolved per call, so the underlying
 *       kernel {@code FlowEngine} is always read from the current
 *       {@code ScopedValue} scope rather than captured once at construction time.</li>
 * </ul>
 *
 * <p>Scheduling, parking, waking, and stats are all thin delegations to
 * {@link FlowScheduler} / {@link FlowEngine}. The template never replicates kernel
 * state nor caches contexts beyond returning what callers ask for.
 *
 * <h2>Engine-resolution discipline</h2>
 * <p>Every public method that needs the engine resolves it via
 * {@link FlowEngineSupplier#requireEngine()} on entry. Calling outside a kernel-bound
 * scope (or before the lifecycle has captured the engine) throws
 * {@link IllegalStateException} immediately — no silent fallback, no half-execution.
 *
 * <h2>Plan registry contract</h2>
 * <ul>
 *   <li>{@link #registerPlan(String, FlowExecutionPlan)} is intended for the registrar
 *       only. Multiple registrations under the same name throw — duplicate flow names
 *       are an application bug, not a runtime concern to silently tolerate.</li>
 *   <li>{@link #planFor(String)} fails fast if the flow is unknown.</li>
 * </ul>
 *
 * @since 0.5.0
 */
public final class ExerisFlowTemplate {

    private final FlowEngineSupplier engineSupplier;
    private final ConcurrentMap<String, FlowExecutionPlan> plans = new ConcurrentHashMap<>();

    public ExerisFlowTemplate(FlowEngineSupplier engineSupplier) {
        this.engineSupplier = Objects.requireNonNull(engineSupplier, "engineSupplier");
    }

    // =========================================================================
    // Plan registry — populated by ExerisFlowDefinitionRegistrar at boot
    // =========================================================================

    /**
     * Stores a compiled execution plan under the given definition name. Called by
     * {@link ExerisFlowDefinitionRegistrar} during {@code SmartLifecycle.start()}.
     *
     * @throws IllegalStateException if a plan with the same name was already registered
     */
    void registerPlan(String definitionName, FlowExecutionPlan plan) {
        Objects.requireNonNull(definitionName, "definitionName");
        Objects.requireNonNull(plan, "plan");
        FlowExecutionPlan existing = plans.putIfAbsent(definitionName, plan);
        if (existing != null) {
            throw new IllegalStateException(
                    "Duplicate ExerisFlowDefinition registered under name '" + definitionName
                            + "'. Each ExerisFlowDefinition.name() must be unique within the application context.");
        }
    }

    /**
     * Clears all registered plans. Called by {@link ExerisFlowDefinitionRegistrar} on
     * lifecycle stop so a subsequent re-bootstrap starts from an empty registry.
     */
    void clearPlans() {
        plans.clear();
    }

    /**
     * Returns the compiled {@link FlowExecutionPlan} for the given definition name.
     *
     * @throws IllegalArgumentException if no plan was registered under that name
     */
    public FlowExecutionPlan planFor(String definitionName) {
        Objects.requireNonNull(definitionName, "definitionName");
        FlowExecutionPlan plan = plans.get(definitionName);
        if (plan == null) {
            throw new IllegalArgumentException(
                    "No ExerisFlowDefinition registered under name '" + definitionName
                            + "'. Known flows: " + plans.keySet());
        }
        return plan;
    }

    /**
     * Returns the names of all registered flows in this template's registry. Useful for
     * actuator / diagnostic surfaces.
     */
    public Set<String> registeredFlowNames() {
        return Set.copyOf(plans.keySet());
    }

    /**
     * @return {@code true} if a flow with the given definition name is registered
     */
    public boolean hasFlow(String definitionName) {
        return definitionName != null && plans.containsKey(definitionName);
    }

    // =========================================================================
    // Context construction
    // =========================================================================

    /**
     * Builds an initial {@link FlowContext} for the named flow with a freshly minted
     * UUID instance id, {@code state = CREATED}, {@code currentStep = 0}, and timeout
     * inherited from the compiled plan.
     *
     * <p>The returned context is the seed handed to {@link #schedule(String, FlowContext)}
     * (or another scheduler operation). Kernel-side state advances internally; callers
     * MUST treat the returned record as immutable.
     *
     * @throws IllegalArgumentException if no plan is registered under {@code definitionName}
     */
    public FlowContext newContext(String definitionName) {
        FlowExecutionPlan plan = planFor(definitionName);
        UUID instance = UUID.randomUUID();
        return new BridgeFlowContext(
                instance.getMostSignificantBits(),
                instance.getLeastSignificantBits(),
                plan.definitionName(),
                0,
                FlowState.CREATED,
                plan.timeoutDurationNanos());
    }

    // =========================================================================
    // Scheduler delegation
    // =========================================================================

    /**
     * Schedules execution of a registered flow with a freshly minted context. Convenience
     * for {@code schedule(name, newContext(name))}.
     *
     * @return the seed context the engine was scheduled with — callers should retain this
     *         to subsequently {@link #wake(FlowContext)} or {@link #park(FlowContext)} the
     *         instance, since kernel-side instance lookup happens by id
     */
    public FlowContext schedule(String definitionName) {
        FlowContext ctx = newContext(definitionName);
        schedule(definitionName, ctx);
        return ctx;
    }

    /**
     * Schedules execution of a registered flow with the supplied context.
     *
     * @throws IllegalArgumentException if {@code definitionName} is unknown
     * @throws IllegalStateException    if the kernel {@link FlowEngine} is not bound
     */
    public void schedule(String definitionName, FlowContext context) {
        Objects.requireNonNull(context, "context");
        FlowExecutionPlan plan = planFor(definitionName);
        scheduler().schedule(plan, context);
    }

    /**
     * Parks an in-flight flow instance. Idempotent in the sense that the kernel rejects
     * double-park attempts internally; callers do not need to track state manually.
     */
    public void park(FlowContext context) {
        Objects.requireNonNull(context, "context");
        scheduler().park(context);
    }

    /**
     * Wakes a previously parked flow instance.
     */
    public void wake(FlowContext context) {
        Objects.requireNonNull(context, "context");
        scheduler().wake(context);
    }

    /**
     * Looks up a parked flow by its instance id (UUID split into 64-bit halves).
     *
     * @return the parked context if the kernel still holds it, or empty if it has been
     *         woken / completed / never parked
     */
    public Optional<FlowContext> lookupParked(long instanceIdMost, long instanceIdLeast) {
        return scheduler().lookupParked(instanceIdMost, instanceIdLeast);
    }

    // =========================================================================
    // Diagnostics
    // =========================================================================

    /**
     * Returns the current engine statistics snapshot. Read-through to
     * {@link FlowEngine#stats()} — fails if the engine is not bound.
     */
    public FlowEngineStats stats() {
        return engine().stats();
    }

    /**
     * Read-only view of the registered plan map for diagnostic / actuator use. Returned
     * map is a snapshot copy — mutations do not affect the template.
     */
    public Map<String, FlowExecutionPlan> registeredPlans() {
        return Map.copyOf(plans);
    }

    // =========================================================================
    // Internal
    // =========================================================================

    private FlowEngine engine() {
        return engineSupplier.requireEngine();
    }

    private FlowScheduler scheduler() {
        return engine().scheduler();
    }
}
