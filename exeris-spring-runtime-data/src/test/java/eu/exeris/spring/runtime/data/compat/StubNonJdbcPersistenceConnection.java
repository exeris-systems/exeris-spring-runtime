/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.data.compat;

import eu.exeris.kernel.spi.exceptions.persistence.PersistenceProviderException;
import eu.exeris.kernel.spi.persistence.PersistenceConnection;
import eu.exeris.kernel.spi.persistence.PersistenceStatement;
import eu.exeris.kernel.spi.persistence.QueryResult;
import eu.exeris.kernel.spi.persistence.TransactionIsolation;

import java.util.Optional;

/**
 * Stub PersistenceConnection that is NOT a JdbcPersistenceConnection —
 * simulates Enterprise engine behaviour for fail-fast guard tests.
 */
final class StubNonJdbcPersistenceConnection implements PersistenceConnection {

    /** Explicitly unwraps to nothing — the bridge must fail fast on a non-JDBC engine.
     *  Stated here (rather than relying on the SPI default) so the test intent is
     *  self-documenting and insulated from a future kernel SPI default change. */
    @Override public <T> Optional<T> unwrap(Class<T> iface) { return Optional.empty(); }

    @Override public PersistenceStatement prepare(String sql) { throw new UnsupportedOperationException(); }
    @Override public QueryResult executeQuery(String sql) { throw new UnsupportedOperationException(); }
    @Override public long executeUpdate(String sql) { throw new UnsupportedOperationException(); }
    @Override public void beginTransaction() {}
    @Override public void beginTransaction(TransactionIsolation isolation, boolean readOnly) {}
    @Override public void commit() {}
    @Override public void rollback() {}
    @Override public boolean inTransaction() { return false; }
    @Override public boolean isOpen() { return true; }
    @Override public void close() {}
}
