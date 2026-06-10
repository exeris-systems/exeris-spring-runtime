/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.flow;

import java.util.Objects;

import eu.exeris.kernel.spi.flow.FlowDefinitionBuilder;
import eu.exeris.kernel.spi.flow.model.FlowDefinition;
import eu.exeris.kernel.spi.flow.model.FlowStepAction;
import eu.exeris.spring.boot.autoconfigure.KernelProviderScope;

/**
 * Decorating {@link FlowDefinitionBuilder} that wraps every registered {@link FlowStepAction}
 * (forward action and compensation alike) in a {@link KernelProviderScope}, so the step body
 * executes with the kernel provider {@code ScopedValue} slots re-bound from the references
 * captured at bootstrap.
 *
 * <h2>Why this exists</h2>
 * <p>Step actions execute on kernel flow scheduler worker virtual threads, which do not inherit
 * the bootstrap {@code ScopedValue} scope. The flow <em>bridge</em> classes avoid the gap by
 * reading captured engine references through {@code FlowEngineSupplier} — but the gap re-opens
 * inside <em>application</em> step bodies: per the {@code FlowStepAction} contract, cross-cutting
 * providers are obtained via {@code KernelProviders} scoped slots, and the compat persistence
 * path ({@code ExerisDataSource} backing a Spring repository called from a saga step) reads
 * {@code KernelProviders.PERSISTENCE_ENGINE} directly. Without re-binding, every step that
 * touches the compat {@code DataSource} fails with "PersistenceEngine is not bound in the
 * current scope" and the saga compensates straight from step 0. This mirrors the request-path
 * fix in {@code eu.exeris.spring.runtime.web.scope.KernelProviderBinder} (PR #48).
 *
 * <h2>Cost</h2>
 * <p>One capturing lambda per step <em>execution</em> (the {@code Supplier} handed to the scope
 * closes over the {@code FlowContext}), plus the carrier allocation only when a slot is actually
 * unbound. Flow step execution is not the HTTP hot path; the cost is negligible next to the work
 * a step performs, and the scope collapses to a direct call when the kernel already propagates
 * its scope to worker threads.
 *
 * <p>Mode-neutral: re-binding only fills unbound slots and uses only {@code ScopedValue} — no
 * {@code ThreadLocal}, no behavioural change for pure-mode step bodies that never read the slots.
 *
 * @since 0.5.0
 */
final class ProviderScopedFlowDefinitionBuilder implements FlowDefinitionBuilder {

    private final FlowDefinitionBuilder delegate;
    private final KernelProviderScope providerScope;

    ProviderScopedFlowDefinitionBuilder(FlowDefinitionBuilder delegate, KernelProviderScope providerScope) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.providerScope = Objects.requireNonNull(providerScope, "providerScope");
    }

    @Override
    public FlowDefinitionBuilder step(String name, FlowStepAction action, FlowStepAction compensation) {
        delegate.step(name, wrap(action), wrap(compensation));
        return this;
    }

    @Override
    public FlowDefinitionBuilder transition(int fromStep, int toStep) {
        delegate.transition(fromStep, toStep);
        return this;
    }

    @Override
    public FlowDefinitionBuilder transition(int fromStep, int toStep, String conditionTag) {
        delegate.transition(fromStep, toStep, conditionTag);
        return this;
    }

    @Override
    public FlowDefinitionBuilder timeoutDuration(long durationNanos) {
        delegate.timeoutDuration(durationNanos);
        return this;
    }

    @Override
    public FlowDefinitionBuilder maxRetries(int maxRetries) {
        delegate.maxRetries(maxRetries);
        return this;
    }

    @Override
    public FlowDefinition build() {
        return delegate.build();
    }

    /**
     * Null-tolerant so a legal {@code null} compensation passes through unchanged and a
     * {@code null} forward action still surfaces the delegate's own validation error.
     */
    private FlowStepAction wrap(FlowStepAction action) {
        if (action == null) {
            return null;
        }
        return context -> providerScope.call(() -> action.execute(context));
    }
}
