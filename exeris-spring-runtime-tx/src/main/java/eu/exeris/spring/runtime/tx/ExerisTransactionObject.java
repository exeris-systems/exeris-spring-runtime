/*
 * Copyright (C) 2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.spring.runtime.tx;

import eu.exeris.kernel.spi.persistence.PersistenceConnection;

import java.util.Objects;

/**
 * Transaction object carrying the active {@link PersistenceConnection} within
 * a Spring-managed transaction scope.
 *
 * <p>One instance per physical transaction. It is bound to Spring's
 * {@code TransactionSynchronizationManager} for the duration of that transaction, so that a
 * participating call finds and joins it, and travels inside {@code DefaultTransactionStatus}.
 * It is not stored in a {@code ScopedValue} of its own.
 *
 * <h2>Ownership</h2>
 * <p>{@link ExerisPlatformTransactionManager} creates and owns the lifecycle.
 * {@link #connection()} is used by commit/rollback to drive the underlying
 * persistence connection.
 *
 * @since 0.1
 */
final class ExerisTransactionObject {

    private final PersistenceConnection connection;
    private final boolean newConnection;
    private boolean rollbackOnly;

    ExerisTransactionObject(PersistenceConnection connection, boolean newConnection) {
        this.connection = Objects.requireNonNull(connection, "connection must not be null");
        this.newConnection = newConnection;
    }

    PersistenceConnection connection() {
        return connection;
    }

    boolean isNewConnection() {
        return newConnection;
    }

    boolean hasActiveConnection() {
        return connection.isOpen();
    }

    /**
     * Marks the physical transaction rollback-only. Shared by every participant that joined it,
     * so a participant's rollback decides the outcome of the transaction it joined.
     */
    void setRollbackOnly() {
        this.rollbackOnly = true;
    }

    boolean isRollbackOnly() {
        return rollbackOnly;
    }
}
