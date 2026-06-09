/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.data.compat;

import eu.exeris.kernel.spi.persistence.PersistenceConnection;
import eu.exeris.kernel.spi.persistence.PersistenceStatement;
import eu.exeris.kernel.spi.persistence.QueryResult;
import eu.exeris.kernel.spi.persistence.TransactionIsolation;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.Optional;

/**
 * Stub PersistenceConnection that unwraps to a JDBC {@link Connection} (Community-tier shape).
 * Records whether {@link #close()} was called so leak-guard tests can assert the connection was
 * released on the failure path of {@link ExerisDataSource#bindTransactionConnection}.
 */
final class StubJdbcPersistenceConnection implements PersistenceConnection {

    // A no-behaviour JDBC Connection — only needs to be non-null for proxy construction.
    private final Connection rawJdbc = (Connection) Proxy.newProxyInstance(
            StubJdbcPersistenceConnection.class.getClassLoader(),
            new Class<?>[] { Connection.class },
            (p, method, methodArgs) -> null);
    private boolean closed = false;

    boolean isClosed() {
        return closed;
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> Optional<T> unwrap(Class<T> iface) {
        if (iface == Connection.class) {
            return Optional.of((T) rawJdbc);
        }
        return Optional.empty();
    }

    @Override public PersistenceStatement prepare(String sql) { throw new UnsupportedOperationException(); }
    @Override public QueryResult executeQuery(String sql) { throw new UnsupportedOperationException(); }
    @Override public long executeUpdate(String sql) { throw new UnsupportedOperationException(); }
    @Override public void beginTransaction() {}
    @Override public void beginTransaction(TransactionIsolation isolation, boolean readOnly) {}
    @Override public void commit() {}
    @Override public void rollback() {}
    @Override public boolean inTransaction() { return false; }
    @Override public boolean isOpen() { return !closed; }
    @Override public void close() { closed = true; }
}
