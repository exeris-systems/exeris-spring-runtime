/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.flow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;

import eu.exeris.kernel.spi.events.EventBus;
import eu.exeris.kernel.spi.events.EventDescriptor;
import eu.exeris.kernel.spi.events.EventEngine;
import eu.exeris.kernel.spi.flow.ChoreographyDecision;
import eu.exeris.kernel.spi.flow.FlowEngine;
import eu.exeris.kernel.spi.flow.FlowEngineCapabilities;
import eu.exeris.spring.runtime.events.EventEngineSupplier;

/**
 * Unit tests for {@link ExerisFlowChoreographyBridge} — drives every branch of the
 * tolerant/strict posture and the capability gate without spinning up the kernel.
 */
class ExerisFlowChoreographyBridgeTest {

    private static FlowEngineCapabilities capabilitiesWithoutChoreography() {
        return new FlowEngineCapabilities(
                false, false, false, false, true, true, /* choreographySupport */ false, "test-no-choreo");
    }

    @Test
    void noMapperBeansIsNoOpEvenWithoutEngine() {
        ApplicationContext ctx = mock(ApplicationContext.class);
        when(ctx.getBeansOfType(ExerisFlowChoreographyMapper.class)).thenReturn(Map.of());

        ExerisFlowChoreographyBridge bridge = bridge(ctx, Optional.empty(), Optional.empty(),
                strict());

        bridge.afterSingletonsInstantiated();
        bridge.start();

        assertThat(bridge.isRunning()).isTrue();
        assertThat(bridge.registeredMapperCount()).isZero();
    }

    @Test
    void mapperWithEmptyEventTypeNamesIsRejectedAtCollectionTime() {
        ApplicationContext ctx = ctxWithMappers(Map.of("empty", mapper(Set.of())));

        ExerisFlowChoreographyBridge bridge = bridge(ctx, Optional.empty(), Optional.empty(),
                strict());

        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(bridge::afterSingletonsInstantiated)
                .withMessageContaining("declares no event type names");
    }

    @Test
    void strictPostureFailsLoudWhenFlowEngineMissing() {
        ApplicationContext ctx = ctxWithMappers(Map.of("alpha", mapper(Set.of("UserCreated"))));

        ExerisFlowChoreographyBridge bridge = bridge(ctx, Optional.empty(), Optional.of(mock(EventEngine.class)),
                strict());

        bridge.afterSingletonsInstantiated();

        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(bridge::start)
                .withMessageContaining("FlowEngine is not bound")
                .withMessageContaining("require-engine=false");
        assertThat(bridge.isRunning()).isFalse();
    }

    @Test
    void strictPostureFailsLoudWhenEventEngineMissing() {
        ApplicationContext ctx = ctxWithMappers(Map.of("alpha", mapper(Set.of("UserCreated"))));
        FlowEngine flow = mockFlow(/* choreography */ true);

        ExerisFlowChoreographyBridge bridge = bridge(ctx, Optional.of(flow), Optional.empty(),
                strict());

        bridge.afterSingletonsInstantiated();

        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(bridge::start)
                .withMessageContaining("EventEngine is not bound");
        assertThat(bridge.isRunning()).isFalse();
        verify(flow, never()).registerChoreographyMapper(any(), any(), any());
    }

    @Test
    void tolerantPostureLogsAndContinuesWhenEngineMissing() {
        ApplicationContext ctx = ctxWithMappers(Map.of("alpha", mapper(Set.of("UserCreated"))));

        ExerisFlowChoreographyBridge bridge = bridge(ctx, Optional.empty(), Optional.empty(),
                tolerant());

        bridge.afterSingletonsInstantiated();
        bridge.start();

        assertThat(bridge.isRunning()).isTrue();
        assertThat(bridge.registeredMapperCount()).isOne();
    }

    @Test
    void capabilityMissingAlwaysFailsLoudEvenWhenTolerant() {
        ApplicationContext ctx = ctxWithMappers(Map.of("alpha", mapper(Set.of("UserCreated"))));
        FlowEngine flow = mockFlow(/* choreography */ false);
        EventEngine events = mock(EventEngine.class);

        ExerisFlowChoreographyBridge bridge = bridge(ctx, Optional.of(flow), Optional.of(events),
                tolerant() /* even when require-engine=false */);

        bridge.afterSingletonsInstantiated();

        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(bridge::start)
                .withMessageContaining("choreographySupport=false")
                .withMessageContaining("test-no-choreo");
        verify(flow, never()).registerChoreographyMapper(any(), any(), any());
    }

