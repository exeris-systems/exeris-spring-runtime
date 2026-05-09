/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.tx;

import eu.exeris.kernel.spi.persistence.PersistenceConnection;
import eu.exeris.kernel.spi.persistence.PersistenceStatement;
import eu.exeris.kernel.spi.persistence.QueryResult;
import eu.exeris.kernel.spi.persistence.TransactionIsolation;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link ExerisTransactionSynchronizationBridge}.
 */
class ExerisTransactionScaffoldTest {

    @Test
    void constructor_rejectsNullConnection() {
        assertThatThrownBy(() -> new ExerisTransactionSynchronizationBridge(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void afterCompletion_closesOpenConnection() {
        StubConnection conn = new StubConnection(true);
        ExerisTransactionSynchronizationBridge bridge = new ExerisTransactionSynchronizationBridge(conn);

        bridge.afterCompletion(TransactionSynchronization.STATUS_COMMITTED);

        assertThat(conn.closeCalled).isTrue();
    }

    @Test
    void afterCompletion_isNoOpWhenConnectionAlreadyClosed() {
        StubConnection conn = new StubConnection(false);
        ExerisTransactionSynchronizationBridge bridge = new ExerisTransactionSynchronizationBridge(conn);

        bridge.afterCompletion(TransactionSynchronization.STATUS_COMMITTED);

        assertThat(conn.closeCalled).isFalse();
    }

    @Test
    void afterCompletion_swallowsPoolReturnException() {
        PersistenceConnection boom = new StubConnection(true) {
            @Override
            public void close() {
                throw new RuntimeException("pool return failed");
            }
        };
        ExerisTransactionSynchronizationBridge bridge = new ExerisTransactionSynchronizationBridge(boom);

        // must not propagate the pool-return failure
        bridge.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
    }

    // =========================================================================
    // Minimal stub
    // =========================================================================

    private static class StubConnection implements PersistenceConnection {
        boolean closeCalled = false;
        private final boolean open;

        StubConnection(boolean open) {
            this.open = open;
        }

        @Override public PersistenceStatement prepare(String sql) { throw new UnsupportedOperationException(); }
        @Override public QueryResult executeQuery(String sql) { throw new UnsupportedOperationException(); }
        @Override public long executeUpdate(String sql) { throw new UnsupportedOperationException(); }
        @Override public void beginTransaction() {}
        @Override public void beginTransaction(TransactionIsolation isolation, boolean readOnly) {}
        @Override public void commit() {}
        @Override public void rollback() {}
        @Override public boolean inTransaction() { return false; }
        @Override public boolean isOpen() { return open; }
        @Override public void close() { closeCalled = true; }
    }
}

