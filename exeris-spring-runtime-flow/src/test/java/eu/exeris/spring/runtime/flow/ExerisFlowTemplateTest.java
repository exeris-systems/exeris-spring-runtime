/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.flow;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import org.junit.jupiter.api.Test;

import eu.exeris.kernel.spi.flow.FlowEngine;
import eu.exeris.kernel.spi.flow.FlowEngineStats;
import eu.exeris.kernel.spi.flow.FlowScheduler;
import eu.exeris.kernel.spi.flow.model.FlowContext;
import eu.exeris.kernel.spi.flow.model.FlowExecutionPlan;
import eu.exeris.kernel.spi.flow.model.FlowState;

/**
 * Unit tests for {@link ExerisFlowTemplate} — verifies plan registry semantics,
 * context construction, and scheduler delegation against a mocked {@link FlowEngine}.
 */
class ExerisFlowTemplateTest {

    private static final String FLOW_NAME = "order-fulfillment";
    private static final long PLAN_TIMEOUT_NANOS = 90_000_000_000L;

    @Test
    void registerPlanRejectsDuplicates() {
        ExerisFlowTemplate template = new ExerisFlowTemplate(missingEngineSupplier());
        FlowExecutionPlan plan = stubPlan(FLOW_NAME);

        template.registerPlan(FLOW_NAME, plan);

        assertThatThrownBy(() -> template.registerPlan(FLOW_NAME, plan))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate ExerisFlowDefinition")
                .hasMessageContaining(FLOW_NAME);
    }

