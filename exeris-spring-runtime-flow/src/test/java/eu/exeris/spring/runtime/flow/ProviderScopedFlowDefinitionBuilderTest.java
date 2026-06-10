/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.flow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.flow.FlowDefinitionBuilder;
import eu.exeris.kernel.spi.flow.model.FlowContext;
import eu.exeris.kernel.spi.flow.model.FlowDefinition;
import eu.exeris.kernel.spi.flow.model.FlowOutcome;
import eu.exeris.kernel.spi.flow.model.FlowStepAction;
import eu.exeris.kernel.spi.persistence.PersistenceEngine;
import eu.exeris.spring.boot.autoconfigure.KernelProviderScope;

/**
 * Unit tests for {@link ProviderScopedFlowDefinitionBuilder} — the decorator that wraps every
 * registered {@link FlowStepAction} (action and compensation) in a {@link KernelProviderScope},
 * so step bodies executing on kernel flow scheduler worker virtual threads observe the kernel
 * provider {@code ScopedValue} slots re-bound from the bootstrap captures.
 */
class ProviderScopedFlowDefinitionBuilderTest {

    @Test
    void wrapsStepActionAndCompensation_executionSeesPersistenceEngineBound() {
        PersistenceEngine engine = mock(PersistenceEngine.class);
        KernelProviderScope scope =
                KernelProviderScope.capturing(() -> Optional.of(engine), Optional::empty);
        FlowDefinitionBuilder delegate = mock(FlowDefinitionBuilder.class);
        ProviderScopedFlowDefinitionBuilder builder =
                new ProviderScopedFlowDefinitionBuilder(delegate, scope);

        AtomicReference<PersistenceEngine> seenInAction = new AtomicReference<>();
        AtomicReference<PersistenceEngine> seenInCompensation = new AtomicReference<>();
        FlowStepAction action = ctx -> {
            seenInAction.set(KernelProviders.PERSISTENCE_ENGINE.get());
            return FlowOutcome.COMPLETE;
        };
        FlowStepAction compensation = ctx -> {
            seenInCompensation.set(KernelProviders.PERSISTENCE_ENGINE.get());
            return FlowOutcome.COMPLETE;
        };

        builder.step("charge", action, compensation);

        ArgumentCaptor<FlowStepAction> actionCaptor = ArgumentCaptor.forClass(FlowStepAction.class);
        ArgumentCaptor<FlowStepAction> compensationCaptor = ArgumentCaptor.forClass(FlowStepAction.class);
        verify(delegate).step(eq("charge"), actionCaptor.capture(), compensationCaptor.capture());
        assertThat(actionCaptor.getValue()).isNotSameAs(action);
        assertThat(compensationCaptor.getValue()).isNotSameAs(compensation);

        // Simulate the kernel scheduler invoking the registered actions on a thread with no
        // bootstrap bindings (this test thread): the wrap must re-bind the slot around the body.
        FlowContext context = mock(FlowContext.class);
        assertThat(actionCaptor.getValue().execute(context)).isEqualTo(FlowOutcome.COMPLETE);
        assertThat(compensationCaptor.getValue().execute(context)).isEqualTo(FlowOutcome.COMPLETE);
        assertThat(seenInAction.get()).isSameAs(engine);
        assertThat(seenInCompensation.get()).isSameAs(engine);
        // The re-bind is scoped to the step body only.
        assertThat(KernelProviders.PERSISTENCE_ENGINE.isBound()).isFalse();
    }

    @Test
    void nullCompensationPassesThroughUnwrapped() {
        FlowDefinitionBuilder delegate = mock(FlowDefinitionBuilder.class);
        ProviderScopedFlowDefinitionBuilder builder =
                new ProviderScopedFlowDefinitionBuilder(delegate, KernelProviderScope.noop());

        builder.step("dispatch", ctx -> FlowOutcome.COMPLETE, null);

        ArgumentCaptor<FlowStepAction> compensationCaptor = ArgumentCaptor.forClass(FlowStepAction.class);
        verify(delegate).step(eq("dispatch"), ArgumentCaptor.forClass(FlowStepAction.class).capture(),
                compensationCaptor.capture());
        assertThat(compensationCaptor.getValue()).isNull();
    }

    @Test
    void wrappedActionPropagatesOutcomeAndExceptions() {
        FlowDefinitionBuilder delegate = mock(FlowDefinitionBuilder.class);
        ProviderScopedFlowDefinitionBuilder builder =
                new ProviderScopedFlowDefinitionBuilder(delegate, KernelProviderScope.noop());

        builder.step("park", ctx -> FlowOutcome.PARK, ctx -> {
            throw new IllegalStateException("compensation failed");
        });

        ArgumentCaptor<FlowStepAction> actionCaptor = ArgumentCaptor.forClass(FlowStepAction.class);
        ArgumentCaptor<FlowStepAction> compensationCaptor = ArgumentCaptor.forClass(FlowStepAction.class);
        verify(delegate).step(eq("park"), actionCaptor.capture(), compensationCaptor.capture());

        FlowContext context = mock(FlowContext.class);
        assertThat(actionCaptor.getValue().execute(context)).isEqualTo(FlowOutcome.PARK);
        assertThatThrownBy(() -> compensationCaptor.getValue().execute(context))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("compensation failed");
    }

    @Test
    void chainingMethodsReturnTheDecorator_andDelegateAllConfiguration() {
        FlowDefinitionBuilder delegate = mock(FlowDefinitionBuilder.class);
        FlowDefinition built = mock(FlowDefinition.class);
        when(delegate.build()).thenReturn(built);
        ProviderScopedFlowDefinitionBuilder builder =
                new ProviderScopedFlowDefinitionBuilder(delegate, KernelProviderScope.noop());

        // Returning `this` (the decorator) instead of the delegate is load-bearing: a fluent
        // chain like b.step(...).step(...) must keep wrapping later steps too.
        assertThat(builder.step("s", ctx -> FlowOutcome.COMPLETE, null)).isSameAs(builder);
        assertThat(builder.transition(0, 1)).isSameAs(builder);
        assertThat(builder.transition(0, 1, "retry")).isSameAs(builder);
        assertThat(builder.timeoutDuration(1_000L)).isSameAs(builder);
        assertThat(builder.maxRetries(3)).isSameAs(builder);
        assertThat(builder.build()).isSameAs(built);

        verify(delegate).transition(0, 1);
        verify(delegate).transition(0, 1, "retry");
        verify(delegate).timeoutDuration(1_000L);
        verify(delegate).maxRetries(3);
        verify(delegate).build();
    }
}
