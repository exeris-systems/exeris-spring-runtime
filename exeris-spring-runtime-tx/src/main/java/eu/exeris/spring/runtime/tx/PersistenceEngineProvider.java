/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.tx;

import eu.exeris.kernel.spi.persistence.PersistenceEngine;

/**
 * Functional interface for deferred resolution of the active {@link PersistenceEngine}.
 *
 * <p>Registered as a Spring bean via a lambda that reads
 * {@link eu.exeris.kernel.spi.context.KernelProviders#PERSISTENCE_ENGINE} at call time —
 * NOT at bean construction time.
 *
 * <p>Example repository usage:
 * {@snippet lang="java" :
 * public final class OrderRepository {
 *     private final PersistenceEngineProvider engineProvider;
 *
 *     public OrderRepository(PersistenceEngineProvider engineProvider) {
 *         this.engineProvider = engineProvider;
 *     }
 *
 *     public Order findById(UUID id) {
 *         return new TransactionOrchestrator(engineProvider.get())
 *             .query(conn -> ...);
 *     }
 * }
 * }
 *
 * <p><b>Contract:</b> Callers MUST invoke {@code get()} only from kernel-owned Virtual Threads
 * (i.e., inside request handlers). Calling {@code get()} outside the kernel scope
 * will result in {@link java.util.NoSuchElementException} from the unbound ScopedValue.
 *
 * @implSpec The {@link PersistenceEngine} is a {@code ScopedValue}-bound resource available
 *     only on kernel-owned Virtual Threads. Exposing it as a raw {@code @Bean} of type
 *     {@code PersistenceEngine} would promote it to a Spring singleton scope, creating
 *     stale-state risk for concurrent requests. Instead, consumer beans constructor-inject
 *     {@code PersistenceEngineProvider} and call {@link #get()} inside their handler methods
 *     where the slot is guaranteed to be bound.
 * @since 0.1
 */
@FunctionalInterface
public interface PersistenceEngineProvider {

    /**
     * Returns the active {@link PersistenceEngine} for the current kernel VT scope.
     *
     * @return the active persistence engine; never {@code null} in kernel scope
     * @throws java.util.NoSuchElementException if called outside a kernel VT scope
     */
    PersistenceEngine get();
}