    @Test
    void planForUnknownNameThrowsListingKnownFlows() {
        ExerisFlowTemplate template = new ExerisFlowTemplate(missingEngineSupplier());
        template.registerPlan(FLOW_NAME, stubPlan(FLOW_NAME));

        assertThatThrownBy(() -> template.planFor("nope"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nope")
                .hasMessageContaining(FLOW_NAME); // registered names included for diagnostics
    }

    @Test
    void registeredFlowNamesIsImmutableSnapshot() {
        ExerisFlowTemplate template = new ExerisFlowTemplate(missingEngineSupplier());
        template.registerPlan(FLOW_NAME, stubPlan(FLOW_NAME));

        var snapshot = template.registeredFlowNames();
        assertThat(snapshot).containsExactly(FLOW_NAME);
        assertThatThrownBy(() -> snapshot.add("mutable")).isInstanceOf(UnsupportedOperationException.class);
        assertThat(template.hasFlow(FLOW_NAME)).isTrue();
        assertThat(template.hasFlow("nope")).isFalse();
        assertThat(template.hasFlow(null)).isFalse();
    }

    @Test
    void newContextProducesFreshUuidStateCreatedAndPlanTimeout() {
        ExerisFlowTemplate template = new ExerisFlowTemplate(missingEngineSupplier());
        template.registerPlan(FLOW_NAME, stubPlan(FLOW_NAME));

        FlowContext c1 = template.newContext(FLOW_NAME);
        FlowContext c2 = template.newContext(FLOW_NAME);

        assertThat(c1.definitionName()).isEqualTo(FLOW_NAME);
        assertThat(c1.state()).isEqualTo(FlowState.CREATED);
        assertThat(c1.currentStep()).isZero();
        assertThat(c1.timeoutNanos()).isEqualTo(PLAN_TIMEOUT_NANOS);
        // Distinct UUIDs across calls — single template instance must not return a singleton context.
        assertThat(c1.instanceIdMost() == c2.instanceIdMost()
                && c1.instanceIdLeast() == c2.instanceIdLeast()).isFalse();
    }

    @Test
    void scheduleByNameDelegatesToSchedulerAndReturnsSeedContext() {
        FlowScheduler scheduler = mock(FlowScheduler.class);
        FlowEngine engine = engineWithScheduler(scheduler);
        ExerisFlowTemplate template = new ExerisFlowTemplate(() -> Optional.of(engine));
        template.registerPlan(FLOW_NAME, stubPlan(FLOW_NAME));

        FlowContext seed = template.schedule(FLOW_NAME);

        verify(scheduler, times(1)).schedule(any(FlowExecutionPlan.class), any(FlowContext.class));
        assertThat(seed.definitionName()).isEqualTo(FLOW_NAME);
        assertThat(seed.state()).isEqualTo(FlowState.CREATED);
    }

    @Test
    void scheduleWithExplicitContextDelegatesWithoutMutation() {
        FlowScheduler scheduler = mock(FlowScheduler.class);
        FlowEngine engine = engineWithScheduler(scheduler);
        ExerisFlowTemplate template = new ExerisFlowTemplate(() -> Optional.of(engine));
        template.registerPlan(FLOW_NAME, stubPlan(FLOW_NAME));

        FlowContext ctx = template.newContext(FLOW_NAME);
        template.schedule(FLOW_NAME, ctx);

        verify(scheduler).schedule(any(FlowExecutionPlan.class), any(FlowContext.class));
    }

    @Test
    void parkWakeLookupAllDelegateToScheduler() {
        FlowScheduler scheduler = mock(FlowScheduler.class);
        FlowEngine engine = engineWithScheduler(scheduler);
        ExerisFlowTemplate template = new ExerisFlowTemplate(() -> Optional.of(engine));
        template.registerPlan(FLOW_NAME, stubPlan(FLOW_NAME));
        FlowContext ctx = template.newContext(FLOW_NAME);

        template.park(ctx);
        template.wake(ctx);
        when(scheduler.lookupParked(ctx.instanceIdMost(), ctx.instanceIdLeast())).thenReturn(Optional.of(ctx));
        Optional<FlowContext> looked = template.lookupParked(ctx.instanceIdMost(), ctx.instanceIdLeast());

        verify(scheduler).park(ctx);
        verify(scheduler).wake(ctx);
        verify(scheduler).lookupParked(ctx.instanceIdMost(), ctx.instanceIdLeast());
        assertThat(looked).contains(ctx);
    }

    @Test
    void statsReadsThroughToEngine() {
        FlowEngine engine = mock(FlowEngine.class);
        FlowEngineStats stats = FlowEngineStats.empty();
        when(engine.stats()).thenReturn(stats);
        ExerisFlowTemplate template = new ExerisFlowTemplate(() -> Optional.of(engine));

        assertThat(template.stats()).isSameAs(stats);
        verify(engine).stats();
    }

    @Test
    void scheduleFailsLoudWhenEngineUnbound() {
        // First-call-site enforcement: requireEngine() throws if the kernel never
        // bound a FlowEngine. The template MUST NOT silently no-op or retry.
        ExerisFlowTemplate template = new ExerisFlowTemplate(missingEngineSupplier());
        template.registerPlan(FLOW_NAME, stubPlan(FLOW_NAME));
        FlowContext ctx = template.newContext(FLOW_NAME);

        assertThatThrownBy(() -> template.schedule(FLOW_NAME, ctx))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FlowEngine is not available");
    }

    @Test
    void planRegistryQueriesDoNotTouchEngine() {
        // Plan registry is owned by the template — querying it must not require the
        // engine to be bound (operators inspect registered flow names at boot before
        // the kernel has finished initialising).
        FlowEngine engine = mock(FlowEngine.class);
        ExerisFlowTemplate template = new ExerisFlowTemplate(() -> Optional.of(engine));
        template.registerPlan(FLOW_NAME, stubPlan(FLOW_NAME));

        template.registeredFlowNames();
        template.registeredPlans();
        template.hasFlow(FLOW_NAME);
        template.planFor(FLOW_NAME);
        template.newContext(FLOW_NAME);

        verifyNoInteractions(engine);
    }

    @Test
    void clearPlansEmptiesRegistry() {
        ExerisFlowTemplate template = new ExerisFlowTemplate(missingEngineSupplier());
        template.registerPlan(FLOW_NAME, stubPlan(FLOW_NAME));
        template.clearPlans();

        assertThat(template.registeredFlowNames()).isEmpty();
        assertThat(template.hasFlow(FLOW_NAME)).isFalse();
    }

    // =========================================================================
    // helpers
    // =========================================================================

    private static FlowEngineSupplier missingEngineSupplier() {
        return Optional::empty;
    }

    private static FlowEngine engineWithScheduler(FlowScheduler scheduler) {
        FlowEngine engine = mock(FlowEngine.class);
        when(engine.scheduler()).thenReturn(scheduler);
        return engine;
    }

    private static FlowExecutionPlan stubPlan(String name) {
        FlowExecutionPlan plan = mock(FlowExecutionPlan.class);
        when(plan.definitionName()).thenReturn(name);
        when(plan.timeoutDurationNanos()).thenReturn(PLAN_TIMEOUT_NANOS);
        when(plan.stepCount()).thenReturn(1);
        return plan;
    }
}
