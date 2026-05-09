/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.tx;

import eu.exeris.kernel.spi.persistence.PersistenceConnection;

import java.util.Objects;

/**
 * Transaction object carrying the active {@link PersistenceConnection} within
 * a Spring-managed transaction scope.
 *
 * <p>Stored as the transaction object inside Spring's {@code DefaultTransactionStatus}.
 * One instance per active transaction. NOT stored in any {@code ThreadLocal} or
 * {@code ScopedValue} — it travels inside the Spring transaction infrastructure only.
 *
 * <h2>Ownership</h2>
 * <p>{@link ExerisPlatformTransactionManager} creates and owns the lifecycle.
 * {@link #connection()} is used by commit/rollback to drive the underlying
 * persistence connection.
 *
 * @since 0.1.0
 */
final class ExerisTransactionObject {

    private final PersistenceConnection connection;
    private final boolean newConnection;

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
}
