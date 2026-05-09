/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.flow;

import java.util.Optional;

import eu.exeris.kernel.spi.flow.FlowEngine;

/**
 * Lazy seam between the flow module and the kernel {@link FlowEngine} reference
 * captured by {@code ExerisRuntimeLifecycle} during bootstrap.
 *
 * <p>Spring beans construct at context refresh, before the lifecycle has started the
 * kernel; reading {@code KernelProviders.FLOW_ENGINE} from a Spring thread always
 * returns unbound because {@code ScopedValue} is only bound on kernel-owned virtual
 * threads. Funnelling access through this supplier defers the lookup until first use,
 * by which time the lifecycle has captured the reference, and gives the flow module
 * a single place to fail with a clear message when the kernel did not activate a
 * flow subsystem at all.
 *
 * <p>This is the same pattern as {@code EventEngineSupplier} in the events module
 * (Phase 4A) and {@code PersistenceEngineProvider} in the tx module (Phase 3A) —
 * a deferred {@code ScopedValue} accessor preserves the kernel contract that
 * engine references are read from the current scope, not captured once at bean
 * construction.
 *
 * @since 0.1.0
 */
@FunctionalInterface
public interface FlowEngineSupplier {

    /**
     * Returns the captured engine if one is available, or empty if the kernel ran
     * without a flow subsystem. Implementations must not throw.
     */
    Optional<FlowEngine> tryGet();

    /**
     * Returns the captured engine or throws if it is not available. Used on hot paths
     * that cannot proceed without the engine.
     */
    default FlowEngine requireEngine() {
        return tryGet().orElseThrow(() -> new IllegalStateException(
                "Exeris kernel FlowEngine is not available — kernel has not booted, "
                        + "or no FlowProvider was active during bootstrap. "
                        + "Set exeris.runtime.flow.enabled=true and ensure a FlowProvider is on the classpath."));
    }
}
