/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.events;

import java.util.Optional;

import eu.exeris.kernel.spi.events.EventEngine;

/**
 * Lazy seam between the events module and the kernel {@link EventEngine} reference
 * captured by {@code ExerisRuntimeLifecycle} during bootstrap.
 *
 * <p>Spring beans construct at context refresh, before the lifecycle has started the
 * kernel; reading {@code KernelProviders.EVENT_ENGINE} from a Spring thread always
 * returns unbound because {@code ScopedValue} is only bound on kernel-owned virtual
 * threads. Funnelling access through this supplier defers the lookup until first use,
 * by which time the lifecycle has captured the reference, and gives the events module
 * a single place to fail with a clear message when the kernel did not activate an
 * event subsystem at all.
 *
 * @since 0.1.0
 */
@FunctionalInterface
public interface EventEngineSupplier {

    /**
     * Returns the captured engine if one is available, or empty if the kernel ran
     * without an event subsystem. Implementations must not throw.
     */
    Optional<EventEngine> tryGet();

    /**
     * Returns the captured engine or throws if it is not available. Used on hot paths
     * that cannot proceed without the engine.
     */
    default EventEngine requireEngine() {
        return tryGet().orElseThrow(() -> new IllegalStateException(
                "Exeris kernel EventEngine is not available — kernel has not booted, "
                        + "or no EventProvider was active during bootstrap. "
                        + "Set exeris.runtime.events.enabled=true and ensure an EventProvider is on the classpath."));
    }
}
