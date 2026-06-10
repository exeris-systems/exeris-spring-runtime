/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.boot.autoconfigure;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.persistence.PersistenceEngine;

/**
 * Re-propagates kernel provider {@code ScopedValue} slots around a value-returning action that
 * executes on a thread the kernel bootstrap scope does not reach. Calls re-bind
 * {@link KernelProviders#PERSISTENCE_ENGINE} and/or {@link KernelProviders#MEMORY_ALLOCATOR}
 * from references captured by {@link ExerisRuntimeLifecycle} at bootstrap — but only the slots
 * that are currently unbound.
 *
 * <h2>Why this exists</h2>
 * <p>The kernel binds its provider slots once in the bootstrap {@code ScopedValue} scope.
 * Application code handed to the kernel as a callback — a {@code FlowStepAction} executing on a
 * flow scheduler worker virtual thread, for example — runs outside that scope, so slot-reading
 * consumers (the compat {@code ExerisDataSource} / {@code PersistenceEngineProvider} persistence
 * path in particular) fail with "PersistenceEngine is not bound in the current scope". The web
 * module solves the same gap on the request path with
 * {@code eu.exeris.spring.runtime.web.scope.KernelProviderBinder}; this is its value-returning
 * sibling for non-web bridge modules, colocated with {@link ExerisRuntimeLifecycle} because that
 * is where the captured references live and because consumer modules (e.g. flow) deliberately ban
 * direct {@code eu.exeris.kernel.spi.persistence..} imports in their boundary guards.
 *
 * <h2>Ownership</h2>
 * <p>This is re-propagation of references the kernel created and owns — not a host-runtime
 * claim. Exeris remains the runtime owner; the scope fills a context-propagation gap on threads
 * the bootstrap bindings do not reach. It uses only {@code ScopedValue} (no {@code ThreadLocal})
 * and is mode-neutral: re-binding happens strictly when a slot is <em>unbound</em>, so when the
 * kernel does propagate its scope this collapses to a pass-through and never overrides a
 * carrier-affine binding established by the kernel.
 *
 * @since 0.5.0
 */
public interface KernelProviderScope {

    /**
     * Runs {@code action} with any unbound kernel provider slots re-bound from their captured
     * references and returns its result. When all relevant slots are already bound (or no
     * captured reference is available), invokes {@code action} directly.
     */
    <T> T call(Supplier<T> action);

    /**
     * Pass-through scope: never re-binds anything. The default for the disabled path (no
     * {@link ExerisRuntimeLifecycle} bean) and the test path.
     */
    static KernelProviderScope noop() {
        return new KernelProviderScope() {
            @Override
            public <T> T call(Supplier<T> action) {
                Objects.requireNonNull(action, "action");
                return action.get();
            }
        };
    }

    /**
     * Capturing scope: re-binds {@link KernelProviders#PERSISTENCE_ENGINE} and
     * {@link KernelProviders#MEMORY_ALLOCATOR} from the supplied captured references, each only
     * when the corresponding slot is currently unbound and a reference is available.
     *
     * @param persistenceEngine deferred accessor to the captured kernel persistence engine
     * @param memoryAllocator   deferred accessor to the captured kernel memory allocator
     */
    static KernelProviderScope capturing(Supplier<Optional<PersistenceEngine>> persistenceEngine,
                                         Supplier<Optional<MemoryAllocator>> memoryAllocator) {
        Objects.requireNonNull(persistenceEngine, "persistenceEngine");
        Objects.requireNonNull(memoryAllocator, "memoryAllocator");
        return new KernelProviderScope() {
            @Override
            public <T> T call(Supplier<T> action) {
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
                    return action.get();
                }
                ScopedValue.CallableOp<T, RuntimeException> op = action::get;
                return carrier.call(op);
            }
        };
    }

    /**
     * Capturing scope wired to the lifecycle's captured engine references. The accessors are
     * deferred — they read the captured references at call time, so a scope built during bean
     * wiring (before {@link ExerisRuntimeLifecycle#start()} has populated the captures) becomes
     * effective as soon as the kernel has booted.
     */
    static KernelProviderScope fromLifecycle(ExerisRuntimeLifecycle lifecycle) {
        Objects.requireNonNull(lifecycle, "lifecycle");
        return capturing(lifecycle::getPersistenceEngine, lifecycle::getMemoryAllocator);
    }
}
