/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.events;

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
import eu.exeris.spring.boot.autoconfigure.ExerisRuntimeLifecycle;
import eu.exeris.spring.boot.autoconfigure.ExerisRuntimeProperties;
import eu.exeris.spring.boot.autoconfigure.ExerisSpringConfigProvider;

/**
 * End-to-end runtime integration test for the events bridge.
 *
 * <p>Boots a real {@link ExerisRuntimeLifecycle} with {@code exeris-kernel-community} on
 * the test classpath, so the kernel bootstrap discovers the community {@code EventProvider}
 * via {@code ServiceLoader} and binds a real {@code EventEngine} into
 * {@code KernelProviders.EVENT_ENGINE}. The test verifies the load-bearing assumption of
 * the events module: that the engine reference can be captured across the
 * {@code ScopedValue} boundary and consumed by Spring beans on a different thread.
 *
 * <h2>What this proves vs the unit suite</h2>
 * <ul>
 *   <li>{@link ExerisRuntimeLifecycle#getEventEngine()} is populated after a real kernel
 *       bootstrap and cleared after shutdown.</li>
 *   <li>{@link ExerisEventListenerRegistrar} can subscribe a listener to a real
 *       {@code EventBus} and have it invoked when {@link ExerisEventPublisher} publishes
 *       through the same engine.</li>
 *   <li>The seam works without any Spring auto-configuration glue — direct construction
 *       and lifecycle calls are enough, which keeps the test independent of the Boot
 *       application context machinery.</li>
 * </ul>
 *
 * <h2>Mode</h2>
 * <p>PURE_MODE — the events bridge is mode-agnostic; this test does not exercise web mode.
 *
 * @since 0.1.0
 */
class ExerisEventBridgeRuntimeIntegrationTest {

    private static final String EVENT_TYPE_NAME = "test.payment.completed";
    private static final long AWAIT_DISPATCH_SECONDS = 5L;

    @Test
    void lifecycleCapturesEventEngineDuringBootAndClearsItOnStop() {
        ExerisRuntimeLifecycle lifecycle = newLifecycle();

        assertThat(lifecycle.getEventEngine())
                .as("Engine must not be visible before start()")
                .isEmpty();

        lifecycle.start();
        try {
            assertThat(lifecycle.getEventEngine())
                    .as("Engine must be captured once the kernel boot thread has bound EVENT_ENGINE")
                    .isPresent();
        } finally {
            lifecycle.stop();
        }

        assertThat(lifecycle.getEventEngine())
                .as("Engine reference must be cleared after shutdown so it does not outlive the kernel")
                .isEmpty();
    }

    @Test
    void publisherDispatchesIntoKernelBusAndReachesAnnotatedListener() throws InterruptedException {
        ExerisRuntimeLifecycle lifecycle = newLifecycle();
        lifecycle.start();
        try {
            EventEngine engine = lifecycle.getEventEngine().orElseThrow();
            registerEventType(engine);

            try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
                ctx.register(LatchedListenerBean.class);
                ctx.refresh();

                EventEngineSupplier supplier = lifecycle::getEventEngine;
                ExerisEventTypeRegistry typeRegistry = new ExerisEventTypeRegistry(supplier);
                ExerisEventListenerRegistrar registrar = new ExerisEventListenerRegistrar(
                        ctx, supplier, new ExerisEventProperties(true));
                registrar.afterSingletonsInstantiated();
                registrar.start();
                try {
                    assertThat(registrar.activeSubscriptionCount())
                            .as("Real kernel bus must accept the subscription")
                            .isEqualTo(1);

                    ExerisEventPublisher publisher = new ExerisEventPublisher(supplier, typeRegistry);
                    UUID streamId = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
                    publisher.publish(EVENT_TYPE_NAME, streamId, EventPayload.empty());

                    LatchedListenerBean bean = ctx.getBean(LatchedListenerBean.class);
                    assertThat(bean.latch.await(AWAIT_DISPATCH_SECONDS, TimeUnit.SECONDS))
                            .as("Listener must be invoked within %d seconds", AWAIT_DISPATCH_SECONDS)
                            .isTrue();
                    assertThat(bean.invocations.get()).isEqualTo(1);
                    EventDescriptor received = bean.lastDescriptor.get();
                    assertThat(received).isNotNull();
                    assertThat(received.streamIdHigh()).isEqualTo(streamId.getMostSignificantBits());
                    assertThat(received.streamIdLow()).isEqualTo(streamId.getLeastSignificantBits());
                } finally {
                    registrar.stop();
                }
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
        // H2 in-memory keeps the kernel persistence subsystem happy without
        // requiring a real database; events module verification does not depend
        // on persistence behaviour and we just need bootstrap to complete.
        MockEnvironment env = new MockEnvironment()
                .withProperty("exeris.runtime.persistence.jdbc-url",
                        "jdbc:h2:mem:exeris_events_runtime_it;DB_CLOSE_DELAY=-1")
                .withProperty("exeris.runtime.persistence.username", "sa")
                .withProperty("exeris.runtime.persistence.password", "")
                .withProperty("exeris.runtime.persistence.run-migrations", "false");
        return new ExerisRuntimeLifecycle(
                properties,
                new ExerisSpringConfigProvider(env),
                java.util.Optional.empty()
        );
    }

    private static void registerEventType(EventEngine engine) {
        // Register only if not already present — the community kernel may seed default
        // types in future releases, and the test must remain idempotent across reboots.
        // A high fixed ordinal (rather than registry().size()) avoids collisions if the
        // kernel ever seeds default types in parallel during boot.
        if (!engine.registry().isRegistered(EVENT_TYPE_NAME)) {
            engine.registry().register(EventTypeSpec.of(EVENT_TYPE_NAME, 1024));
        }
    }

    public static class LatchedListenerBean {
        final AtomicInteger invocations = new AtomicInteger();
        final AtomicReference<EventDescriptor> lastDescriptor = new AtomicReference<>();
        final CountDownLatch latch = new CountDownLatch(1);

        @ExerisEventListener(eventTypes = {EVENT_TYPE_NAME})
        public void onEvent(EventDescriptor descriptor, EventPayload payload) {
            lastDescriptor.set(descriptor);
            invocations.incrementAndGet();
            latch.countDown();
        }
    }
}
