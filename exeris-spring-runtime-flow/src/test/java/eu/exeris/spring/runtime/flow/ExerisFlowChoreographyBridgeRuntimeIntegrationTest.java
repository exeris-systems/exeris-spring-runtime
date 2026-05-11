/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.flow;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.mock.env.MockEnvironment;

import eu.exeris.kernel.spi.events.EventDescriptor;
import eu.exeris.kernel.spi.events.EventEngine;
import eu.exeris.kernel.spi.events.EventPayload;
import eu.exeris.kernel.spi.events.EventTypeSpec;
import eu.exeris.kernel.spi.flow.ChoreographyDecision;
import eu.exeris.spring.boot.autoconfigure.ExerisRuntimeLifecycle;
import eu.exeris.spring.boot.autoconfigure.ExerisRuntimeProperties;
import eu.exeris.spring.boot.autoconfigure.ExerisSpringConfigProvider;
import eu.exeris.spring.runtime.events.EventEngineSupplier;
import eu.exeris.spring.runtime.events.ExerisEventPublisher;
import eu.exeris.spring.runtime.events.ExerisEventTypeRegistry;

/**
 * End-to-end runtime integration test for the flow choreography bridge (Phase 4B Step 3).
 *
 * <p>Boots a real {@link ExerisRuntimeLifecycle} so the kernel community providers bind a
 * real {@code FlowEngine} and {@code EventEngine}. The test verifies the load-bearing
 * Step 3 contract: an {@link ExerisFlowChoreographyMapper} bean discovered by
 * {@link ExerisFlowChoreographyBridge} actually receives event descriptors when matching
 * events are published through the kernel {@code EventBus} via {@link ExerisEventPublisher}.
 *
 * <h2>What this proves vs the unit suite</h2>
 * <ul>
 *   <li>{@link ExerisFlowChoreographyBridge#start()} successfully calls
 *       {@code FlowEngine.registerChoreographyMapper} against a live community engine.</li>
 *   <li>The kernel routes a real event from the bus through the registered mapper —
 *       not just a Mockito-verified {@code registerChoreographyMapper(...)} call.</li>
 *   <li>The {@code FlowEngineCapabilities.choreographySupport()} gate is exercised
 *       against the actual community kernel (which sets it true).</li>
 * </ul>
 *
 * <h2>Mode</h2>
 * <p>PURE_MODE — choreography is mode-agnostic; this test does not exercise web mode.
 *
 * @since 0.5.0
 */
class ExerisFlowChoreographyBridgeRuntimeIntegrationTest {

    private static final String EVENT_TYPE_NAME = "test.choreography.user-deleted";
    private static final long AWAIT_DISPATCH_SECONDS = 5L;

