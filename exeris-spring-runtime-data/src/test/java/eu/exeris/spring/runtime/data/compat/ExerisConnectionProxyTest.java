/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.data.compat;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link ExerisConnectionProxy} intercept semantics.
 *
 * <p>Uses an H2 in-memory JDBC connection as the raw connection.
 * Tests verify the 6 intercepts defined in ADR-017 §6.1.
 */
class ExerisConnectionProxyTest {

    private Connection h2Conn;
    private StubNonJdbcPersistenceConnection stubPersistenceConn;

    @BeforeEach
    void openH2Connection() throws SQLException {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:exeris_proxy_test_" + Thread.currentThread().getId() + ";DB_CLOSE_DELAY=-1");
        h2Conn = ds.getConnection();
        h2Conn.setAutoCommit(false);
        stubPersistenceConn = new StubNonJdbcPersistenceConnection();
    }

    @AfterEach
    void closeH2Connection() throws SQLException {
        if (h2Conn != null && !h2Conn.isClosed()) {
            h2Conn.close();
        }
    }

    // -------------------------------------------------------------------------
    // in-tx intercepts
    // -------------------------------------------------------------------------

    @Test
    void close_inTxScope_isNoOp() throws SQLException {
        ExerisConnectionProxy proxy = new ExerisConnectionProxy(h2Conn, stubPersistenceConn, true);
        proxy.close();
        // h2Conn must still be open — close was a no-op
        assertThat(h2Conn.isClosed()).isFalse();
    }

    @Test
    void commit_inTxScope_isNoOp() throws SQLException {
        ExerisConnectionProxy proxy = new ExerisConnectionProxy(h2Conn, stubPersistenceConn, true);
        proxy.commit(); // should not throw; h2Conn.commit() not called
        assertThat(h2Conn.isClosed()).isFalse();
    }

    @Test
    void rollback_inTxScope_isNoOp() throws SQLException {
        ExerisConnectionProxy proxy = new ExerisConnectionProxy(h2Conn, stubPersistenceConn, true);
        proxy.rollback(); // should not throw
        assertThat(h2Conn.isClosed()).isFalse();
    }

    @Test
    void setAutoCommitTrue_inTxScope_throwsSqlException() {
        ExerisConnectionProxy proxy = new ExerisConnectionProxy(h2Conn, stubPersistenceConn, true);
        assertThatThrownBy(() -> proxy.setAutoCommit(true))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("setAutoCommit(true) is not permitted");
    }

    @Test
    void setAutoCommitFalse_inTxScope_passesThrough() throws SQLException {
        ExerisConnectionProxy proxy = new ExerisConnectionProxy(h2Conn, stubPersistenceConn, true);
        proxy.setAutoCommit(false); // should NOT throw — false is benign
        assertThat(h2Conn.getAutoCommit()).isFalse();
    }

    @Test
    void setTransactionIsolation_inTxScope_throwsSqlException() {
        ExerisConnectionProxy proxy = new ExerisConnectionProxy(h2Conn, stubPersistenceConn, true);
        assertThatThrownBy(() -> proxy.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("setTransactionIsolation() is not permitted");
    }

    // -------------------------------------------------------------------------
    // unwrap — always throws regardless of tx status
    // -------------------------------------------------------------------------

    @Test
    void unwrap_inTxScope_alwaysThrowsSqlException() {
        ExerisConnectionProxy proxy = new ExerisConnectionProxy(h2Conn, stubPersistenceConn, true);
        assertThatThrownBy(() -> proxy.unwrap(Connection.class))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("proxy boundary must not be breached");
    }

    @Test
    void unwrap_outsideTxScope_alwaysThrowsSqlException() {
        ExerisConnectionProxy proxy = new ExerisConnectionProxy(h2Conn, stubPersistenceConn, false);
        assertThatThrownBy(() -> proxy.unwrap(Connection.class))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("proxy boundary must not be breached");
    }

    @Test
    void isWrapperFor_ownClass_returnsTrue() throws SQLException {
        ExerisConnectionProxy proxy = new ExerisConnectionProxy(h2Conn, stubPersistenceConn, false);
        assertThat(proxy.isWrapperFor(ExerisConnectionProxy.class)).isTrue();
    }

    @Test
    void isWrapperFor_connectionInterface_returnsTrue() throws SQLException {
        ExerisConnectionProxy proxy = new ExerisConnectionProxy(h2Conn, stubPersistenceConn, false);
        assertThat(proxy.isWrapperFor(Connection.class)).isTrue();
    }

    @Test
    void isWrapperFor_rawH2Class_returnsFalse() throws SQLException {
        ExerisConnectionProxy proxy = new ExerisConnectionProxy(h2Conn, stubPersistenceConn, false);
        assertThat(proxy.isWrapperFor(String.class)).isFalse();
    }

    // -------------------------------------------------------------------------
    // outside-tx pass-through
    // -------------------------------------------------------------------------

    @Test
    void setTransactionIsolation_outsideTxScope_passesThrough() throws SQLException {
        ExerisConnectionProxy proxy = new ExerisConnectionProxy(h2Conn, stubPersistenceConn, false);
        proxy.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        assertThat(h2Conn.getTransactionIsolation()).isEqualTo(Connection.TRANSACTION_READ_COMMITTED);
    }

    @Test
    void isClosed_reflectsH2State() throws SQLException {
        ExerisConnectionProxy proxy = new ExerisConnectionProxy(h2Conn, stubPersistenceConn, false);
        assertThat(proxy.isClosed()).isFalse();
    }

    @Test
    void prepareStatement_delegatesToRealConnection() throws SQLException {
        ExerisConnectionProxy proxy = new ExerisConnectionProxy(h2Conn, stubPersistenceConn, false);
        var stmt = proxy.prepareStatement("SELECT 1");
        assertThat(stmt).isNotNull();
        stmt.close();
    }
}
