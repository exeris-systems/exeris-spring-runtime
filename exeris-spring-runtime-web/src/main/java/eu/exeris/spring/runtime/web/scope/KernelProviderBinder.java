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
 * {@link KernelProviders#MEMORY_ALLOCATOR} — but only those that are currently unbound.
 *
 * <h2>Why this exists</h2>
 * <p>The kernel binds its provider slots once in the bootstrap {@code ScopedValue} scope. Its
 * own generated handlers receive those providers through the kernel construction seam or run on
 * per-request dispatch virtual threads that inherit the scope. An <em>externally supplied</em>
 * {@link eu.exeris.kernel.spi.http.HttpHandler} — this runtime's dispatcher, bound via
 * {@code HttpKernelProviders.HTTP_SERVER_HANDLER} — is invoked on the transport carrier thread,
 * which carries no bootstrap bindings. The event/flow/graph bridges already work around this by
 * reading captured engine references instead of the {@code ScopedValue}; the compat persistence
 * path ({@code ExerisDataSource}, {@code PersistenceEngineProvider}) and the response codec
 * ({@code ExerisServerResponse} reading {@code MEMORY_ALLOCATOR}) read the slot directly, so they
 * need the slot re-bound here. The captured references come from
 * {@code ExerisRuntimeLifecycle.getPersistenceEngine()} / {@code getMemoryAllocator()}.
 *
 * <h2>Ownership</h2>
 * <p>This is re-propagation of references the kernel created and owns — not a host-runtime claim.
 * Exeris remains the runtime owner; the binder fills a context-propagation gap on the externally
 * supplied handler thread. It uses only {@code ScopedValue} (no {@code ThreadLocal}) and is
 * therefore mode-neutral: it is shared by the Pure Mode and Compatibility Mode dispatchers.
 * Re-binding happens strictly when a slot is <em>unbound</em>, so when the kernel does propagate
 * the scope this collapses to a zero-overhead pass-through and never overrides a carrier-affine
 * binding established by the kernel.
 *
 * @since 0.8.1
 */
@FunctionalInterface
public interface KernelProviderBinder {

    /**
     * Run {@code action} with any unbound kernel provider slots re-bound from their captured
     * references. When all relevant slots are already bound (or no captured reference is
     * available), runs {@code action} directly with no allocation.
     */
    void bind(Runnable action);

    /**
     * Pass-through binder: never re-binds anything, zero allocation. The default for the
     * disabled path (no {@code ExerisRuntimeLifecycle} bean) and the test path.
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