    @Test
    void mapperReceivesDescriptorsViaKernelBusAfterBridgeRegistersIt() throws InterruptedException {
        ExerisRuntimeLifecycle lifecycle = newLifecycle();
        lifecycle.start();
        try {
            EventEngine eventEngine = lifecycle.getEventEngine().orElseThrow();
            registerEventType(eventEngine);

            CountDownLatch invoked = new CountDownLatch(1);
            AtomicInteger calls = new AtomicInteger();
            AtomicReference<EventDescriptor> received = new AtomicReference<>();

            ExerisFlowChoreographyMapper mapper = new ExerisFlowChoreographyMapper() {
                @Override public Set<String> eventTypeNames() { return Set.of(EVENT_TYPE_NAME); }
                @Override public ChoreographyDecision map(EventDescriptor descriptor) {
                    received.set(descriptor);
                    calls.incrementAndGet();
                    invoked.countDown();
                    // Ignore is sufficient: the test verifies the Spring → kernel registration
                    // path, not the Wake/Start cascade (those are kernel-side TCK concerns).
                    return new ChoreographyDecision.Ignore();
                }
            };

            try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
                ctx.registerBean("testMapper", ExerisFlowChoreographyMapper.class, () -> mapper);
                ctx.refresh();

                FlowEngineSupplier flowSupplier = lifecycle::getFlowEngine;
                EventEngineSupplier eventSupplier = lifecycle::getEventEngine;
                ExerisFlowProperties flowProps = new ExerisFlowProperties(true, false, true, true);

                ExerisFlowChoreographyBridge bridge = new ExerisFlowChoreographyBridge(
                        ctx, flowSupplier, eventSupplier, flowProps);
                bridge.afterSingletonsInstantiated();
                bridge.start();
                try {
                    assertThat(bridge.isRunning()).isTrue();
                    assertThat(bridge.registeredMapperCount()).isOne();

                    // Publish a real event of the registered type — the kernel bus must
                    // dispatch through the choreography subscription back into the mapper.
                    ExerisEventTypeRegistry typeRegistry = new ExerisEventTypeRegistry(eventSupplier);
                    ExerisEventPublisher publisher = new ExerisEventPublisher(eventSupplier, typeRegistry);
                    UUID streamId = UUID.fromString("11111111-2222-3333-4444-555555555555");
                    publisher.publish(EVENT_TYPE_NAME, streamId, EventPayload.empty());

                    assertThat(invoked.await(AWAIT_DISPATCH_SECONDS, TimeUnit.SECONDS))
                            .as("Choreography mapper must be invoked within %d seconds via the live kernel bus",
                                    AWAIT_DISPATCH_SECONDS)
                            .isTrue();
                    assertThat(calls.get()).isEqualTo(1);
                    EventDescriptor d = received.get();
                    assertThat(d).isNotNull();
                    assertThat(d.streamIdHigh()).isEqualTo(streamId.getMostSignificantBits());
                    assertThat(d.streamIdLow()).isEqualTo(streamId.getLeastSignificantBits());
                } finally {
                    bridge.stop();
                }
            }
        } finally {
            lifecycle.stop();
        }
    }

    @Test
    void bridgeStartIsNoOpWhenNoMapperBeansArePresent() {
        ExerisRuntimeLifecycle lifecycle = newLifecycle();
        lifecycle.start();
        try {
            try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
                ctx.refresh(); // empty context

                ExerisFlowChoreographyBridge bridge = new ExerisFlowChoreographyBridge(
                        ctx,
                        lifecycle::getFlowEngine,
                        lifecycle::getEventEngine,
                        new ExerisFlowProperties(true, false, true, true));
                bridge.afterSingletonsInstantiated();
                bridge.start();

                assertThat(bridge.isRunning()).isTrue();
                assertThat(bridge.registeredMapperCount()).isZero();
            }
        } finally {
            lifecycle.stop();
        }
    }

    private static ExerisRuntimeLifecycle newLifecycle() {
        ExerisRuntimeProperties properties = new ExerisRuntimeProperties(
                true,
                false,
                new ExerisRuntimeProperties.WebProperties(ExerisRuntimeProperties.Mode.PURE),
                new ExerisRuntimeProperties.LifecycleProperties(30),
                new ExerisRuntimeProperties.ShutdownProperties(true, 30)
        );
        MockEnvironment env = new MockEnvironment()
                .withProperty("exeris.runtime.network.port", "0")
                .withProperty("exeris.runtime.persistence.jdbc-url",
                        "jdbc:h2:mem:exeris_flow_choreo_runtime_it;DB_CLOSE_DELAY=-1")
                .withProperty("exeris.runtime.persistence.username", "sa")
                .withProperty("exeris.runtime.persistence.password", "")
                .withProperty("exeris.runtime.persistence.run-migrations", "false");
        return new ExerisRuntimeLifecycle(
                properties,
                new ExerisSpringConfigProvider(env),
                Optional.empty()
        );
    }

    private static void registerEventType(EventEngine engine) {
        if (!engine.registry().isRegistered(EVENT_TYPE_NAME)) {
            engine.registry().register(EventTypeSpec.of(EVENT_TYPE_NAME, 2048));
        }
    }
}