    @Test
    void registersEachMapperWithItsDeclaredEventTypeNames() {
        ExerisFlowChoreographyMapper alpha = mapper(Set.of("UserCreated", "UserDeleted"));
        ExerisFlowChoreographyMapper beta = mapper(Set.of("OrderShipped"));
        ApplicationContext ctx = ctxWithMappers(Map.of("alpha", alpha, "beta", beta));

        FlowEngine flow = mockFlow(/* choreography */ true);
        EventEngine events = mock(EventEngine.class);
        EventBus bus = mock(EventBus.class);
        when(events.bus()).thenReturn(bus);

        ExerisFlowChoreographyBridge bridge = bridge(ctx, Optional.of(flow), Optional.of(events),
                strict());

        bridge.afterSingletonsInstantiated();
        bridge.start();

        assertThat(bridge.isRunning()).isTrue();
        assertThat(bridge.registeredMapperCount()).isEqualTo(2);
        verify(flow).registerChoreographyMapper(eq(alpha), eq(Set.of("UserCreated", "UserDeleted")), eq(bus));
        verify(flow).registerChoreographyMapper(eq(beta), eq(Set.of("OrderShipped")), eq(bus));
        verify(flow, times(2)).registerChoreographyMapper(any(), any(), any());
    }

    @Test
    void startIsIdempotent() {
        ExerisFlowChoreographyMapper alpha = mapper(Set.of("UserCreated"));
        ApplicationContext ctx = ctxWithMappers(Map.of("alpha", alpha));
        FlowEngine flow = mockFlow(/* choreography */ true);
        EventEngine events = mock(EventEngine.class);
        when(events.bus()).thenReturn(mock(EventBus.class));

        ExerisFlowChoreographyBridge bridge = bridge(ctx, Optional.of(flow), Optional.of(events),
                strict());

        bridge.afterSingletonsInstantiated();
        bridge.start();
        bridge.start(); // second call is a no-op

        verify(flow, times(1)).registerChoreographyMapper(any(), any(), any());
    }

    @Test
    void stopClearsRunningFlagWithoutTouchingEngine() {
        ApplicationContext ctx = ctxWithMappers(Map.of("alpha", mapper(Set.of("UserCreated"))));
        FlowEngine flow = mockFlow(/* choreography */ true);
        EventEngine events = mock(EventEngine.class);
        when(events.bus()).thenReturn(mock(EventBus.class));

        ExerisFlowChoreographyBridge bridge = bridge(ctx, Optional.of(flow), Optional.of(events),
                strict());

        bridge.afterSingletonsInstantiated();
        bridge.start();
        assertThat(bridge.isRunning()).isTrue();

        bridge.stop();
        assertThat(bridge.isRunning()).isFalse();
        // No further interactions with the engine — the kernel owns subscription teardown
        // through engine.close() during ExerisRuntimeLifecycle.stop().
    }

    @Test
    void phaseConstantSitsOneSlotAfterRegistrar() {
        assertThat(ExerisFlowChoreographyBridge.PHASE).isEqualTo(Integer.MAX_VALUE - 98);
        // Sanity: registrar phase is MAX_VALUE - 99, so bridge runs after.
        assertThat(ExerisFlowChoreographyBridge.PHASE)
                .isGreaterThan(Integer.MAX_VALUE - 99);
    }

    // ---- helpers ----

    private static ExerisFlowChoreographyBridge bridge(ApplicationContext ctx,
                                                        Optional<FlowEngine> flow,
                                                        Optional<EventEngine> events,
                                                        ExerisFlowProperties props) {
        return new ExerisFlowChoreographyBridge(ctx, () -> flow, () -> events, props);
    }

    private static ApplicationContext ctxWithMappers(Map<String, ExerisFlowChoreographyMapper> beans) {
        ApplicationContext ctx = mock(ApplicationContext.class);
        when(ctx.getBeansOfType(ExerisFlowChoreographyMapper.class)).thenReturn(new HashMap<>(beans));
        return ctx;
    }

    private static ExerisFlowChoreographyMapper mapper(Set<String> types) {
        return new ExerisFlowChoreographyMapper() {
            @Override
            public Set<String> eventTypeNames() {
                return types;
            }

            @Override
            public ChoreographyDecision map(EventDescriptor descriptor) {
                return new ChoreographyDecision.Ignore();
            }
        };
    }

    private static FlowEngine mockFlow(boolean choreographySupport) {
        FlowEngine flow = mock(FlowEngine.class);
        FlowEngineCapabilities caps = choreographySupport
                ? FlowEngineCapabilities.COMMUNITY.withProvider("test-choreo")
                : capabilitiesWithoutChoreography();
        when(flow.capabilities()).thenReturn(caps);
        return flow;
    }

    private static ExerisFlowProperties strict() {
        return new ExerisFlowProperties(true, false, true, /* requireEngine */ true);
    }

    private static ExerisFlowProperties tolerant() {
        return new ExerisFlowProperties(true, false, true, /* requireEngine */ false);
    }
}
