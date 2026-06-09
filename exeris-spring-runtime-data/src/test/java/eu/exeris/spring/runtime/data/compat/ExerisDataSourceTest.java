/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.data.compat;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class ExerisDataSourceTest {

    private final ExerisDataSource dataSource = new ExerisDataSource();

    @Test
    void getConnection_withNoBoundEngine_throwsSqlException() {
        // KernelProviders.PERSISTENCE_ENGINE is not bound outside VT scope
        assertThatThrownBy(dataSource::getConnection)
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("PersistenceEngine is not bound");
    }

    @Test
    void getConnection_withTransactionBoundProxy_returnsItWithoutOpening() throws SQLException {
        // Simulate an active ExerisPlatformTransactionManager transaction: a proxy is already
        // bound to the dataSource key. getConnection() must return that proxy (one connection
        // per transaction) instead of opening a new PersistenceConnection.
        var bound = new ExerisConnectionProxy(
                mock(java.sql.Connection.class), new StubJdbcPersistenceConnection(), true);
        TransactionSynchronizationManager.bindResource(dataSource, bound);
        try {
            assertThat(dataSource.getConnection()).isSameAs(bound);
        } finally {
            TransactionSynchronizationManager.unbindResourceIfPossible(dataSource);
        }
    }

    @Test
    void getConnectionWithCredentials_throwsSqlFeatureNotSupportedException() {
        assertThatThrownBy(() -> dataSource.getConnection("user", "pass"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("Credential-parameterised");
    }

    @Test
    void unwrap_returnsSelf_whenIfaceMatches() throws SQLException {
        ExerisDataSource result = dataSource.unwrap(ExerisDataSource.class);
        assertThat(result).isSameAs(dataSource);
    }

    @Test
    void unwrap_throwsSqlException_whenIfaceDoesNotMatch() {
        assertThatThrownBy(() -> dataSource.unwrap(String.class))
                .isInstanceOf(SQLException.class);
    }

    @Test
    void isWrapperFor_returnsTrue_forOwnClass() throws SQLException {
        assertThat(dataSource.isWrapperFor(ExerisDataSource.class)).isTrue();
    }

    @Test
    void isWrapperFor_returnsFalse_forOtherClass() throws SQLException {
        assertThat(dataSource.isWrapperFor(String.class)).isFalse();
    }

    @Test
    void getLoginTimeout_returnsZero() throws SQLException {
        assertThat(dataSource.getLoginTimeout()).isZero();
    }

    @Test
    void getParentLogger_throwsSqlFeatureNotSupportedException() {
        assertThatThrownBy(dataSource::getParentLogger)
                .isInstanceOf(SQLException.class);
    }

    @Test
    void getLogWriter_returnsNull() throws SQLException {
        assertThat(dataSource.getLogWriter()).isNull();
    }

    @Test
    void setLogWriter_isNoOp() {
        // Exeris uses JFR/JUL, not the JDBC LogWriter — the setter is a deliberate no-op.
        assertThatCode(() -> dataSource.setLogWriter(null)).doesNotThrowAnyException();
    }

    @Test
    void setLoginTimeout_isNoOp() {
        // Timeout is governed by PersistenceEngine configuration — the setter is a no-op.
        assertThatCode(() -> dataSource.setLoginTimeout(5)).doesNotThrowAnyException();
    }

    // -------------------------------------------------------------------------
    // bindTransactionConnection — tested via TransactionSynchronizationManager
    // -------------------------------------------------------------------------

    @Test
    void bindTransactionConnection_throwsUnsupportedOperation_whenEngineNotJdbcBacked() {
        // Use a non-JdbcPersistenceConnection stub
        var stubConn = new StubNonJdbcPersistenceConnection();
        assertThatThrownBy(() -> dataSource.bindTransactionConnection(stubConn))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("exeris-spring-runtime-enterprise");
    }

    @Test
    void bindTransactionConnection_happyPath_bindsProxyAndUnbindsOnCompletion() {
        // Synchronization must be active for registerSynchronization() to succeed.
        TransactionSynchronizationManager.initSynchronization();
        try {
            var stubConn = new StubJdbcPersistenceConnection();
            dataSource.bindTransactionConnection(stubConn);

            // Proxy is now bound to the dataSource key and a completion sync is registered.
            assertThat(TransactionSynchronizationManager.getResource(dataSource))
                    .isInstanceOf(ExerisConnectionProxy.class);
            var syncs = TransactionSynchronizationManager.getSynchronizations();
            assertThat(syncs).isNotEmpty();

            // Drive UnbindOnCompletionSynchronization.afterCompletion → resource is unbound.
            syncs.forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_COMMITTED));
            assertThat(TransactionSynchronizationManager.getResource(dataSource)).isNull();
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.unbindResourceIfPossible(dataSource);
        }
    }

    @Test
    void bindTransactionConnection_closesConnection_whenBindResourceFails() {
        // Pre-bind a resource to the dataSource key so the subsequent bindResource() in
        // bindTransactionConnection throws IllegalStateException ("already bound"). The guard
        // must then release the freshly opened PersistenceConnection rather than orphan it.
        // (transactionActive=true makes ExerisConnectionProxy.close() a no-op, so the connection
        // is closed directly — this test would fail if the guard called proxy.close() instead.)
        var stubConn = new StubJdbcPersistenceConnection();
        TransactionSynchronizationManager.bindResource(dataSource, new Object());
        try {
            assertThatThrownBy(() -> dataSource.bindTransactionConnection(stubConn))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(stubConn.isClosed())
                    .as("PersistenceConnection must be closed on the bindResource failure path")
                    .isTrue();
        } finally {
            TransactionSynchronizationManager.unbindResourceIfPossible(dataSource);
        }
    }
}

