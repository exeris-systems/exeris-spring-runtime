/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.tx;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.persistence.PersistenceConnection;
import eu.exeris.kernel.spi.persistence.PersistenceEngine;
import eu.exeris.kernel.spi.persistence.QueryResult;
import eu.exeris.spring.boot.autoconfigure.ExerisRuntimeLifecycle;
import eu.exeris.spring.boot.autoconfigure.ExerisRuntimeProperties;
import eu.exeris.spring.boot.autoconfigure.ExerisSpringConfigProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runtime integration coverage for {@link ExerisPlatformTransactionManager} against a
 * <strong>real kernel {@code PersistenceEngine}</strong>, not a stub.
 *
 * <h2>Why this exists</h2>
 * <p>Every other test in this module drives {@code StubPersistenceEngine} /
 * {@code TrackingEngine} — doubles written in this repository. They prove the manager calls
 * the SPI in the expected order, but they cannot disagree with us: a stub records
 * {@code commit()} without committing anything, and reports that {@code REQUIRES_NEW}
 * opened a second connection without that connection being independent. Durability,
 * rollback and isolation were therefore asserted against our reading of the SPI rather than
 * against an engine the kernel actually produces.
 *
 * <p>That blind spot is not hypothetical. It is the same shape as the shutdown-drain defect
 * (kernel 0.10.2, see {@code ExerisWireLevelRuntimeIntegrationTest}) and as the compat
 * datasource defects found downstream rather than here — in each case the double modelled
 * the contract as written while the runtime behaved differently.
 *
 * <h2>What this proves that the stub suite cannot</h2>
 * <ul>
 *   <li>A committed write is durable — visible from a connection that did not participate
 *       in the transaction.</li>
 *   <li>A rolled-back write leaves nothing behind.</li>
 *   <li>{@code REQUIRES_NEW} is genuinely independent: the inner transaction commits and
 *       survives the outer one rolling back, which requires two real connections with real
 *       isolation between them.</li>
 *   <li>Connections are returned to the pool on both commit and rollback — a leak shows up
 *       as a rising {@code EngineStats.activeConnections}, which no stub tracks.</li>
 * </ul>
 *
 * <h2>Mode</h2>
 * <p>MIXED. The engine is obtained the way the production request path obtains it —
 * captured from the kernel boot scope by {@link ExerisRuntimeLifecycle} and re-bound into
 * {@link KernelProviders#PERSISTENCE_ENGINE} for the calling thread, mirroring
 * {@code KernelProviderBinder} in the web module. The transactional connection is reached
 * through {@link ExerisJdbcResourceCallback}, which is the compatibility-mode seam and the
 * only supported way to obtain it — {@code ExerisTransactionObject} is package-private and
 * the connection, per the manager's own Javadoc, "travels inside Spring's transaction
 * infrastructure only". Using the same seam {@code ExerisDataSource} uses keeps this test
 * on a supported API instead of reaching into internals.
 *
 * <h2>What this deliberately does not assert</h2>
 * <p>That {@code @Transactional} governs Level 1 Exeris-native repositories. It does not,
 * by design: per {@code phase-3-invariants.md} §6 those repositories take the
 * <em>engine</em> from {@link PersistenceEngineProvider} and drive their own
 * {@code TransactionalExecutor}, which owns its connection and its commit. Connection
 * sharing under {@code @Transactional} (§7) is defined only for the {@code ExerisDataSource}
 * path. A service method that annotates {@code @Transactional} around Level 1 repository
 * calls therefore does not roll those writes back — a composition rule currently stated
 * nowhere the two halves meet. That is a documentation gap worth closing, not a defect in
 * this manager, and it is out of scope here.
 */
class ExerisTransactionManagerRuntimeIntegrationTest {

    private static final AtomicLong DB_COUNTER = new AtomicLong();

    @Test
    void committedWriteIsDurable_visibleFromAConnectionOutsideTheTransaction() {
        withRealEngine((fixture) -> {
            TransactionTemplate template = new TransactionTemplate(fixture.txManager());

            template.executeWithoutResult(_ ->
                    fixture.currentConnection()
                            .executeUpdate("INSERT INTO tx_it (id, label) VALUES (1, 'committed')"));

            assertThat(countRows(fixture.engine())).isEqualTo(1);
            assertThat(readLabel(fixture.engine(), 1)).isEqualTo("committed");
        });
    }

    @Test
    void rolledBackWriteLeavesNothingBehind() {
        withRealEngine((fixture) -> {
            TransactionTemplate template = new TransactionTemplate(fixture.txManager());

            assertThatThrownBy(() -> template.executeWithoutResult(_ -> {
                fixture.currentConnection()
                        .executeUpdate("INSERT INTO tx_it (id, label) VALUES (1, 'doomed')");
                throw new IllegalStateException("force rollback");
            })).isInstanceOf(IllegalStateException.class).hasMessage("force rollback");

            assertThat(countRows(fixture.engine())).isZero();
        });
    }

    /**
     * The assertion the stub suite structurally cannot make: {@code REQUIRES_NEW} must
     * suspend the outer transaction and run on an independent connection, so the inner
     * commit survives the outer rollback. A stub can report "opened a second connection";
     * only a real engine can show that the second connection was genuinely isolated.
     */
    @Test
    void requiresNew_innerCommitSurvivesOuterRollback() {
        withRealEngine((fixture) -> {
            TransactionTemplate outer = new TransactionTemplate(fixture.txManager());
            DefaultTransactionDefinition requiresNew = new DefaultTransactionDefinition();
            requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            TransactionTemplate inner = new TransactionTemplate(fixture.txManager(), requiresNew);

            assertThatThrownBy(() -> outer.executeWithoutResult(_ -> {
                fixture.currentConnection()
                        .executeUpdate("INSERT INTO tx_it (id, label) VALUES (1, 'outer')");

                inner.executeWithoutResult(_ ->
                        fixture.currentConnection()
                                .executeUpdate("INSERT INTO tx_it (id, label) VALUES (2, 'inner')"));

                throw new IllegalStateException("force outer rollback");
            })).isInstanceOf(IllegalStateException.class);

            assertThat(fixture.openedConnections())
                    .as("REQUIRES_NEW must open a second connection rather than joining the outer one")
                    .hasSize(2);
            assertThat(countRows(fixture.engine())).isEqualTo(1);
            assertThat(readLabel(fixture.engine(), 2)).isEqualTo("inner");
        });
    }

    @Test
    void connectionsAreReturnedToThePool_onBothCommitAndRollback() {
        withRealEngine((fixture) -> {
            TransactionTemplate template = new TransactionTemplate(fixture.txManager());
            int baseline = fixture.engine().stats().activeConnections();

            // Non-vacuity guard: if activeConnections never moved, the balance assertion
            // below would hold no matter how badly connections leaked. Prove the metric
            // responds to an open transaction before trusting it to detect a leak.
            template.executeWithoutResult(_ ->
                    assertThat(fixture.engine().stats().activeConnections())
                            .as("activeConnections must rise while a transaction holds a connection, "
                                    + "otherwise the leak assertion below is meaningless")
                            .isGreaterThan(baseline));

            for (int i = 0; i < 10; i++) {
                int id = i;
                template.executeWithoutResult(_ ->
                        fixture.currentConnection().executeUpdate(
                                "INSERT INTO tx_it (id, label) VALUES (" + id + ", 'commit')"));
            }
            for (int i = 0; i < 10; i++) {
                assertThatThrownBy(() -> template.executeWithoutResult(_ -> {
                    fixture.currentConnection()
                            .executeUpdate("INSERT INTO tx_it (id, label) VALUES (999, 'rollback')");
                    throw new IllegalStateException("force rollback");
                })).isInstanceOf(IllegalStateException.class);
            }

            assertThat(fixture.engine().stats().activeConnections())
                    .as("20 transactions must not strand connections in the pool")
                    .isEqualTo(baseline);
            assertThat(countRows(fixture.engine())).isEqualTo(10);
        });
    }

    // ---------------------------------------------------------------------
    // Harness
    // ---------------------------------------------------------------------

    @FunctionalInterface
    private interface EngineTest {
        void run(Fixture fixture);
    }

    /**
     * Bundles the real engine, a manager wired to it, and the connections that manager has
     * opened — recorded through {@link ExerisJdbcResourceCallback}, the same seam
     * {@code ExerisDataSource} registers in production.
     */
    private static final class Fixture implements ExerisJdbcResourceCallback {

        private final PersistenceEngine engine;
        private final ExerisPlatformTransactionManager txManager;
        private final List<PersistenceConnection> opened = new ArrayList<>();

        private Fixture(PersistenceEngine engine) {
            this.engine = engine;
            this.txManager = new ExerisPlatformTransactionManager();
            this.txManager.setJdbcResourceCallback(this);
        }

        @Override
        public void onNewTransactionStarted(PersistenceConnection connection) {
            opened.add(connection);
        }

        PersistenceEngine engine() {
            return engine;
        }

        ExerisPlatformTransactionManager txManager() {
            return txManager;
        }

        /**
         * The connection of the most recently started transaction. Every write in this
         * class happens immediately after entering its transaction, so "most recent" is
         * unambiguously the innermost active one — including inside {@code REQUIRES_NEW}.
         */
        PersistenceConnection currentConnection() {
            if (opened.isEmpty()) {
                throw new AssertionError("no transactional connection bound");
            }
            return opened.getLast();
        }

        List<PersistenceConnection> openedConnections() {
            return List.copyOf(opened);
        }
    }

    /**
     * Boots a real kernel, binds its {@code PersistenceEngine} into the kernel
     * {@code ScopedValue} slot for the calling thread, and creates the test table. Each
     * invocation uses its own in-memory database so methods stay independent.
     */
    private static void withRealEngine(EngineTest body) {
        String db = "exeris_tx_runtime_it_" + DB_COUNTER.incrementAndGet();
        ExerisRuntimeLifecycle lifecycle = newLifecycle("jdbc:h2:mem:" + db + ";DB_CLOSE_DELAY=-1");

        lifecycle.start();
        try {
            PersistenceEngine engine = lifecycle.getPersistenceEngine().orElseThrow(
                    () -> new AssertionError(
                            "kernel bootstrap did not bind a PersistenceEngine — check that "
                                    + "exeris-kernel-community is on the test classpath"));

            try (PersistenceConnection setup = engine.openConnection()) {
                setup.executeUpdate("CREATE TABLE tx_it (id INT PRIMARY KEY, label VARCHAR(64))");
            }

            ScopedValue.where(KernelProviders.PERSISTENCE_ENGINE, engine)
                    .run(() -> body.run(new Fixture(engine)));
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
        // Ephemeral HTTP port: the kernel boot DAG initialises the HTTP subsystem
        // unconditionally and this module does not use it — keep it out of the way rather
        // than racing another test for a fixed port.
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

    private static long countRows(PersistenceEngine engine) {
        try (PersistenceConnection probe = engine.openConnection();
             QueryResult result = probe.executeQuery("SELECT COUNT(*) FROM tx_it")) {
            assertThat(result.next()).isTrue();
            return result.row().getLong(0);
        }
    }

    private static String readLabel(PersistenceEngine engine, int id) {
        try (PersistenceConnection probe = engine.openConnection();
             QueryResult result = probe.executeQuery("SELECT label FROM tx_it WHERE id = " + id)) {
            assertThat(result.next()).isTrue();
            return result.row().getString(0);
        }
    }
}
