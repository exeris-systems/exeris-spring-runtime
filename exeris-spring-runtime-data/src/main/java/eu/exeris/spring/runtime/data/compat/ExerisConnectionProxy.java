/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.data.compat;

import eu.exeris.kernel.spi.persistence.PersistenceConnection;

import java.sql.*;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.Executor;

/**
 * Pass-through {@link Connection} proxy for the Exeris JDBC compatibility bridge.
 *
 * <h2>Intercepts (ADR-017 §6.1)</h2>
 * <ul>
 *   <li>{@link #close()} — no-op in managed tx scope; delegates to {@code PersistenceConnection.close()} outside</li>
 *   <li>{@link #commit()} — no-op in managed tx scope</li>
 *   <li>{@link #rollback()} / {@link #rollback(Savepoint)} — no-op in managed tx scope</li>
 *   <li>{@link #setAutoCommit(boolean) setAutoCommit(true)} — throws in managed tx scope</li>
 *   <li>{@link #setTransactionIsolation(int)} — throws in managed tx scope</li>
 *   <li>{@link #unwrap(Class)} — always throws; proxy boundary must not be breached</li>
 * </ul>
 *
 * <h2>rawJdbcConnection() approved caller note</h2>
 * <p>Every call to {@code JdbcPersistenceConnection.rawJdbcConnection()} in this class
 * is an approved integration bridge usage per the updated Javadoc contract.
 * See ADR-017 §6.4.
 *
 * @since 0.1.0
 */
final class ExerisConnectionProxy implements Connection {

    private final Connection rawConn;
    private final PersistenceConnection persistenceConn;
    private final boolean transactionActive;

    ExerisConnectionProxy(Connection rawConn,
                          PersistenceConnection persistenceConn,
                          boolean transactionActive) {
        this.rawConn          = Objects.requireNonNull(rawConn, "rawConn must not be null");
        this.persistenceConn  = Objects.requireNonNull(persistenceConn, "persistenceConn must not be null");
        this.transactionActive = transactionActive;
    }

    // =========================================================================
    // Intercepted methods
    // =========================================================================

    @Override
    public void close() throws SQLException {
        if (!transactionActive) {
            persistenceConn.close();
        }
        // No-op in managed tx — lifecycle owned by ExerisPlatformTransactionManager
    }

    @Override
    public void commit() throws SQLException {
        if (!transactionActive) {
            rawConn.commit();
        }
        // No-op in managed tx
    }

    @Override
    public void rollback() throws SQLException {
        if (!transactionActive) {
            rawConn.rollback();
        }
        // No-op in managed tx
    }

    @Override
    public void rollback(Savepoint savepoint) throws SQLException {
        if (!transactionActive) {
            rawConn.rollback(savepoint);
        }
        // No-op in managed tx
    }

    @Override
    public void setAutoCommit(boolean autoCommit) throws SQLException {
        if (transactionActive && autoCommit) {
            throw new SQLException(
                    "setAutoCommit(true) is not permitted on an Exeris-managed connection " +
                    "within an active transaction — would trigger an implicit PostgreSQL COMMIT " +
                    "and corrupt JdbcPersistenceConnection internal state. See ADR-017 §6.1.");
        }
        rawConn.setAutoCommit(autoCommit);
    }

    @Override
    public void setTransactionIsolation(int level) throws SQLException {
        if (transactionActive) {
            throw new SQLException(
                    "setTransactionIsolation() is not permitted after a transaction has started " +
                    "on an Exeris-managed connection — would corrupt JdbcPersistenceConnection " +
                    "baseline restore logic. See ADR-017 §6.1.");
        }
        rawConn.setTransactionIsolation(level);
    }

    @Override
    public boolean isClosed() throws SQLException {
        return !persistenceConn.isOpen() || rawConn.isClosed();
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        throw new SQLException(
                "ExerisConnectionProxy does not expose the underlying JDBC connection via unwrap(). " +
                "The proxy boundary must not be breached. Use ExerisConnectionProxy directly. " +
                "See ADR-017 §6.1.");
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return iface.isInstance(this);
    }

    // =========================================================================
    // Pass-through delegation
    // =========================================================================
    // All remaining java.sql.Connection methods delegate directly to rawConn.
    // rawJdbcConnection() approved caller: integration bridge. See ADR-017 §6.4.

    @Override
    public Statement createStatement() throws SQLException { return rawConn.createStatement(); }

    @Override
    public PreparedStatement prepareStatement(String sql) throws SQLException { return rawConn.prepareStatement(sql); }

    @Override
    public CallableStatement prepareCall(String sql) throws SQLException { return rawConn.prepareCall(sql); }

    @Override
    public String nativeSQL(String sql) throws SQLException { return rawConn.nativeSQL(sql); }

