/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.tx;

import eu.exeris.kernel.spi.persistence.PersistenceConnection;
import org.springframework.transaction.support.TransactionSynchronization;

import java.util.Objects;

/**
 * Deterministic close sentinel for Exeris {@link PersistenceConnection} instances
 * participating in a Spring-managed transaction.
 *
 * <h2>Purpose</h2>
 * <p>Registered by {@link ExerisPlatformTransactionManager#prepareSynchronization}
 * after Spring initialises the synchronization infrastructure. Acts as an idempotent
 * last-resort close guard: if the connection was already closed by
 * {@code doCommit}/{@code doRollback} (as is normal), {@link #afterCompletion} is a
 * no-op. If an unexpected code path bypassed the normal close (framework exception
 * before {@code finally} ran), this callback ensures the connection is not leaked.
 *
 * <h2>Mode</h2>
 * <p>Compatibility Mode — registered only for Spring-AOP-driven {@code @Transactional}
 * transactions. Not used in pure-mode request paths.
 *
 * @since 0.1.0
 */
final class ExerisTransactionSynchronizationBridge implements TransactionSynchronization {

    private final PersistenceConnection connection;

    ExerisTransactionSynchronizationBridge(PersistenceConnection connection) {
        this.connection = Objects.requireNonNull(connection, "connection must not be null");
    }

    /**
     * Idempotent safety close. Called by Spring after the transaction has fully
     * completed (committed or rolled back). If the connection is already closed (the
     * normal path), this is a no-op. If it is still open, close it and swallow any
     * pool-return exception so that the original outcome is not obscured.
     *
     * @param status {@link #STATUS_COMMITTED}, {@link #STATUS_ROLLED_BACK}, or
     *               {@link #STATUS_UNKNOWN}
     */
    @Override
    public void afterCompletion(int status) {
        if (connection.isOpen()) {
            try {
                connection.close();
            } catch (RuntimeException _) {
                // idempotent close guard — pool-return failure must not override the
                // transaction outcome that has already been delivered to the caller
            }
        }
    }
}
