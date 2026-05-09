/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.data.compat;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
}

