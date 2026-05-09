/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.tx;

import eu.exeris.kernel.spi.persistence.PersistenceConnection;

/**
 * Callback for JDBC integration layers to receive notification when a new
 * Exeris-managed transaction starts and a {@link PersistenceConnection} is bound.
 *
 * <p>Implemented by {@code ExerisDataSource} (in {@code exeris-spring-runtime-data})
 * to bind a JDBC-compatible proxy to the current Spring transaction synchronization
 * context. Registered on {@link ExerisPlatformTransactionManager} via
 * {@link ExerisPlatformTransactionManager#setJdbcResourceCallback(ExerisJdbcResourceCallback)}.
 *
 * <h2>Timing</h2>
 * <p>The callback is invoked from
 * {@link ExerisPlatformTransactionManager#prepareSynchronization} — after
 * {@code doBegin()} opens the connection AND after Spring activates the
 * synchronization infrastructure. {@link org.springframework.transaction.support.TransactionSynchronizationManager}
 * is fully active at this point.
 *
 * <h2>Mode</h2>
 * <p>Compatibility Mode only. Not used in pure-mode request paths.
 *
 * @since 0.1.0
 */
@FunctionalInterface
public interface ExerisJdbcResourceCallback {

    /**
     * Called when a new Exeris-managed transaction starts with an open connection.
     *
     * <p>Implementations may call
     * {@link org.springframework.transaction.support.TransactionSynchronizationManager#bindResource}
     * and
     * {@link org.springframework.transaction.support.TransactionSynchronizationManager#registerSynchronization}
     * at this point — both are safe because synchronization is active.
     *
     * @param connection the freshly opened, already-in-transaction {@link PersistenceConnection};
     *                   never {@code null}; must NOT be closed by this callback
     */
    void onNewTransactionStarted(PersistenceConnection connection);
}
