/*
 * Copyright (C) 2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.spring.runtime.data.compat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.persistence.PersistenceConnection;
import eu.exeris.kernel.spi.persistence.PersistenceEngine;
import eu.exeris.kernel.spi.persistence.QueryResult;
import eu.exeris.spring.boot.autoconfigure.ExerisRuntimeLifecycle;
import eu.exeris.spring.boot.autoconfigure.ExerisRuntimeProperties;
import eu.exeris.spring.boot.autoconfigure.ExerisSpringConfigProvider;
import eu.exeris.spring.runtime.tx.ExerisPlatformTransactionManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@link ExerisDataSource} under Spring's JDBC support — {@link JdbcTemplate}, which resolves its
 * connection through {@code DataSourceUtils} — against a live kernel {@code PersistenceEngine}
 * on H2, off the request path.
 *
 * <p>The supported manager is the Exeris transaction manager, wired through
 * {@code ExerisJdbcResourceCallback} as {@code ExerisDataAutoConfiguration} wires it. Spring's
 * {@link JdbcTransactionManager} appears only in a regression guard; see that test.
 *
 * <h2>Mode</h2>
 * <p>COMPATIBILITY_MODE — the JDBC bridge of ADR-017 over kernel-owned persistence.
 */
class ExerisDataSourceTransactionIntegrationTest {

    private static final AtomicLong DB_COUNTER = new AtomicLong();

    @AfterEach
    void noThreadStateLeaks() {
        assertThat(TransactionSynchronizationManager.getResourceMap()).isEmpty();
        assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
    }

    @Test
    void jdbcTemplate_insideAnExerisTransaction_runsOnTheTransactionConnection() {
        withRealEngine(fixture -> {
            TransactionTemplate tx = fixture.exerisTemplate(TransactionDefinition.PROPAGATION_REQUIRED);

            assertThatThrownBy(() -> tx.executeWithoutResult(_ -> {
                fixture.jdbc().update("INSERT INTO ds_tx (id, label) VALUES (1, 'jdbc-template')");
                assertThat(countThroughGetConnection(fixture.dataSource()))
                        .as("getConnection() inside the transaction sees the uncommitted JdbcTemplate write, "
                                + "so both run on the one transaction connection")
                        .isEqualTo(1);
                throw new IllegalStateException("force rollback");
            })).isInstanceOf(IllegalStateException.class).hasMessage("force rollback");

            assertThat(countRows(fixture.engine())).isZero();

            tx.executeWithoutResult(_ ->
                    fixture.jdbc().update("INSERT INTO ds_tx (id, label) VALUES (2, 'committed')"));
            assertThat(countRows(fixture.engine())).isEqualTo(1);
        });
    }

    @Test
    void jdbcTemplate_inAJoinedTransaction_rollsBackWithTheOuterTransaction() {
        withRealEngine(fixture -> {
            TransactionTemplate outer = fixture.exerisTemplate(TransactionDefinition.PROPAGATION_REQUIRED);
            TransactionTemplate inner = fixture.exerisTemplate(TransactionDefinition.PROPAGATION_REQUIRED);

            assertThatThrownBy(() -> outer.executeWithoutResult(_ -> {
                fixture.jdbc().update("INSERT INTO ds_tx (id, label) VALUES (1, 'outer')");
                inner.executeWithoutResult(_ ->
                        fixture.jdbc().update("INSERT INTO ds_tx (id, label) VALUES (2, 'inner')"));
                throw new IllegalStateException("force outer rollback");
            })).isInstanceOf(IllegalStateException.class);

            assertThat(countRows(fixture.engine())).isZero();
        });
    }

