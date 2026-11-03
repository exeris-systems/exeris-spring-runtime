/*
 * Copyright (C) 2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.examples.orders;

import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import eu.exeris.kernel.spi.events.EventRegistry;
import eu.exeris.kernel.spi.events.EventTypeSpec;
import eu.exeris.spring.boot.autoconfigure.ExerisRuntimeLifecycle;

// tag::event-types[]
/**
 * Registers the application's event types in the kernel's event registry.
 *
 * <p>A listener or a choreography mapper subscribes by type name, and the kernel refuses a name it
 * does not hold. The registry exists only once the kernel has booted, and subscriptions are made
 * in the next lifecycle phase, so registration runs in the runtime's own phase. Injecting
 * {@link ExerisRuntimeLifecycle} makes Spring start the runtime before this bean.
 */
@Component
public class OrderEventTypes implements SmartLifecycle {

    public static final String ORDER_PLACED = "order.placed";

    private final ExerisRuntimeLifecycle runtime;
    private volatile boolean running;

    public OrderEventTypes(ExerisRuntimeLifecycle runtime) {
        this.runtime = runtime;
    }

    @Override
    public void start() {
        EventRegistry registry = runtime.getEventEngine()
                .orElseThrow(() -> new IllegalStateException("the kernel booted without an event engine"))
                .registry();
        if (!registry.isRegistered(ORDER_PLACED)) {
            registry.register(EventTypeSpec.of(ORDER_PLACED, 1024));
        }
        running = true;
    }

    @Override
    public void stop() {
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 100;
    }
}
// end::event-types[]
