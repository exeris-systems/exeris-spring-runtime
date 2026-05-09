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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration test verifying that {@code @Transactional} AOP proxies drive
 * {@link ExerisPlatformTransactionManager} — commit, rollback, and synchronization
 * bridge close guard — when the kernel persistence engine is bound via
 * {@code ScopedValue}.
 *
 * <h2>Mode</h2>
 * <p>Compatibility Mode — validates the {@code @Transactional} annotation path,
 * not the pure-mode {@code TransactionalExecutor} path.
 *
 * <h2>Context Lifecycle</h2>
 * <p>The {@link AnnotationConfigApplicationContext} is created once per test class
 * ({@code @BeforeAll}). Each test method binds a fresh stub engine via
 * {@code ScopedValue.where().run()} — the manager bean is engine-agnostic at
 * construction time.
 *
 * @since 0.1.0
 */
class ExerisTransactionalAopIntegrationTest {

    private static AnnotationConfigApplicationContext context;
    private static TransactionalService service;

    private TrackingEngine engine;

    @BeforeAll
    static void startContext() {
        context = new AnnotationConfigApplicationContext(TestConfig.class);
        service = context.getBean(TransactionalService.class);
    }

    @AfterAll
    static void closeContext() {
        context.close();
    }

    @BeforeEach
    void freshEngine() {
        engine = new TrackingEngine();
    }

    // =========================================================================
    // AOP proxy verification
    // =========================================================================

    @Test
    void service_isAopProxy() {
        // Spring AOP must have wrapped the bean with a transactional proxy
        assertThat(org.springframework.aop.support.AopUtils.isAopProxy(service)).isTrue();
    }

    // =========================================================================
    // Commit path
    // =========================================================================

    @Test
    void transactionalMethod_commitsAndClosesConnection() {
        ScopedValue.where(KernelProviders.PERSISTENCE_ENGINE, engine).run(() -> {
            service.doWork();

            TrackingConnection conn = engine.lastConnection();
            assertThat(conn).isNotNull();
            assertThat(conn.commitCalled).isTrue();
            assertThat(conn.rollbackCalled).isFalse();
            assertThat(conn.closeCalled).isTrue();
        });
    }

    @Test
    void afterCompletion_synchronizationBridge_isNoOpWhenAlreadyClosed() {
        // Normal commit path closes the connection in doCommit's finally block.
        // The synchronization bridge afterCompletion runs afterwards and must be a no-op
        // (connection.isOpen() == false), not cause a double-close error.
        ScopedValue.where(KernelProviders.PERSISTENCE_ENGINE, engine).run(() -> {
            service.doWork();

            TrackingConnection conn = engine.lastConnection();
            // closeCalled = true from doCommit finally (normal path)
            assertThat(conn.closeCalled).isTrue();
            // The bridge ran but did not call close a second time — closeCount is exactly 1
            assertThat(conn.closeCount).isEqualTo(1);
        });
    }

    // =========================================================================
    // Rollback path
    // =========================================================================

    @Test
    void transactionalMethod_rollsBackAndClosesConnectionOnException() {
        ScopedValue.where(KernelProviders.PERSISTENCE_ENGINE, engine).run(() -> {
            assertThatThrownBy(() -> service.doWorkThatFails())
                    .isInstanceOf(RuntimeException.class)
                    .hasMessage("transactional failure");

            TrackingConnection conn = engine.lastConnection();
            assertThat(conn).isNotNull();
            assertThat(conn.rollbackCalled).isTrue();
            assertThat(conn.commitCalled).isFalse();
            assertThat(conn.closeCalled).isTrue();
        });
    }

    // =========================================================================
    // Read-only path
    // =========================================================================

    @Test
    void readOnlyTransactionalMethod_opensReadOnlyTransaction() {
        ScopedValue.where(KernelProviders.PERSISTENCE_ENGINE, engine).run(() -> {
            service.doReadOnlyWork();

            TrackingConnection conn = engine.lastConnection();
            assertThat(conn).isNotNull();
            assertThat(conn.lastReadOnly).isTrue();
            assertThat(conn.commitCalled).isTrue();
        });
    }

    // =========================================================================
    // Spring @Configuration
    // =========================================================================

    @Configuration
    @EnableTransactionManagement
    static class TestConfig {

        @Bean
        ExerisPlatformTransactionManager txManager() {
            return new ExerisPlatformTransactionManager();
        }

        @Bean
        TransactionalService transactionalService() {
            return new TransactionalService();
        }
    }

    // =========================================================================
    // Service under test
    // =========================================================================

    static class TransactionalService {

        @Transactional
        public void doWork() {
            // no-op — just exercises the @Transactional commit path
        }

        @Transactional
        public void doWorkThatFails() {
            throw new RuntimeException("transactional failure");
        }

        @Transactional(readOnly = true)
        public void doReadOnlyWork() {
            // no-op — exercises the read-only flag propagation
        }
    }

    // =========================================================================
    // Tracking stubs
    // =========================================================================

    private static final class TrackingEngine implements PersistenceEngine {
        private TrackingConnection lastConn;

        @Override
        public PersistenceConnection openConnection() {
            lastConn = new TrackingConnection();
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

        TrackingConnection lastConnection() {
            return lastConn;
        }
    }

    private static final class TrackingConnection implements PersistenceConnection {
        boolean commitCalled = false;
        boolean rollbackCalled = false;
        boolean closeCalled = false;
        int closeCount = 0;
        boolean open = true;
        boolean inTx = false;
        boolean lastReadOnly = false;

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
            lastReadOnly = readOnly;
        }

        @Override
        public void commit() {
            commitCalled = true;
            inTx = false;
        }

        @Override
        public void rollback() {
            rollbackCalled = true;
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
            closeCount++;
            open = false;
        }
    }
}
