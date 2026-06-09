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
    void commitAndRollback_inTx_doNotTouchRawConnection() throws SQLException {
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
        // Shared references for array/Properties args — verify(...) matches by reference, so the
        // call and the verification must use the same instance.
        int[] colIndexes = {1};
        String[] colNames = {"id"};
        Properties clientProps = new Properties();
        Object[] arrayElems = {1};
        Object[] structAttrs = {1};

        // Every call below is followed by a verify(...) so a regression in any single
        // delegation (not just a representative subset) fails the test, not merely a
        // "method threw" check.
        p.createStatement();                                          verify(raw).createStatement();
        p.prepareStatement("SELECT 1");                              verify(raw).prepareStatement("SELECT 1");
        p.prepareCall("{call f()}");                                 verify(raw).prepareCall("{call f()}");
        p.nativeSQL("SELECT 1");                                     verify(raw).nativeSQL("SELECT 1");
        p.getAutoCommit();                                           verify(raw).getAutoCommit();
        p.getMetaData();                                             verify(raw).getMetaData();
        p.setReadOnly(true);                                         verify(raw).setReadOnly(true);
        p.isReadOnly();                                              verify(raw).isReadOnly();
        p.setCatalog("c");                                           verify(raw).setCatalog("c");
        p.getCatalog();                                              verify(raw).getCatalog();
        p.getTransactionIsolation();                                 verify(raw).getTransactionIsolation();
        p.getWarnings();                                             verify(raw).getWarnings();
        p.clearWarnings();                                           verify(raw).clearWarnings();
        p.createStatement(ResultSet_TYPE, ResultSet_CONCUR);         verify(raw).createStatement(ResultSet_TYPE, ResultSet_CONCUR);
        p.prepareStatement("s", ResultSet_TYPE, ResultSet_CONCUR);   verify(raw).prepareStatement("s", ResultSet_TYPE, ResultSet_CONCUR);
        p.prepareCall("c", ResultSet_TYPE, ResultSet_CONCUR);        verify(raw).prepareCall("c", ResultSet_TYPE, ResultSet_CONCUR);
        p.getTypeMap();                                              verify(raw).getTypeMap();
        p.setTypeMap(Map.of());                                      verify(raw).setTypeMap(Map.of());
        p.setHoldability(1);                                         verify(raw).setHoldability(1);
        p.getHoldability();                                          verify(raw).getHoldability();
        p.setSavepoint();                                            verify(raw).setSavepoint();
        p.setSavepoint("sp");                                        verify(raw).setSavepoint("sp");
        p.releaseSavepoint(sp);                                      verify(raw).releaseSavepoint(sp);
        p.createStatement(ResultSet_TYPE, ResultSet_CONCUR, 1);      verify(raw).createStatement(ResultSet_TYPE, ResultSet_CONCUR, 1);
        p.prepareStatement("s", ResultSet_TYPE, ResultSet_CONCUR, 1); verify(raw).prepareStatement("s", ResultSet_TYPE, ResultSet_CONCUR, 1);
        p.prepareCall("c", ResultSet_TYPE, ResultSet_CONCUR, 1);     verify(raw).prepareCall("c", ResultSet_TYPE, ResultSet_CONCUR, 1);
        p.prepareStatement("s", java.sql.Statement.RETURN_GENERATED_KEYS); verify(raw).prepareStatement("s", java.sql.Statement.RETURN_GENERATED_KEYS);
        p.prepareStatement("s", colIndexes);                         verify(raw).prepareStatement("s", colIndexes);
        p.prepareStatement("s", colNames);                           verify(raw).prepareStatement("s", colNames);
        p.createClob();                                              verify(raw).createClob();
        p.createBlob();                                              verify(raw).createBlob();
        p.createNClob();                                             verify(raw).createNClob();
        p.createSQLXML();                                            verify(raw).createSQLXML();
        p.isValid(1);                                                verify(raw).isValid(1);
        p.setClientInfo("k", "v");                                   verify(raw).setClientInfo("k", "v");
        p.setClientInfo(clientProps);                                verify(raw).setClientInfo(clientProps);
        p.getClientInfo("k");                                        verify(raw).getClientInfo("k");
        p.getClientInfo();                                           verify(raw).getClientInfo();
        p.createArrayOf("int", arrayElems);                          verify(raw).createArrayOf("int", arrayElems);
        p.createStruct("t", structAttrs);                            verify(raw).createStruct("t", structAttrs);
        p.setSchema("s");                                            verify(raw).setSchema("s");
        p.getSchema();                                               verify(raw).getSchema();
        p.abort(executor);                                           verify(raw).abort(executor);
        p.setNetworkTimeout(executor, 1000);                         verify(raw).setNetworkTimeout(executor, 1000);
        p.getNetworkTimeout();                                       verify(raw).getNetworkTimeout();
    }

    private static final int ResultSet_TYPE = java.sql.ResultSet.TYPE_FORWARD_ONLY;
    private static final int ResultSet_CONCUR = java.sql.ResultSet.CONCUR_READ_ONLY;
}