    @Override
    public boolean getAutoCommit() throws SQLException { return rawConn.getAutoCommit(); }

    @Override
    public DatabaseMetaData getMetaData() throws SQLException { return rawConn.getMetaData(); }

    @Override
    public void setReadOnly(boolean readOnly) throws SQLException { rawConn.setReadOnly(readOnly); }

    @Override
    public boolean isReadOnly() throws SQLException { return rawConn.isReadOnly(); }

    @Override
    public void setCatalog(String catalog) throws SQLException { rawConn.setCatalog(catalog); }

    @Override
    public String getCatalog() throws SQLException { return rawConn.getCatalog(); }

    @Override
    public int getTransactionIsolation() throws SQLException { return rawConn.getTransactionIsolation(); }

    @Override
    public SQLWarning getWarnings() throws SQLException { return rawConn.getWarnings(); }

    @Override
    public void clearWarnings() throws SQLException { rawConn.clearWarnings(); }

    @Override
    public Statement createStatement(int resultSetType, int resultSetConcurrency) throws SQLException {
        return rawConn.createStatement(resultSetType, resultSetConcurrency);
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency) throws SQLException {
        return rawConn.prepareStatement(sql, resultSetType, resultSetConcurrency);
    }

    @Override
    public CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency) throws SQLException {
        return rawConn.prepareCall(sql, resultSetType, resultSetConcurrency);
    }

    @Override
    public Map<String, Class<?>> getTypeMap() throws SQLException { return rawConn.getTypeMap(); }

    @Override
    public void setTypeMap(Map<String, Class<?>> map) throws SQLException { rawConn.setTypeMap(map); }

    @Override
    public void setHoldability(int holdability) throws SQLException { rawConn.setHoldability(holdability); }

    @Override
    public int getHoldability() throws SQLException { return rawConn.getHoldability(); }

    @Override
    public Savepoint setSavepoint() throws SQLException { return rawConn.setSavepoint(); }

    @Override
    public Savepoint setSavepoint(String name) throws SQLException { return rawConn.setSavepoint(name); }

    @Override
    public void releaseSavepoint(Savepoint savepoint) throws SQLException { rawConn.releaseSavepoint(savepoint); }

    @Override
    public Statement createStatement(int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException {
        return rawConn.createStatement(resultSetType, resultSetConcurrency, resultSetHoldability);
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException {
        return rawConn.prepareStatement(sql, resultSetType, resultSetConcurrency, resultSetHoldability);
    }

    @Override
    public CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException {
        return rawConn.prepareCall(sql, resultSetType, resultSetConcurrency, resultSetHoldability);
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int autoGeneratedKeys) throws SQLException {
        return rawConn.prepareStatement(sql, autoGeneratedKeys);
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int[] columnIndexes) throws SQLException {
        return rawConn.prepareStatement(sql, columnIndexes);
    }

    @Override
    public PreparedStatement prepareStatement(String sql, String[] columnNames) throws SQLException {
        return rawConn.prepareStatement(sql, columnNames);
    }

    @Override
    public Clob createClob() throws SQLException { return rawConn.createClob(); }

    @Override
    public Blob createBlob() throws SQLException { return rawConn.createBlob(); }

    @Override
    public NClob createNClob() throws SQLException { return rawConn.createNClob(); }

    @Override
    public SQLXML createSQLXML() throws SQLException { return rawConn.createSQLXML(); }

    @Override
    public boolean isValid(int timeout) throws SQLException { return rawConn.isValid(timeout); }

    @Override
    public void setClientInfo(String name, String value) throws SQLClientInfoException { rawConn.setClientInfo(name, value); }

    @Override
    public void setClientInfo(Properties properties) throws SQLClientInfoException { rawConn.setClientInfo(properties); }

    @Override
    public String getClientInfo(String name) throws SQLException { return rawConn.getClientInfo(name); }

    @Override
    public Properties getClientInfo() throws SQLException { return rawConn.getClientInfo(); }

    @Override
    public Array createArrayOf(String typeName, Object[] elements) throws SQLException { return rawConn.createArrayOf(typeName, elements); }

    @Override
    public Struct createStruct(String typeName, Object[] attributes) throws SQLException { return rawConn.createStruct(typeName, attributes); }

    @Override
    public void setSchema(String schema) throws SQLException { rawConn.setSchema(schema); }

    @Override
    public String getSchema() throws SQLException { return rawConn.getSchema(); }

    @Override
    public void abort(Executor executor) throws SQLException { rawConn.abort(executor); }

    @Override
    public void setNetworkTimeout(Executor executor, int milliseconds) throws SQLException { rawConn.setNetworkTimeout(executor, milliseconds); }

    @Override
    public int getNetworkTimeout() throws SQLException { return rawConn.getNetworkTimeout(); }
}
