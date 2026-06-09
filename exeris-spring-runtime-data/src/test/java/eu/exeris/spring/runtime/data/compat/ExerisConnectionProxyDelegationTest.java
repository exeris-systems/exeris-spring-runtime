/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.data.compat;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Pass-through delegation + outside-transaction intercept coverage for
 * {@link ExerisConnectionProxy}.
 *
 * <p>Complements {@link ExerisConnectionProxyTest} (which exercises the in-transaction
 * intercepts against a real H2 connection). Here the raw connection is a Mockito mock so
 * every {@code java.sql.Connection} method delegates deterministically — including the
 * primitive-returning getters that a null-returning JDK proxy could not handle — and the
 * delegation can be asserted with {@code verify(...)}.
 */
class ExerisConnectionProxyDelegationTest {

    private final Connection raw = mock(Connection.class);
    private final StubJdbcPersistenceConnection persistenceConn = new StubJdbcPersistenceConnection();

    private ExerisConnectionProxy proxy(boolean txActive) {
        return new ExerisConnectionProxy(raw, persistenceConn, txActive);
    }

    // -------------------------------------------------------------------------
    // outside-transaction intercepts (close/commit/rollback delegate to the raw conn)
    // -------------------------------------------------------------------------

    @Test
    void close_outsideTx_closesPersistenceConnection() throws SQLException {
        proxy(false).close();
        assertThat(persistenceConn.isClosed())
                .as("outside a managed tx, close() must release the PersistenceConnection")
                .isTrue();
    }

    @Test
    void commit_outsideTx_delegatesToRawConnection() throws SQLException {
        proxy(false).commit();
        verify(raw).commit();
    }

    @Test
    void rollback_outsideTx_delegatesToRawConnection() throws SQLException {
        proxy(false).rollback();
        verify(raw).rollback();
    }

    @Test
    void rollbackSavepoint_outsideTx_delegatesToRawConnection() throws SQLException {
        Savepoint sp = mock(Savepoint.class);
        proxy(false).rollback(sp);
        verify(raw).rollback(sp);
    }

    @Test
    void setAutoCommitTrue_outsideTx_delegatesToRawConnection() throws SQLException {
        proxy(false).setAutoCommit(true);
        verify(raw).setAutoCommit(true);
    }

    @Test
    void commit_inTx_doesNotTouchRawConnection() throws SQLException {
        proxy(true).commit();
        proxy(true).rollback();
        verifyNoInteractions(raw);
    }

    @Test
    void isClosed_whenPersistenceConnectionClosed_returnsTrueWithoutQueryingRaw() throws SQLException {
        persistenceConn.close(); // isOpen() now false → short-circuits before touching rawConn
        assertThat(proxy(false).isClosed()).isTrue();
    }

    @Test
    void isClosed_whenBothOpen_returnsFalse() throws SQLException {
        when(raw.isClosed()).thenReturn(false);
        assertThat(proxy(false).isClosed()).isFalse();
    }

    // -------------------------------------------------------------------------
    // pass-through delegation — every remaining Connection method forwards to raw
    // -------------------------------------------------------------------------

    @Test
    void allPassThroughMethods_delegateToRawConnection() throws SQLException {
        ExerisConnectionProxy p = proxy(false);
        Executor executor = Runnable::run;
        Savepoint sp = mock(Savepoint.class);

        p.createStatement();
        p.prepareStatement("SELECT 1");
        p.prepareCall("{call f()}");
        p.nativeSQL("SELECT 1");
        p.getAutoCommit();
        p.getMetaData();
        p.setReadOnly(true);
        p.isReadOnly();
        p.setCatalog("c");
        p.getCatalog();
        p.getTransactionIsolation();
        p.getWarnings();
        p.clearWarnings();
        p.createStatement(ResultSet_TYPE, ResultSet_CONCUR);
        p.prepareStatement("s", ResultSet_TYPE, ResultSet_CONCUR);
        p.prepareCall("c", ResultSet_TYPE, ResultSet_CONCUR);
        p.getTypeMap();
        p.setTypeMap(Map.of());
        p.setHoldability(1);
        p.getHoldability();
        p.setSavepoint();
        p.setSavepoint("sp");
        p.releaseSavepoint(sp);
        p.createStatement(ResultSet_TYPE, ResultSet_CONCUR, 1);
        p.prepareStatement("s", ResultSet_TYPE, ResultSet_CONCUR, 1);
        p.prepareCall("c", ResultSet_TYPE, ResultSet_CONCUR, 1);
        p.prepareStatement("s", java.sql.Statement.RETURN_GENERATED_KEYS);
        p.prepareStatement("s", new int[] {1});
        p.prepareStatement("s", new String[] {"id"});
        p.createClob();
        p.createBlob();
        p.createNClob();
        p.createSQLXML();
        p.isValid(1);
        p.setClientInfo("k", "v");
        p.setClientInfo(new Properties());
        p.getClientInfo("k");
        p.getClientInfo();
        p.createArrayOf("int", new Object[] {1});
        p.createStruct("t", new Object[] {1});
        p.setSchema("s");
        p.getSchema();
        p.abort(executor);
        p.setNetworkTimeout(executor, 1000);
        p.getNetworkTimeout();

        // Spot-check a representative subset reached the raw connection.
        verify(raw).createStatement();
        verify(raw).getMetaData();
        verify(raw).setReadOnly(true);
        verify(raw).setSchema("s");
        verify(raw).abort(executor);
        verify(raw).getNetworkTimeout();
    }

    private static final int ResultSet_TYPE = java.sql.ResultSet.TYPE_FORWARD_ONLY;
    private static final int ResultSet_CONCUR = java.sql.ResultSet.CONCUR_READ_ONLY;
}
