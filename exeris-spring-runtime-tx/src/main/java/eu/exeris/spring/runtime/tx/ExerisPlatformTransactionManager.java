/*
 * Copyright (C) 2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.spring.runtime.tx;

import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.exceptions.persistence.PersistenceProviderException;
import eu.exeris.kernel.spi.persistence.PersistenceConnection;
import eu.exeris.kernel.spi.persistence.TransactionIsolation;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.SmartTransactionObject;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;

import java.util.Objects;

/**
 * {@link org.springframework.transaction.PlatformTransactionManager} implementation
 * backed by the Exeris kernel persistence engine.
 *
 * <h2>Connection Ownership</h2>
 * <p>The active {@link PersistenceConnection} is opened from the kernel engine, held in
 * {@link ExerisTransactionObject}, and closed by this manager when the transaction completes.
 * For the duration of the transaction the object is also bound to Spring's
 * {@link TransactionSynchronizationManager}, keyed by this manager: the binding is how a
 * participating call recognises the transaction it joins, and it is unbound before a suspended
 * predecessor is resumed. No {@code ScopedValue} carries the connection; it travels inside
 * Spring's transaction infrastructure only.
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
 *   <li>{@code SUPPORTS} — joins an existing transaction or runs without one.</li>
 *   <li>{@code MANDATORY} — joins an existing transaction;
 *       {@code IllegalTransactionStateException} if there is none.</li>
 *   <li>{@code NEVER} — {@code IllegalTransactionStateException} if a transaction is active.</li>
 *   <li>{@code REQUIRES_NEW} — suspends an existing transaction, runs on a second, independent
 *       connection, then resumes the first.</li>
 *   <li>{@code NOT_SUPPORTED} — suspends an existing transaction, runs without one, then
 *       resumes it.</li>
 *   <li>{@code NESTED} — {@code UnsupportedOperationException}, inside a transaction or not;
 *       there are no savepoints on the kernel {@code PersistenceConnection} contract.</li>
 * </ul>
 * <p>A participant that rolls back marks the transaction it joined rollback-only, and the
 * outermost commit then rolls back with {@code UnexpectedRollbackException}.
 *
 * <h2>Suspension on a kernel request</h2>
 * <p>Suspending is meaningful only when the engine can hand this thread a connection other than
 * the one the suspended transaction runs on. On a kernel request route it cannot: the kernel holds
 * one connection for the whole request, and {@code openConnection()} returns that connection each
 * time. There, {@code REQUIRES_NEW} and {@code NOT_SUPPORTED} inside a transaction fail with
 * {@code IllegalTransactionStateException} before any of their work runs, and the outer
 * transaction stays active and usable. Work that must commit independently of the request's
 * transaction has to run off the request path.
 *
 * <p>To tell the two cases apart, every suspension opens one connection from the engine and
 * closes it again. On a request route that is the request's own connection and costs nothing.
 * Off the request route it borrows and returns one pooled connection, so a suspension —
 * {@code NOT_SUPPORTED} included, which otherwise needs no connection — needs a free pool slot
 * besides the one the suspended transaction holds. With none free, the suspension waits for the
 * pool's connection timeout and then fails with {@code CannotCreateTransactionException}; the
 * outer transaction stays usable. A kernel SPI signal that names the request-scoped connection is
 * requested, and would replace this probe.
 *
 * <h2>Completion callbacks</h2>
 * <p>A transaction stays bound to the thread after its commit or rollback, until cleanup, so
 * {@code afterCommit} and {@code afterCompletion} callbacks still see it. A callback that starts
 * {@code REQUIRES_NEW} gets a new transaction: off the request route on a second connection, on
 * a request route on the request's connection, which the completed transaction no longer uses.
 * A callback that would join it ({@code REQUIRED}, {@code SUPPORTS}, {@code MANDATORY}) fails
 * with {@code IllegalTransactionStateException} instead of running in a transaction that has
 * already ended.
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
 * @since 0.1
 */
public final class ExerisPlatformTransactionManager extends AbstractPlatformTransactionManager {

    private static final long serialVersionUID = 1L;

