/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.data.compat;

import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.exceptions.persistence.PersistenceProviderException;
import eu.exeris.kernel.spi.persistence.PersistenceConnection;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * Compatibility-mode {@link DataSource} adapter for the Exeris JDBC compatibility bridge.
 *
 * <h2>Purpose (ADR-017 Option B)</h2>
 * <p>Enables JPA/Hibernate to obtain a JDBC {@link Connection} backed by the Exeris
 * Community persistence engine. The returned {@link ExerisConnectionProxy} intercepts
 * lifecycle methods ({@code close}, {@code commit}, {@code rollback},
 * {@code setAutoCommit(true)}, {@code setTransactionIsolation}, {@code unwrap}) while
 * passing all other method calls directly to the real JDBC driver.
 *
 * <h2>Connection Reuse in @Transactional scope</h2>
 * <p>When {@link ExerisPlatformTransactionManager} starts a new transaction, it calls
 * {@link #bindTransactionConnection} via the registered {@link eu.exeris.spring.runtime.tx.ExerisJdbcResourceCallback}.
 * Subsequent calls to {@link #getConnection()} within the same transaction return the
 * already-bound proxy (one connection per transaction).
 *
 * <h2>Non-transactional path</h2>
 * <p>Outside a Spring-managed transaction, each {@link #getConnection()} call opens
 * a new {@link PersistenceConnection}. The caller is responsible for closing it.
 *
 * <h2>Community tier only</h2>
 * <p>Throws {@link UnsupportedOperationException} if a non-JDBC engine is bound.
 * Enterprise compatibility requires {@code exeris-spring-runtime-enterprise}.
 *
 * <h2>JFR observability</h2>
 * <p>Emits {@link JpaConnectionAcquiredEvent} on new opens and
 * {@link JpaConnectionBoundEvent} on transactional reuse.
 *
 * @since 0.1.0
 * @see ExerisConnectionProxy
 */
public final class ExerisDataSource implements DataSource {

    // =========================================================================
    // DataSource implementation
    // =========================================================================

    @Override
    public Connection getConnection() throws SQLException {
        // 1. Check for active transaction-bound proxy
        ExerisConnectionProxy bound =
                (ExerisConnectionProxy) TransactionSynchronizationManager.getResource(this);
        if (bound != null) {
            JpaConnectionBoundEvent.emit();
            return bound;
        }

        // 2. No active transaction — open a new connection (caller owns close)
        PersistenceConnection conn = openConnection();
        Connection rawJdbc = unwrapJdbc(conn);

        // SPI unwrap(Connection.class) — approved caller: integration bridge. See ADR-017 §6.4.
        ExerisConnectionProxy proxy =
                new ExerisConnectionProxy(rawJdbc, conn, false);
        JpaConnectionAcquiredEvent.emit();
        return proxy;
    }

    /**
     * Not supported — credential-parameterised connection acquisition is outside the
     * scope of the Exeris JDBC bridge (ADR-017 §8, Non-Goals).
     */
    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        throw new SQLFeatureNotSupportedException(
                "Credential-parameterised getConnection() is not supported by ExerisDataSource. " +
                "Connection credentials are managed by the Exeris PersistenceEngine. " +
                "See ADR-017 §8 Non-Goals.");
    }

    // =========================================================================
    // Transaction integration — called by ExerisJdbcResourceCallback
    // =========================================================================

    /**
     * Binds a transaction-scoped {@link ExerisConnectionProxy} to the current
     * {@link TransactionSynchronizationManager}.
     *
     * <p>Called by {@code ExerisDataAutoConfiguration} via
     * {@link eu.exeris.spring.runtime.tx.ExerisJdbcResourceCallback} when a new
     * {@link eu.exeris.spring.runtime.tx.ExerisPlatformTransactionManager}-managed
     * transaction starts.
     *
     * <p>Must only be called when synchronization is active (i.e., from
     * {@code ExerisPlatformTransactionManager.prepareSynchronization}).
     *
     * @param connection the freshly opened, already-in-transaction PersistenceConnection
     */
    public void bindTransactionConnection(PersistenceConnection connection) {
        Connection rawJdbc = unwrapJdbc(connection);

        // SPI unwrap(Connection.class) — approved caller: integration bridge. See ADR-017 §6.4.
        ExerisConnectionProxy proxy =
                new ExerisConnectionProxy(rawJdbc, connection, true);

        // Guard the bind/register sequence: if bindResource throws (a resource is already
        // bound to this key) or registerSynchronization throws after a successful bind, the
        // freshly opened PersistenceConnection would otherwise leak. Not reachable under normal
        // wiring (ExerisJdbcResourceCallback checks for an active transaction first), but the
        // exception path must not orphan a connection.
        //
        // Close the PersistenceConnection directly, NOT proxy.close(): the proxy is built with
        // transactionActive=true, so ExerisConnectionProxy.close() is a deliberate no-op (the
        // tx manager owns the lifecycle on the happy path). On this failure path no tx manager
        // will ever complete, so we must release the underlying connection ourselves.
        boolean bound = false;
        try {
            TransactionSynchronizationManager.bindResource(this, proxy);
            bound = true;
            TransactionSynchronizationManager.registerSynchronization(
                    new UnbindOnCompletionSynchronization(this));
            JpaConnectionBoundEvent.emit();
        } catch (RuntimeException ex) {
            if (bound && TransactionSynchronizationManager.hasResource(this)) {
                TransactionSynchronizationManager.unbindResource(this);
            }
            try {
                connection.close();
            } catch (Exception suppressed) {
                ex.addSuppressed(suppressed);
            }
            throw ex;
        }
    }

    // =========================================================================
    // DataSource metadata — minimal impl
    // =========================================================================

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) {
            return iface.cast(this);
        }
        throw new SQLException("ExerisDataSource cannot unwrap " + iface.getName());
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return iface.isInstance(this);
    }

    @Override
    public PrintWriter getLogWriter() throws SQLException {
        return null;
    }

    @Override
    public void setLogWriter(PrintWriter out) throws SQLException {
        // no-op — Exeris uses JFR/JUL, not JDBC LogWriter
    }

    @Override
    public void setLoginTimeout(int seconds) throws SQLException {
        // no-op — timeout is governed by PersistenceEngine configuration
    }

    @Override
    public int getLoginTimeout() throws SQLException {
        return 0;
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        throw new SQLFeatureNotSupportedException("ExerisDataSource does not use java.util.logging.");
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    private static PersistenceConnection openConnection() throws SQLException {
        if (!KernelProviders.PERSISTENCE_ENGINE.isBound()) {
            throw new SQLException(
                    "Exeris PersistenceEngine is not bound in the current scope. " +
                    "Ensure this method is called from a kernel-owned Virtual Thread " +
                    "after ExerisRuntimeLifecycle.start() has completed.");
        }
        try {
            return KernelProviders.PERSISTENCE_ENGINE.get().openConnection();
        } catch (PersistenceProviderException ex) {
            throw new SQLException("Failed to open Exeris persistence connection", ex);
        }
    }

    /**
     * Unwraps the raw {@link Connection} from a kernel {@link PersistenceConnection} via the SPI
     * {@link PersistenceConnection#unwrap(Class)} (kernel 0.8.1+). This works for both a directly
     * JDBC-backed connection and the request-session forwarding wrapper the kernel binds per
     * request (the wrapper forwards {@code unwrap} to its delegate). Using the SPI unwrap keeps
     * this bridge decoupled from the community-concrete connection type and swappable across
     * engines.
     */
    private static Connection unwrapJdbc(PersistenceConnection conn) {
        Optional<Connection> rawJdbc = conn.unwrap(Connection.class);
        if (rawJdbc.isPresent()) {
            return rawJdbc.get();
        }
        try {
            conn.close();
        } catch (Exception ignored) {
            // best-effort cleanup before failing
        }
        throw new UnsupportedOperationException(
                "ExerisDataSource requires a JDBC-backed PersistenceEngine (Community tier). " +
                "The bound engine's connection does not unwrap to java.sql.Connection. " +
                "Enterprise-tier JDBC compatibility requires exeris-spring-runtime-enterprise.");
    }

    // =========================================================================
    // Inner class
    // =========================================================================

    private record UnbindOnCompletionSynchronization(ExerisDataSource dataSource)
            implements TransactionSynchronization {

        @Override
        public void afterCompletion(int status) {
            TransactionSynchronizationManager.unbindResourceIfPossible(dataSource);
        }
    }
}