    @Test
    void jdbcTemplate_inRequiresNew_commitsIndependently_andTheOuterResumesOnItsOwnConnection() {
        withRealEngine(fixture -> {
            TransactionTemplate outer = fixture.exerisTemplate(TransactionDefinition.PROPAGATION_REQUIRED);
            TransactionTemplate inner = fixture.exerisTemplate(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

            assertThatThrownBy(() -> outer.executeWithoutResult(_ -> {
                fixture.jdbc().update("INSERT INTO ds_tx (id, label) VALUES (1, 'outer')");
                inner.executeWithoutResult(_ -> {
                    assertThat(countThroughGetConnection(fixture.dataSource()))
                            .as("the inner transaction runs on its own connection and cannot see the outer write")
                            .isZero();
                    fixture.jdbc().update("INSERT INTO ds_tx (id, label) VALUES (2, 'inner')");
                });
                assertThat(countThroughGetConnection(fixture.dataSource()))
                        .as("after resume the outer connection sees its own write and the inner commit")
                        .isEqualTo(2);
                throw new IllegalStateException("force outer rollback");
            })).isInstanceOf(IllegalStateException.class);

            assertThat(countRows(fixture.engine())).isEqualTo(1);
            assertThat(readLabel(fixture.engine(), 2)).isEqualTo("inner");
        });
    }

    /**
     * Regression guard only: {@code getConnection()} must not cast a {@code ConnectionHolder}
     * another manager bound under the data-source key. {@link JdbcTransactionManager} over
     * {@link ExerisDataSource} is <strong>not a supported pairing</strong> — it demarcates raw
     * JDBC transactions on an Exeris-owned connection, bypassing
     * {@code PersistenceConnection.beginTransaction} / {@code commit}, a split ownership ADR-017
     * rejects. Passing here does not make it supported.
     */
    @Test
    void jdbcTransactionManager_overExerisDataSource_getConnectionDoesNotFail() {
        withRealEngine(fixture -> {
            TransactionTemplate tx = new TransactionTemplate(new JdbcTransactionManager(fixture.dataSource()));

            tx.executeWithoutResult(_ -> {
                fixture.jdbc().update("INSERT INTO ds_tx (id, label) VALUES (1, 'jdbc-tm')");
                try (Connection direct = fixture.dataSource().getConnection()) {
                    assertThat(direct.isClosed()).isFalse();
                } catch (SQLException ex) {
                    throw new AssertionError(ex);
                }
            });

            assertThat(countRows(fixture.engine())).isEqualTo(1);
        });
    }

    // ---------------------------------------------------------------------
    // Harness
    // ---------------------------------------------------------------------

    @FunctionalInterface
    private interface EngineTest {
        void run(Fixture fixture);
    }

    private record Fixture(PersistenceEngine engine,
                           ExerisDataSource dataSource,
                           PlatformTransactionManager exerisTxManager,
                           JdbcTemplate jdbc) {

        static Fixture create(PersistenceEngine engine) {
            ExerisDataSource dataSource = new ExerisDataSource();
            ExerisPlatformTransactionManager txManager = new ExerisPlatformTransactionManager();
            txManager.setJdbcResourceCallback(dataSource::bindTransactionConnection);
            return new Fixture(engine, dataSource, txManager, new JdbcTemplate(dataSource));
        }

        TransactionTemplate exerisTemplate(int propagation) {
            DefaultTransactionDefinition definition = new DefaultTransactionDefinition();
            definition.setPropagationBehavior(propagation);
            return new TransactionTemplate(exerisTxManager, definition);
        }
    }

    private static void withRealEngine(EngineTest body) {
        String db = "exeris_ds_tx_it_" + DB_COUNTER.incrementAndGet();
        ExerisRuntimeLifecycle lifecycle = newLifecycle("jdbc:h2:mem:" + db + ";DB_CLOSE_DELAY=-1");

        lifecycle.start();
        try {
            PersistenceEngine engine = lifecycle.getPersistenceEngine().orElseThrow(
                    () -> new AssertionError("kernel bootstrap did not bind a PersistenceEngine"));

            try (PersistenceConnection setup = engine.openConnection()) {
                setup.executeUpdate("CREATE TABLE ds_tx (id INT PRIMARY KEY, label VARCHAR(64))");
            }

            ScopedValue.where(KernelProviders.PERSISTENCE_ENGINE, engine)
                    .run(() -> body.run(Fixture.create(engine)));
        } finally {
            lifecycle.stop();
        }
    }

    private static ExerisRuntimeLifecycle newLifecycle(String jdbcUrl) {
        ExerisRuntimeProperties properties = new ExerisRuntimeProperties(
                true,
                false,
                new ExerisRuntimeProperties.WebProperties(ExerisRuntimeProperties.Mode.PURE),
                new ExerisRuntimeProperties.LifecycleProperties(30),
                new ExerisRuntimeProperties.ShutdownProperties(true, 30)
        );
        MockEnvironment env = new MockEnvironment()
                .withProperty("exeris.runtime.network.port", "0")
                .withProperty("exeris.runtime.persistence.jdbc-url", jdbcUrl)
                .withProperty("exeris.runtime.persistence.username", "sa")
                .withProperty("exeris.runtime.persistence.password", "")
                .withProperty("exeris.runtime.persistence.run-migrations", "false");
        return new ExerisRuntimeLifecycle(
                properties,
                new ExerisSpringConfigProvider(env),
                Optional.empty()
        );
    }

    /** Counts rows through {@code getConnection()}, closing the connection as an application would. */
    private static long countThroughGetConnection(ExerisDataSource dataSource) {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM ds_tx")) {
            assertThat(rows.next()).isTrue();
            return rows.getLong(1);
        } catch (SQLException ex) {
            throw new AssertionError(ex);
        }
    }

    private static long countRows(PersistenceEngine engine) {
        try (PersistenceConnection probe = engine.openConnection();
             QueryResult result = probe.executeQuery("SELECT COUNT(*) FROM ds_tx")) {
            assertThat(result.next()).isTrue();
            return result.row().getLong(0);
        }
    }

    private static String readLabel(PersistenceEngine engine, int id) {
        try (PersistenceConnection probe = engine.openConnection();
             QueryResult result = probe.executeQuery("SELECT label FROM ds_tx WHERE id = " + id)) {
            assertThat(result.next()).isTrue();
            return result.row().getString(0);
        }
    }
}
