/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.tx;

import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.persistence.ConnectionInterceptor;
import eu.exeris.kernel.spi.persistence.EngineStats;
import eu.exeris.kernel.spi.persistence.PersistenceConnection;
import eu.exeris.kernel.spi.persistence.PersistenceEngine;
import eu.exeris.kernel.spi.persistence.PersistenceHealthStatus;
import eu.exeris.kernel.spi.persistence.PersistenceStatement;
import eu.exeris.kernel.spi.persistence.QueryResult;
import eu.exeris.kernel.spi.persistence.TransactionIsolation;
import eu.exeris.kernel.spi.security.StorageContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link ExerisPlatformTransactionManager}.
 */
class ExerisPlatformTransactionManagerTest {

    private ExerisPlatformTransactionManager txManager;
    private StubPersistenceEngine engine;

    @BeforeEach
    void setUp() {
        txManager = new ExerisPlatformTransactionManager();
        engine = new StubPersistenceEngine();
    }

    // =========================================================================
    // Engine not bound
    // =========================================================================

    @Test
    void getTransaction_whenEngineNotBound_throwsCannotCreate() {
        // No ScopedValue binding — PERSISTENCE_ENGINE is unbound
        DefaultTransactionDefinition def = new DefaultTransactionDefinition();
        assertThatThrownBy(() -> txManager.getTransaction(def))
                .isInstanceOf(CannotCreateTransactionException.class)
                .hasMessageContaining("Exeris PersistenceEngine is not bound");
    }

    // =========================================================================
    // Happy path — bound engine
    // =========================================================================

    @Test
    void getTransaction_whenEngineBound_returnsActiveStatus() {
        ScopedValue.where(KernelProviders.PERSISTENCE_ENGINE, engine).run(() -> {
            TransactionStatus status = txManager.getTransaction(
                    new DefaultTransactionDefinition());
            assertThat(status.isNewTransaction()).isTrue();
            txManager.commit(status);
        });
    }

    @Test
    void commit_closesConnection() {
        ScopedValue.where(KernelProviders.PERSISTENCE_ENGINE, engine).run(() -> {
            TransactionStatus status = txManager.getTransaction(
                    new DefaultTransactionDefinition());
            txManager.commit(status);
            assertThat(engine.lastConnection().closeCalled).isTrue();
        });
    }

    @Test
    void rollback_closesConnection() {
        ScopedValue.where(KernelProviders.PERSISTENCE_ENGINE, engine).run(() -> {
            TransactionStatus status = txManager.getTransaction(
                    new DefaultTransactionDefinition());
            txManager.rollback(status);
            assertThat(engine.lastConnection().closeCalled).isTrue();
        });
    }

    // =========================================================================
    // Unsupported propagations
    // =========================================================================

    @Test
    void getTransaction_nestedPropagation_throws() {
        DefaultTransactionDefinition def = new DefaultTransactionDefinition();
        def.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
        ScopedValue.where(KernelProviders.PERSISTENCE_ENGINE, engine).run(() ->
                assertThatThrownBy(() -> txManager.getTransaction(def))
                        .isInstanceOf(UnsupportedOperationException.class)
                        .hasMessageContaining("PROPAGATION_NESTED"));
    }

    @Test
    void getTransaction_notSupportedPropagation_runsNonTransactionally() {
        // PROPAGATION_NOT_SUPPORTED is handled silently by AbstractPlatformTransactionManager
        // before doBegin is reached — it returns a non-transactional status without opening
        // a connection. This is a known Phase 3 enforcement gap: the guard in doBegin fires
        // only for NOT_SUPPORTED when an existing transaction exists (via doSuspend).
        DefaultTransactionDefinition def = new DefaultTransactionDefinition();
        def.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
        ScopedValue.where(KernelProviders.PERSISTENCE_ENGINE, engine).run(() -> {
            TransactionStatus status = txManager.getTransaction(def);
            assertThat(status.isNewTransaction()).isFalse();
            // No connection was opened
            assertThat(engine.lastConnection()).isNull();
        });
    }

    // =========================================================================
    // Stub engine + stub connection
    // =========================================================================

    private static final class StubPersistenceEngine implements PersistenceEngine {
        private StubPersistenceConnection lastConn;

        @Override
        public PersistenceConnection openConnection() {
            lastConn = new StubPersistenceConnection();
            return lastConn;
        }

        @Override
        public PersistenceConnection openConnection(StorageContext storageContext) {
            return openConnection();
        }

        @Override
        public PersistenceHealthStatus healthCheckDetailed() {
            throw new UnsupportedOperationException("stub");
        }

        @Override
        public void registerInterceptor(ConnectionInterceptor interceptor) {
            throw new UnsupportedOperationException("stub");
        }

        @Override
        public EngineStats stats() {
            throw new UnsupportedOperationException("stub");
        }

        @Override
        public boolean canServiceRequest() {
            return true;
        }

        @Override
        public void close() {
        }

        StubPersistenceConnection lastConnection() {
            return lastConn;
        }
    }

    private static final class StubPersistenceConnection implements PersistenceConnection {
        boolean closeCalled = false;
        boolean open = true;
        boolean inTx = false;

        @Override
        public PersistenceStatement prepare(String sql) {
            throw new UnsupportedOperationException("stub");
        }

        @Override
        public QueryResult executeQuery(String sql) {
            throw new UnsupportedOperationException("stub");
        }

        @Override
        public long executeUpdate(String sql) {
            throw new UnsupportedOperationException("stub");
        }

        @Override
        public void beginTransaction() {
            inTx = true;
        }

        @Override
        public void beginTransaction(TransactionIsolation isolation, boolean readOnly) {
            inTx = true;
        }

        @Override
        public void commit() {
            inTx = false;
        }

        @Override
        public void rollback() {
            inTx = false;
        }

        @Override
        public boolean inTransaction() {
            return inTx;
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public void close() {
            closeCalled = true;
            open = false;
        }
    }
}
