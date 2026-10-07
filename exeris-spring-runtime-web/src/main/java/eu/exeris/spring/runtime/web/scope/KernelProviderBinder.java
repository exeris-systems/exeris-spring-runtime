/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.web.scope;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.persistence.PersistenceEngine;

/**
 * Re-propagates kernel provider {@code ScopedValue} slots onto the request handler thread when
 * the host runtime did not already bind them there. Inject one instance into the HTTP
 * dispatcher; it calls {@link #bind(Runnable)} once per request, wrapping the dispatch in a
 * {@link ScopedValue.Carrier} that binds {@link KernelProviders#PERSISTENCE_ENGINE} and/or
 * {@link KernelProviders#MEMORY_ALLOCATOR} &mdash; but only those that are currently unbound.
 *
 * @implSpec An externally supplied {@link eu.exeris.kernel.spi.http.HttpHandler} is invoked on the transport
 *     carrier thread, which does not inherit bootstrap bindings established by the kernel.
 *     The binder fills this context-propagation gap by re-binding captured provider references.
 * @implNote This is re-propagation of references the kernel created and owns &mdash; not a host-runtime claim.
 *     It uses only {@code ScopedValue} and is therefore mode-neutral.
 *     Re-binding happens strictly when a slot is <em>unbound</em>, collapsing to a zero-overhead
 *     pass-through when already bound.
 * @since 0.8
 */
@FunctionalInterface
public interface KernelProviderBinder {

    /**
     * Run {@code action} with any unbound kernel provider slots re-bound from their captured
     * references. When all relevant slots are already bound (or no captured reference is
     * available), runs {@code action} directly with no allocation.
     *
     * @param action the action to execute; must not be null
     */
    void bind(Runnable action);

    /**
     * Pass-through binder: never re-binds anything, zero allocation. The default for the
     * disabled path (no {@code ExerisRuntimeLifecycle} bean) and the test path.
     *
     * @return a no-op {@link KernelProviderBinder}
     */
    static KernelProviderBinder noop() {
        return Runnable::run;
    }

    /**
     * Capturing binder: re-binds {@link KernelProviders#PERSISTENCE_ENGINE} and
     * {@link KernelProviders#MEMORY_ALLOCATOR} from the supplied captured references, each only
     * when the corresponding slot is currently unbound and a reference is available.
     *
     * @param persistenceEngine deferred accessor to the captured kernel persistence engine
     * @param memoryAllocator   deferred accessor to the captured kernel memory allocator
     * @return a capturing {@link KernelProviderBinder}
     */
    static KernelProviderBinder capturing(Supplier<Optional<PersistenceEngine>> persistenceEngine,
                                          Supplier<Optional<MemoryAllocator>> memoryAllocator) {
        Objects.requireNonNull(persistenceEngine, "persistenceEngine");
        Objects.requireNonNull(memoryAllocator, "memoryAllocator");
        return action -> {
            Objects.requireNonNull(action, "action");
            ScopedValue.Carrier carrier = null;

            if (!KernelProviders.PERSISTENCE_ENGINE.isBound()) {
                PersistenceEngine engine = persistenceEngine.get().orElse(null);
                if (engine != null) {
                    carrier = ScopedValue.where(KernelProviders.PERSISTENCE_ENGINE, engine);
                }
            }
            if (!KernelProviders.MEMORY_ALLOCATOR.isBound()) {
                MemoryAllocator allocator = memoryAllocator.get().orElse(null);
                if (allocator != null) {
                    carrier = carrier == null
                            ? ScopedValue.where(KernelProviders.MEMORY_ALLOCATOR, allocator)
                            : carrier.where(KernelProviders.MEMORY_ALLOCATOR, allocator);
                }
            }

            if (carrier == null) {
                action.run();
            } else {
                carrier.run(action);
            }
        };
    }
}
