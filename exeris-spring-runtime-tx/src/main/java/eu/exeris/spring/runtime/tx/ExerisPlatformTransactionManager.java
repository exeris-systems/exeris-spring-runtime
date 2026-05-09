/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.tx;

import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.exceptions.persistence.PersistenceProviderException;
import eu.exeris.kernel.spi.persistence.PersistenceConnection;
import eu.exeris.kernel.spi.persistence.TransactionIsolation;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Objects;

/**
 * {@link org.springframework.transaction.PlatformTransactionManager} implementation
 * backed by the Exeris kernel persistence engine.
 *
 * <h2>Connection Ownership</h2>
 * <p>The active {@link PersistenceConnection} is held in {@link ExerisTransactionObject},
 * stored by Spring's {@code AbstractPlatformTransactionManager} inside
 * {@code DefaultTransactionStatus}. No {@code ThreadLocal} or {@code ScopedValue}
 * carries the connection — it travels inside Spring's transaction infrastructure only.
 *
 * <h2>Engine Resolution</h2>
 * <p>{@link KernelProviders#PERSISTENCE_ENGINE} is read at transaction-begin time
 * ({@link #doBegin}) — never at bean construction. This preserves the
 * {@code ScopedValue} contract: the engine is always read from the current kernel
 * VT scope, not captured once as a static singleton.
 *
 * <h2>Propagation Support</h2>
 * <ul>
 *   <li>{@code REQUIRED} — joins an existing transaction or starts a new one.</li>
 *   <li>{@code REQUIRES_NEW} — always opens a second independent connection.</li>
 *   <li>{@code MANDATORY} — requires an active transaction; throws if none.</li>
 *   <li>{@code NESTED}, {@code NOT_SUPPORTED} — {@code UnsupportedOperationException}
 *       (Phase 3 scope limit).</li>
 * </ul>
 *
 * <h2>Retry Gap</h2>
 * <p>Unlike {@link eu.exeris.kernel.spi.persistence.TransactionalExecutor#executeManaged},
 * this manager does NOT automatically retry on {@code 40001} / {@code 40P01}
 * serialization failures. Callers requiring automatic retry MUST use
 * {@code TransactionalExecutor} directly.
 *
 * <h2>Mode</h2>
 * <p>Compatibility Mode — enables {@code @Transactional} AOP on Spring-managed beans.
 *
 * @since 0.1.0
 */
public final class ExerisPlatformTransactionManager extends AbstractPlatformTransactionManager {

    private static final long serialVersionUID = 1L;

    /**
     * Optional JDBC resource callback for integration bridges (e.g., {@code ExerisDataSource}).
     * Null when no JDBC compat bridge is active.
     */
    @Nullable
    private transient ExerisJdbcResourceCallback jdbcResourceCallback;

    /**
     * Constructs the transaction manager.
     *
     * <p>Does NOT acquire a {@link eu.exeris.kernel.spi.persistence.PersistenceEngine}
     * at this point — engine acquisition is deferred to {@link #doBegin} when an
     * actual transaction is needed.
     */
    public ExerisPlatformTransactionManager() {
        setNestedTransactionAllowed(false);
    }

    /**
     * Sets the optional JDBC resource callback invoked during
     * {@link #prepareSynchronization} when a new transaction starts.
     *
     * <p>Called by {@code ExerisDataAutoConfiguration} to wire {@code ExerisDataSource}
     * as the JDBC resource provider for the current transaction manager.
     *
     * @param callback the callback; {@code null} to deactivate
     */
    public void setJdbcResourceCallback(@Nullable ExerisJdbcResourceCallback callback) {
        this.jdbcResourceCallback = callback;
    }

    // =========================================================================
    // AbstractPlatformTransactionManager overrides
    // =========================================================================

    /**
     * Returns a new transaction holder. If the current
     * {@code AbstractPlatformTransactionManager} resource-binding infrastructure
     * has an active transaction, {@link #isExistingTransaction} will detect it
     * via the bound holder.
     */
    @Override
    @NonNull
    protected Object doGetTransaction() {
        return new ExerisTransactionObjectHolder();
    }

    /**
     * Returns {@code true} if the supplied transaction holder has an active connection.
     */
    @Override
    protected boolean isExistingTransaction(@NonNull Object transaction) {
        if (transaction instanceof ExerisTransactionObjectHolder holder) {
            return holder.hasObject() && holder.object().hasActiveConnection();
        }
        return false;
    }

    /**
     * Begins a transaction by opening a {@link PersistenceConnection} from the
     * kernel engine and binding it to the transaction holder.
     *
     * @throws CannotCreateTransactionException if {@code PERSISTENCE_ENGINE} is not
     *         bound (kernel not bootstrapped or called outside VT scope)
     */
    @Override
    protected void doBegin(@NonNull Object transaction,
                           @NonNull TransactionDefinition definition) {
        guardNotNestedOrUnsupported(definition.getPropagationBehavior());

        if (!KernelProviders.PERSISTENCE_ENGINE.isBound()) {
            throw new CannotCreateTransactionException(
                    "Exeris PersistenceEngine is not bound in the current scope. " +
                    "Ensure this method is called from a kernel-owned Virtual Thread " +
                    "after ExerisRuntimeLifecycle.start() has completed.");
        }

        PersistenceConnection connection;
        try {
            connection = KernelProviders.PERSISTENCE_ENGINE.get().openConnection();
        } catch (PersistenceProviderException ex) {
            throw new CannotCreateTransactionException(
                    "Failed to open Exeris persistence connection", ex);
        }

        try {
            TransactionIsolation isolation = toIsolation(definition.getIsolationLevel());
            boolean readOnly = definition.isReadOnly();
            connection.beginTransaction(isolation, readOnly);
        } catch (PersistenceProviderException ex) {
            closeQuietly(connection);
            throw new CannotCreateTransactionException(
                    "Failed to begin Exeris transaction", ex);
        }

        if (transaction instanceof ExerisTransactionObjectHolder holder) {
            holder.bind(new ExerisTransactionObject(connection, true));
        }
    }