    /**
     * Diagnostic logger. Used at {@code DEBUG} level only — never on the request hot path.
     * The transaction manager is silent in normal operation; messages here surface
     * conditions that are intentionally swallowed to preserve commit/rollback exception
     * semantics (e.g. pool-return failure during connection cleanup).
     */
    private static final System.Logger LOGGER =
            System.getLogger(ExerisPlatformTransactionManager.class.getName());

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
        // NESTED is admitted only so that it reaches doBegin, which rejects it with the same
        // exception whether or not a transaction is active. Savepoints are never used.
        setNestedTransactionAllowed(true);
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
     * Returns a new transaction holder, joined to the transaction this manager has bound to the
     * current thread if there is one, so that {@link #isExistingTransaction} detects it.
     */
    @Override
    @NonNull
    protected Object doGetTransaction() {
        ExerisTransactionObjectHolder holder = new ExerisTransactionObjectHolder();
        if (TransactionSynchronizationManager.getResource(this) instanceof ExerisTransactionObject bound) {
            holder.join(bound);
        }
        return holder;
    }

    /**
     * Returns {@code true} if the supplied holder joined a transaction that still owns the
     * thread — begun and not yet cleaned up, including after its commit or rollback while
     * completion callbacks run.
     */
    @Override
    protected boolean isExistingTransaction(@NonNull Object transaction) {
        if (transaction instanceof ExerisTransactionObjectHolder holder) {
            return holder.hasObject() && holder.object().isTransactionActive();
        }
        return false;
    }

    /**
     * Never uses savepoints: {@code NESTED} goes to {@link #doBegin}, which rejects it.
     */
    @Override
    protected boolean useSavepointForNestedTransaction() {
        return false;
    }

    /**
     * Unbinds the current transaction from the thread so that a new one, or none, can run.
     *
     * @throws IllegalTransactionStateException if the engine would hand this thread the
     *         connection the transaction being suspended runs on (a kernel request route), so
     *         there is no connection to suspend onto
     */
    @Override
    @NonNull
    protected Object doSuspend(@NonNull Object transaction) {
        requireIndependentConnection();
        if (transaction instanceof ExerisTransactionObjectHolder holder) {
            holder.clear();
        }
        return TransactionSynchronizationManager.unbindResource(this);
    }

    /**
     * Binds a suspended transaction back to the thread.
     */
    @Override
    protected void doResume(@Nullable Object transaction, @NonNull Object suspendedResources) {
        TransactionSynchronizationManager.bindResource(this, suspendedResources);
    }

    /**
     * Unbinds a completed transaction from the thread. Runs after commit or rollback has closed
     * its connection, and before a suspended predecessor is resumed.
     */
    @Override
    protected void doCleanupAfterCompletion(@NonNull Object transaction) {
        if (transaction instanceof ExerisTransactionObjectHolder holder && holder.isNewTransaction()) {
            holder.object().setTransactionActive(false);
            TransactionSynchronizationManager.unbindResourceIfPossible(this);
        }
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

        if (connection.inTransaction()) {
            closeQuietly(connection);
            throw sharedConnectionInTransaction();
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

        ExerisTransactionObject txObject = new ExerisTransactionObject(connection, true);
        try {
            TransactionSynchronizationManager.bindResource(this, txObject);
        } catch (IllegalStateException ex) {
            // Something other than this manager's live transaction holds the key. The connection
            // is already in a transaction and nothing would ever complete it: end it here.
            rollbackQuietly(connection);
            closeQuietly(connection);
            throw new IllegalTransactionStateException(
                    "Cannot begin an Exeris transaction: a resource is already bound to this "
                    + "transaction manager on the current thread", ex);
        }
        txObject.setTransactionActive(true);
        if (transaction instanceof ExerisTransactionObjectHolder holder) {
            holder.begin(txObject);
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
            txObject.markCompleted();
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
            txObject.markCompleted();
            closeQuietly(txObject.connection());
        }
    }

    /**
     * Marks the transaction a participant joined as rollback-only, so the outermost commit rolls
     * it back instead.
     */
    @Override
    protected void doSetRollbackOnly(@NonNull DefaultTransactionStatus status) {
        extractObject(status).setRollbackOnly();
    }

    /**
     * Registers {@link ExerisTransactionSynchronizationBridge} as a last-resort close
     * guard after Spring initialises the synchronization context.
     *
     * <p>Called by {@code AbstractPlatformTransactionManager.startTransaction()} after
     * {@code doBegin} — synchronization IS active at this point. Registration is skipped
     * if this is not a new transaction (existing transaction joined) or if no connection
     * was bound (e.g. no engine was found, which would have already thrown).
     *
     * @throws IllegalTransactionStateException if the call joins a transaction whose commit or
     *         rollback has already run (a completion callback using a joining propagation)
     */
    @Override
    protected void prepareSynchronization(@NonNull DefaultTransactionStatus status,
                                           @NonNull TransactionDefinition definition) {
        if (!status.isNewTransaction()
                && status.hasTransaction()
                && status.getTransaction() instanceof ExerisTransactionObjectHolder joined
                && joined.hasObject()
                && joined.object().isCompleted()) {
            throw new IllegalTransactionStateException(
                    "Cannot join the current Exeris transaction: it has already committed or rolled "
                    + "back, and its completion callbacks are running. Use PROPAGATION_REQUIRES_NEW "
                    + "for transactional work started from afterCommit or afterCompletion.");
        }
        super.prepareSynchronization(status, definition);
        if (status.isNewTransaction()
                && status.getTransaction() instanceof ExerisTransactionObjectHolder holder
                && holder.hasObject()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            PersistenceConnection connection = holder.object().connection();
            try {
                TransactionSynchronizationManager.registerSynchronization(
                        new ExerisTransactionSynchronizationBridge(connection));
                if (jdbcResourceCallback != null) {
                    jdbcResourceCallback.onNewTransactionStarted(connection);
                }
            } catch (RuntimeException ex) {
                // Spring returns no status when this throws, so nothing would ever complete this
                // transaction: release what it holds on the thread before Spring resumes a
                // suspended predecessor.
                TransactionSynchronizationManager.unbindResourceIfPossible(this);
                if (status.isNewSynchronization()) {
                    TransactionSynchronizationManager.clear();
                }
                if (connection.isOpen()) {
                    closeQuietly(connection);
                }
                throw ex;
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
                    "PROPAGATION_NESTED is not supported by ExerisPlatformTransactionManager. " +
                    "Use PROPAGATION_REQUIRED or PROPAGATION_REQUIRES_NEW.");
        }
        // PROPAGATION_NOT_SUPPORTED never reaches doBegin: Spring suspends through doSuspend and
        // runs without a transaction. See class Javadoc.
    }

    /**
     * Proves the engine can hand this thread a connection outside the transaction about to be
     * suspended. A freshly opened connection is never already in a transaction; one that is, is
     * the suspended transaction's own connection handed back again, because the kernel serves a
     * request route with one connection.
     *
     * <p>Off the request route the probe borrows and returns one pooled connection, and waits for
     * the pool's connection timeout when no slot is free; see the class Javadoc. It stands in for
     * a kernel SPI signal that is requested and not yet available.
     */
    private static void requireIndependentConnection() {
        if (!KernelProviders.PERSISTENCE_ENGINE.isBound()) {
            return;
        }
        PersistenceConnection probe;
        try {
            probe = KernelProviders.PERSISTENCE_ENGINE.get().openConnection();
        } catch (PersistenceProviderException ex) {
            throw new CannotCreateTransactionException(
                    "Failed to open an Exeris persistence connection to suspend the current transaction onto",
                    ex);
        }
        try {
            if (probe.inTransaction()) {
                throw sharedConnectionInTransaction();
            }
        } finally {
            closeQuietly(probe);
        }
    }

    private static IllegalTransactionStateException sharedConnectionInTransaction() {
        return new IllegalTransactionStateException(
                "Cannot suspend the current Exeris transaction: the persistence engine hands this "
                + "thread the connection that transaction runs on. A kernel request route holds one "
                + "connection for the whole request, so PROPAGATION_REQUIRES_NEW and "
                + "PROPAGATION_NOT_SUPPORTED inside a transaction cannot run there. Run work that "
                + "must commit independently off the request path.");
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

    private static void rollbackQuietly(PersistenceConnection connection) {
        try {
            if (connection.inTransaction()) {
                connection.rollback();
            }
        } catch (RuntimeException ex) {
            // The failure that prompted the rollback is the one the caller must see.
            LOGGER.log(System.Logger.Level.DEBUG,
                    "Failed to roll back PersistenceConnection after a failed transaction begin", ex);
        }
    }

    private static void closeQuietly(@Nullable PersistenceConnection connection) {
        if (connection != null) {
            try {
                connection.close();
            } catch (RuntimeException ex) {
                // Pool return failure must not override original exception (commit/rollback outcome
                // takes precedence).
                LOGGER.log(System.Logger.Level.DEBUG,
                        "Failed to close PersistenceConnection during transaction finalize", ex);
            }
        }
    }

    // =========================================================================
    // Inner transaction holder
    // =========================================================================

    /**
     * Mutable holder passed through {@code AbstractPlatformTransactionManager}'s
     * {@code doGetTransaction} / {@code doBegin} lifecycle, one per {@code getTransaction} call.
     * Holds the {@link ExerisTransactionObject} it joined, or the one {@code doBegin} created.
     */
    static final class ExerisTransactionObjectHolder implements SmartTransactionObject {

        @Nullable
        private ExerisTransactionObject txObject;
        private boolean newTransaction;

        void begin(ExerisTransactionObject obj) {
            this.txObject = Objects.requireNonNull(obj);
            this.newTransaction = true;
        }

        void join(ExerisTransactionObject obj) {
            this.txObject = Objects.requireNonNull(obj);
            this.newTransaction = false;
        }

        void clear() {
            this.txObject = null;
            this.newTransaction = false;
        }

        boolean isNewTransaction() {
            return newTransaction;
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

        @Override
        public boolean isRollbackOnly() {
            return txObject != null && txObject.isRollbackOnly();
        }

        @Override
        public void flush() {
            TransactionSynchronizationUtils.triggerFlush();
        }
    }
}