    /**
     * Commits the transaction.
     */
    @Override
    protected void doCommit(@NonNull DefaultTransactionStatus status) {
        ExerisTransactionObject txObject = extractObject(status);
        try {
            txObject.connection().commit();
        } catch (PersistenceProviderException ex) {
            throw new TransactionSystemException("Exeris commit failed", ex);
        } finally {
            closeQuietly(txObject.connection());
        }
    }

    /**
     * Rolls back the transaction.
     */
    @Override
    protected void doRollback(@NonNull DefaultTransactionStatus status) {
        ExerisTransactionObject txObject = extractObject(status);
        try {
            if (txObject.connection().inTransaction()) {
                txObject.connection().rollback();
            }
        } catch (PersistenceProviderException ex) {
            throw new TransactionSystemException("Exeris rollback failed", ex);
        } finally {
            closeQuietly(txObject.connection());
        }
    }

    /**
     * Marks the transaction as rollback-only.
     */
    @Override
    protected void doSetRollbackOnly(@NonNull DefaultTransactionStatus status) {
        status.setRollbackOnly();
    }

    /**
     * Registers {@link ExerisTransactionSynchronizationBridge} as a last-resort close
     * guard after Spring initialises the synchronization context.
     *
     * <p>Called by {@code AbstractPlatformTransactionManager.startTransaction()} after
     * {@code doBegin} — synchronization IS active at this point. Registration is skipped
     * if this is not a new transaction (existing transaction joined) or if no connection
     * was bound (e.g. no engine was found, which would have already thrown).
     */
    @Override
    protected void prepareSynchronization(@NonNull DefaultTransactionStatus status,
                                           @NonNull TransactionDefinition definition) {
        super.prepareSynchronization(status, definition);
        if (status.isNewTransaction()
                && status.getTransaction() instanceof ExerisTransactionObjectHolder holder
                && holder.hasObject()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new ExerisTransactionSynchronizationBridge(holder.object().connection()));
            if (jdbcResourceCallback != null) {
                jdbcResourceCallback.onNewTransactionStarted(holder.object().connection());
            }
        }
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    private static ExerisTransactionObject extractObject(DefaultTransactionStatus status) {
        Object raw = status.getTransaction();
        if (raw instanceof ExerisTransactionObjectHolder holder && holder.hasObject()) {
            return holder.object();
        }
        throw new IllegalStateException(
                "No active ExerisTransactionObject found in DefaultTransactionStatus");
    }

    private static void guardNotNestedOrUnsupported(int propagation) {
        if (propagation == TransactionDefinition.PROPAGATION_NESTED) {
            throw new UnsupportedOperationException(
                    "PROPAGATION_NESTED is not supported by ExerisPlatformTransactionManager " +
                    "(Phase 3 scope). Use PROPAGATION_REQUIRED or PROPAGATION_REQUIRES_NEW.");
        }
        // Note: PROPAGATION_NOT_SUPPORTED is handled silently by AbstractPlatformTransactionManager
        // before doBegin is reached. It is not guarded here — see class Javadoc.
    }

    private static TransactionIsolation toIsolation(int springIsolation) {
        return switch (springIsolation) {
            case TransactionDefinition.ISOLATION_READ_UNCOMMITTED -> TransactionIsolation.READ_UNCOMMITTED;
            case TransactionDefinition.ISOLATION_READ_COMMITTED   -> TransactionIsolation.READ_COMMITTED;
            case TransactionDefinition.ISOLATION_REPEATABLE_READ  -> TransactionIsolation.REPEATABLE_READ;
            case TransactionDefinition.ISOLATION_SERIALIZABLE     -> TransactionIsolation.SERIALIZABLE;
            default -> TransactionIsolation.READ_COMMITTED; // ISOLATION_DEFAULT = -1
        };
    }

    private static void closeQuietly(@Nullable PersistenceConnection connection) {
        if (connection != null) {
            try {
                connection.close();
            } catch (RuntimeException ignored) {
                // pool return failure must not override original exception
            }
        }
    }

    // =========================================================================
    // Inner transaction holder
    // =========================================================================

    /**
     * Mutable holder passed through {@code AbstractPlatformTransactionManager}'s
     * {@code doGetTransaction} / {@code doBegin} lifecycle. Holds the
     * {@link ExerisTransactionObject} once {@code doBegin} binds a connection.
     */
    static final class ExerisTransactionObjectHolder {

        @Nullable
        private ExerisTransactionObject txObject;

        void bind(ExerisTransactionObject obj) {
            this.txObject = Objects.requireNonNull(obj);
        }

        boolean hasObject() {
            return txObject != null;
        }

        ExerisTransactionObject object() {
            if (txObject == null) {
                throw new IllegalStateException("No transaction object bound");
            }
            return txObject;
        }

        boolean hasActiveConnection() {
            return txObject != null && txObject.hasActiveConnection();
        }
    }
}
