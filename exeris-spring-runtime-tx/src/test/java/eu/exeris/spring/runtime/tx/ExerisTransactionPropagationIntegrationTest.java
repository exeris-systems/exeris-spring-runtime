/*
 * Copyright (C) 2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.spring.runtime.tx;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import eu.exeris.kernel.community.persistence.PersistenceSessionBox;
import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.persistence.PersistenceConnection;
import eu.exeris.kernel.spi.persistence.PersistenceEngine;
import eu.exeris.kernel.spi.persistence.QueryResult;
import eu.exeris.kernel.spi.persistence.TransactionIsolation;
import eu.exeris.spring.boot.autoconfigure.ExerisRuntimeLifecycle;
import eu.exeris.spring.boot.autoconfigure.ExerisRuntimeProperties;
import eu.exeris.spring.boot.autoconfigure.ExerisSpringConfigProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Propagation behaviour of {@link ExerisPlatformTransactionManager} when a transaction is
 * already active, against a live kernel {@code PersistenceEngine} on H2.
 *
 * <p>Two thread shapes are covered, because the kernel answers {@code openConnection()}
 * differently on each:
 * <ul>
 *   <li><b>Off the request path</b> — every {@code openConnection()} takes its own pooled
 *       connection, so a transaction can be suspended and a second one started beside it.</li>
 *   <li><b>On a kernel request</b> — the kernel binds a {@link PersistenceSessionBox} for the
 *       request and {@code openConnection()} hands back the one connection that request holds.
 *       The harness binds the box exactly as the kernel's request dispatcher does. There is no
 *       second connection to suspend onto, so {@code REQUIRES_NEW} and {@code NOT_SUPPORTED}
 *       must refuse early rather than run inside the outer transaction.</li>
 * </ul>
 *
 * <h2>Mode</h2>
 * <p>MIXED — exercises the Compatibility Mode transaction bridge against kernel-owned
 * persistence.
 */
class ExerisTransactionPropagationIntegrationTest {

    private static final AtomicLong DB_COUNTER = new AtomicLong();

    @AfterEach
    void noThreadStateLeaks() {
        assertThat(TransactionSynchronizationManager.getResourceMap())
                .as("no transaction resource may stay bound to the thread after a test")
                .isEmpty();
        assertThat(TransactionSynchronizationManager.isSynchronizationActive())
                .as("transaction synchronization must not stay active after a test")
                .isFalse();
    }

    // ---------------------------------------------------------------------
    // Off the request path
    // ---------------------------------------------------------------------

    @Test
    void required_insideTransaction_joinsTheOuterTransaction() {
        withRealEngine(false, fixture -> {
            TransactionTemplate outer = fixture.template(TransactionDefinition.PROPAGATION_REQUIRED);
            TransactionTemplate inner = fixture.template(TransactionDefinition.PROPAGATION_REQUIRED);

            assertThatThrownBy(() -> outer.executeWithoutResult(_ -> {
                fixture.insert(1, "outer");
                inner.executeWithoutResult(status -> {
                    assertThat(status.isNewTransaction()).isFalse();
                    fixture.insert(2, "inner");
                });
                throw new IllegalStateException("force outer rollback");
            })).isInstanceOf(IllegalStateException.class).hasMessage("force outer rollback");

            assertThat(fixture.openedConnections()).hasSize(1);
            assertThat(countRows(fixture.engine()))
                    .as("the joined inner write belongs to the outer transaction and rolls back with it")
                    .isZero();
        });
    }

    @Test
    void required_innerRollbackOnly_rollsBackTheOuterTransactionAtCommit() {
        withRealEngine(false, fixture -> {
            TransactionTemplate outer = fixture.template(TransactionDefinition.PROPAGATION_REQUIRED);
            TransactionTemplate inner = fixture.template(TransactionDefinition.PROPAGATION_REQUIRED);

            assertThatThrownBy(() -> outer.executeWithoutResult(_ -> {
                fixture.insert(1, "outer");
                inner.executeWithoutResult(status -> {
                    fixture.insert(2, "inner");
                    status.setRollbackOnly();
                });
            })).isInstanceOf(UnexpectedRollbackException.class);

            assertThat(countRows(fixture.engine())).isZero();
        });
    }

    @Test
    void mandatory_insideTransaction_joins() {
        withRealEngine(false, fixture -> {
            TransactionTemplate outer = fixture.template(TransactionDefinition.PROPAGATION_REQUIRED);
            TransactionTemplate inner = fixture.template(TransactionDefinition.PROPAGATION_MANDATORY);

            outer.executeWithoutResult(_ -> inner.executeWithoutResult(status -> {
                assertThat(status.isNewTransaction()).isFalse();
                fixture.insert(1, "mandatory");
            }));

            assertThat(fixture.openedConnections()).hasSize(1);
            assertThat(readLabel(fixture.engine(), 1)).isEqualTo("mandatory");
        });
    }

    @Test
    void mandatory_withoutTransaction_throws() {
        withRealEngine(false, fixture -> {
            TransactionTemplate mandatory = fixture.template(TransactionDefinition.PROPAGATION_MANDATORY);

            assertThatThrownBy(() -> mandatory.executeWithoutResult(_ -> { }))
                    .isInstanceOf(IllegalTransactionStateException.class);
            assertThat(fixture.openedConnections()).isEmpty();
        });
    }

    @Test
    void supports_insideTransaction_joins() {
        withRealEngine(false, fixture -> {
            TransactionTemplate outer = fixture.template(TransactionDefinition.PROPAGATION_REQUIRED);
            TransactionTemplate inner = fixture.template(TransactionDefinition.PROPAGATION_SUPPORTS);

            assertThatThrownBy(() -> outer.executeWithoutResult(_ -> {
                inner.executeWithoutResult(status -> {
                    assertThat(status.isNewTransaction()).isFalse();
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                    fixture.insert(1, "supports");
                });
                throw new IllegalStateException("force outer rollback");
            })).isInstanceOf(IllegalStateException.class);

            assertThat(countRows(fixture.engine())).isZero();
        });
    }

    @Test
    void never_insideTransaction_throws_andTheOuterTransactionStillCommits() {
        withRealEngine(false, fixture -> {
            TransactionTemplate outer = fixture.template(TransactionDefinition.PROPAGATION_REQUIRED);
            TransactionTemplate inner = fixture.template(TransactionDefinition.PROPAGATION_NEVER);

            outer.executeWithoutResult(_ -> {
                fixture.insert(1, "outer");
                assertThatThrownBy(() -> inner.executeWithoutResult(_ -> fixture.insert(2, "never")))
                        .isInstanceOf(IllegalTransactionStateException.class);
            });

            assertThat(countRows(fixture.engine())).isEqualTo(1);
            assertThat(readLabel(fixture.engine(), 1)).isEqualTo("outer");
        });
    }

    @Test
    void notSupported_offTheRequestPath_suspendsTheOuterTransaction() {
        withRealEngine(false, fixture -> {
            TransactionTemplate outer = fixture.template(TransactionDefinition.PROPAGATION_REQUIRED);
            TransactionTemplate inner = fixture.template(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);

            assertThatThrownBy(() -> outer.executeWithoutResult(_ -> {
                fixture.insert(1, "outer");
                inner.executeWithoutResult(_ -> {
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                    try (PersistenceConnection autoCommit = fixture.engine().openConnection()) {
                        assertThat(autoCommit.inTransaction()).isFalse();
                        autoCommit.executeUpdate("INSERT INTO tx_prop (id, label) VALUES (2, 'outside')");
                    }
                });
                fixture.insert(3, "outer-after-resume");
                throw new IllegalStateException("force outer rollback");
            })).isInstanceOf(IllegalStateException.class);

            assertThat(countRows(fixture.engine()))
                    .as("only the non-transactional write survives the outer rollback")
                    .isEqualTo(1);
            assertThat(readLabel(fixture.engine(), 2)).isEqualTo("outside");
        });
    }

    @Test
    void requiresNew_offTheRequestPath_suspendsAndResumesTheOuterTransaction() {
        withRealEngine(false, fixture -> {
            TransactionTemplate outer = fixture.template(TransactionDefinition.PROPAGATION_REQUIRED);
            TransactionTemplate inner = fixture.template(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

            outer.executeWithoutResult(_ -> {
                fixture.insert(1, "outer");
                PersistenceConnection outerConnection = fixture.currentConnection();
                inner.executeWithoutResult(status -> {
                    assertThat(status.isNewTransaction()).isTrue();
                    assertThat(fixture.currentConnection()).isNotSameAs(outerConnection);
                    fixture.insert(2, "inner");
                });
                assertThat(fixture.currentConnection()).isSameAs(outerConnection);
                fixture.insert(3, "outer-after-resume");
            });

            assertThat(fixture.openedConnections()).hasSize(2);
            assertThat(countRows(fixture.engine())).isEqualTo(3);
        });
    }

    @Test
    void nested_insideTransaction_throwsUnsupportedOperation() {
        withRealEngine(false, fixture -> {
            TransactionTemplate outer = fixture.template(TransactionDefinition.PROPAGATION_REQUIRED);
            TransactionTemplate inner = fixture.template(TransactionDefinition.PROPAGATION_NESTED);

            outer.executeWithoutResult(_ -> {
                fixture.insert(1, "outer");
                assertThatThrownBy(() -> inner.executeWithoutResult(_ -> { }))
                        .isInstanceOf(UnsupportedOperationException.class)
                        .hasMessageContaining("PROPAGATION_NESTED");
            });

            assertThat(countRows(fixture.engine())).isEqualTo(1);
        });
    }

    // ---------------------------------------------------------------------
    // On a kernel request: one request, one connection
    // ---------------------------------------------------------------------

    @Test
    void required_onARequest_joinsTheRequestTransaction() {
        withRealEngine(true, fixture -> {
            TransactionTemplate outer = fixture.template(TransactionDefinition.PROPAGATION_REQUIRED);
            TransactionTemplate inner = fixture.template(TransactionDefinition.PROPAGATION_REQUIRED);

            outer.executeWithoutResult(_ -> {
                fixture.insert(1, "outer");
                inner.executeWithoutResult(status -> {
                    assertThat(status.isNewTransaction()).isFalse();
                    fixture.insert(2, "inner");
                });
            });

            assertThat(countRows(fixture.engine())).isEqualTo(2);
        });
    }

    @Test
    void requiresNew_onARequest_failsEarly_andTheOuterTransactionStillCommits() {
        withRealEngine(true, fixture -> {
            TransactionTemplate outer = fixture.template(TransactionDefinition.PROPAGATION_REQUIRED);
            TransactionTemplate inner = fixture.template(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

            outer.executeWithoutResult(_ -> {
                fixture.insert(1, "outer");
                assertThatThrownBy(() -> inner.executeWithoutResult(_ -> fixture.insert(2, "inner")))
                        .isInstanceOf(IllegalTransactionStateException.class)
                        .hasMessageContaining("one connection");
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                fixture.insert(3, "outer-after-refusal");
            });

            assertThat(fixture.openedConnections()).hasSize(1);
            assertThat(countRows(fixture.engine())).isEqualTo(2);
            assertThat(readLabel(fixture.engine(), 3)).isEqualTo("outer-after-refusal");
        });
    }

    @Test
    void notSupported_onARequest_failsEarly_insteadOfRunningInTheOuterTransaction() {
        withRealEngine(true, fixture -> {
            TransactionTemplate outer = fixture.template(TransactionDefinition.PROPAGATION_REQUIRED);
            TransactionTemplate inner = fixture.template(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);

            outer.executeWithoutResult(_ -> {
                fixture.insert(1, "outer");
                assertThatThrownBy(() -> inner.executeWithoutResult(_ -> fixture.insert(2, "inner")))
                        .isInstanceOf(IllegalTransactionStateException.class)
                        .hasMessageContaining("one connection");
            });

            assertThat(countRows(fixture.engine())).isEqualTo(1);
        });
    }

    // ---------------------------------------------------------------------
    // From a completion callback: the transaction is committed but still bound
    // ---------------------------------------------------------------------

    @Test
    void requiresNew_fromAfterCommit_offTheRequestPath_runsInANewTransaction() {
        assertRequiresNewFromAfterCommitRunsInANewTransaction(false);
    }

    @Test
    void requiresNew_fromAfterCommit_onARequest_runsInANewTransaction() {
        assertRequiresNewFromAfterCommitRunsInANewTransaction(true);
    }

    @Test
    void required_fromAfterCommit_offTheRequestPath_refusesToJoinTheCompletedTransaction() {
        assertRequiredFromAfterCommitIsRefused(false);
    }

    @Test
    void required_fromAfterCommit_onARequest_refusesToJoinTheCompletedTransaction() {
        assertRequiredFromAfterCommitIsRefused(true);
    }

    private static void assertRequiresNewFromAfterCommitRunsInANewTransaction(boolean onRequest) {
        withRealEngine(onRequest, fixture -> {
            TransactionTemplate outer = fixture.template(TransactionDefinition.PROPAGATION_REQUIRED);
            TransactionTemplate inner = fixture.template(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            AtomicReference<Throwable> innerFailure = new AtomicReference<>();

            outer.executeWithoutResult(_ -> {
                fixture.insert(1, "outer");
                afterCommit(() -> innerFailure.set(catchThrowable(() -> inner.executeWithoutResult(status -> {
                    assertThat(status.isNewTransaction()).isTrue();
                    fixture.insert(2, "after-commit");
                }))));
            });

            assertThat(innerFailure.get()).isNull();
            assertThat(fixture.openedConnections()).hasSize(2);
            assertThat(countRows(fixture.engine())).isEqualTo(2);
            assertThat(readLabel(fixture.engine(), 2)).isEqualTo("after-commit");
        });
    }

    private static void assertRequiredFromAfterCommitIsRefused(boolean onRequest) {
        withRealEngine(onRequest, fixture -> {
            TransactionTemplate outer = fixture.template(TransactionDefinition.PROPAGATION_REQUIRED);
            TransactionTemplate inner = fixture.template(TransactionDefinition.PROPAGATION_REQUIRED);
            AtomicReference<Throwable> innerFailure = new AtomicReference<>();

            outer.executeWithoutResult(_ -> {
                fixture.insert(1, "outer");
                afterCommit(() -> innerFailure.set(catchThrowable(() ->
                        inner.executeWithoutResult(_ -> fixture.insert(2, "joined-after-commit")))));
            });

            assertThat(innerFailure.get())
                    .isInstanceOf(IllegalTransactionStateException.class)
                    .hasMessageContaining("already committed or rolled back");
            assertThat(fixture.openedConnections()).hasSize(1);
            assertThat(countRows(fixture.engine())).isEqualTo(1);
        });
    }

    private static void afterCommit(Runnable callback) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                callback.run();
            }
        });
    }

    // ---------------------------------------------------------------------
    // Failure paths that must not strand a connection
    // ---------------------------------------------------------------------

    @Test
    void begin_whenTheManagerKeyIsAlreadyBound_releasesTheConnectionAndThrows() {
        withRealEngine(false, fixture -> {
            int baseline = fixture.engine().stats().activeConnections();
            TransactionSynchronizationManager.bindResource(fixture.txManager(), "foreign resource");
            try {
                assertThatThrownBy(() -> fixture.template(TransactionDefinition.PROPAGATION_REQUIRED)
                        .executeWithoutResult(_ -> { }))
                        .isInstanceOf(IllegalTransactionStateException.class)
                        .hasMessageContaining("already bound");
                assertThat(fixture.engine().stats().activeConnections())
                        .as("the connection opened for the refused transaction must go back to the pool")
                        .isEqualTo(baseline);
            } finally {
                TransactionSynchronizationManager.unbindResourceIfPossible(fixture.txManager());
            }
        });
    }

    /**
     * Off the request route, suspension borrows one pooled connection to prove a second one
     * exists. With no free slot it must fail within the pool's connection timeout, not hang. The
     * kernel rejects a pool smaller than two, so the test occupies the second slot itself and the
     * outer transaction holds the first.
     */
    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void notSupported_offTheRequestPath_withNoFreePoolSlot_failsWithinTheConnectionTimeout() {
        Map<String, String> exhaustiblePool = Map.of(
                "exeris.runtime.persistence.max-pool-size", "2",
                "exeris.runtime.persistence.min-pool-size", "1",
                "exeris.runtime.persistence.connection-timeout-ms", "500");
        withRealEngine(false, exhaustiblePool, fixture -> {
            TransactionTemplate outer = fixture.template(TransactionDefinition.PROPAGATION_REQUIRED);
            TransactionTemplate inner = fixture.template(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);

            try (PersistenceConnection occupant = fixture.engine().openConnection()) {
                assertThat(occupant.isOpen()).isTrue();
                outer.executeWithoutResult(_ -> {
                    fixture.insert(1, "outer");
                    long started = System.nanoTime();
                    assertThatThrownBy(() -> inner.executeWithoutResult(_ -> { }))
                            .isInstanceOf(CannotCreateTransactionException.class)
                            .hasMessageContaining("suspend the current transaction onto");
                    assertThat(Duration.ofNanos(System.nanoTime() - started))
                            .as("the refusal is bounded by the 500 ms connection timeout")
                            .isLessThan(Duration.ofSeconds(5));
                    fixture.insert(2, "outer-after-refusal");
                });
            }

            assertThat(countRows(fixture.engine())).isEqualTo(2);
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
     * A real engine, a manager wired to it, and the connections that manager has opened —
     * recorded through {@link ExerisJdbcResourceCallback}, the seam {@code ExerisDataSource}
     * registers in production.
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

        TransactionTemplate template(int propagation) {
            DefaultTransactionDefinition definition = new DefaultTransactionDefinition();
            definition.setPropagationBehavior(propagation);
            return new TransactionTemplate(txManager, definition);
        }

        /** The connection of the innermost transaction still running: the last one opened and not yet closed. */
        PersistenceConnection currentConnection() {
            for (int i = opened.size() - 1; i >= 0; i--) {
                if (opened.get(i).isOpen()) {
                    return opened.get(i);
                }
            }
            throw new AssertionError("no transactional connection is open");
        }

        void insert(int id, String label) {
            currentConnection().executeUpdate(
                    "INSERT INTO tx_prop (id, label) VALUES (" + id + ", '" + label + "')");
        }

        List<PersistenceConnection> openedConnections() {
            return List.copyOf(opened);
        }
    }

    /**
     * Boots a real kernel and binds its engine for the calling thread. With {@code onRequest},
     * also binds a {@link PersistenceSessionBox} the way the kernel request dispatcher does for a
     * {@code PROMPT} route, and releases it afterwards as the dispatcher does on completion.
     */
    private static void withRealEngine(boolean onRequest, EngineTest body) {
        withRealEngine(onRequest, Map.of(), body);
    }

    private static void withRealEngine(boolean onRequest, Map<String, String> extraProperties, EngineTest body) {
        String db = "exeris_tx_propagation_it_" + DB_COUNTER.incrementAndGet();
        ExerisRuntimeLifecycle lifecycle =
                newLifecycle("jdbc:h2:mem:" + db + ";DB_CLOSE_DELAY=-1", extraProperties);

        lifecycle.start();
        try {
            PersistenceEngine engine = lifecycle.getPersistenceEngine().orElseThrow(
                    () -> new AssertionError("kernel bootstrap did not bind a PersistenceEngine"));

            try (PersistenceConnection setup = engine.openConnection()) {
                setup.executeUpdate("CREATE TABLE tx_prop (id INT PRIMARY KEY, label VARCHAR(64))");
            }

            Fixture fixture = new Fixture(engine);
            ScopedValue.Carrier carrier = ScopedValue.where(KernelProviders.PERSISTENCE_ENGINE, engine);
            if (onRequest) {
                PersistenceSessionBox box =
                        new PersistenceSessionBox(engine, TransactionIsolation.READ_COMMITTED, false);
                try {
                    carrier.where(PersistenceSessionBox.REQUEST_SESSION, box).run(() -> body.run(fixture));
                } finally {
                    box.release();
                }
            } else {
                carrier.run(() -> body.run(fixture));
            }
        } finally {
            lifecycle.stop();
        }
    }

    private static ExerisRuntimeLifecycle newLifecycle(String jdbcUrl, Map<String, String> extraProperties) {
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
        extraProperties.forEach(env::setProperty);
        return new ExerisRuntimeLifecycle(
                properties,
                new ExerisSpringConfigProvider(env),
                Optional.empty()
        );
    }

    private static long countRows(PersistenceEngine engine) {
        try (PersistenceConnection probe = engine.openConnection();
             QueryResult result = probe.executeQuery("SELECT COUNT(*) FROM tx_prop")) {
            assertThat(result.next()).isTrue();
            return result.row().getLong(0);
        }
    }

    private static String readLabel(PersistenceEngine engine, int id) {
        try (PersistenceConnection probe = engine.openConnection();
             QueryResult result = probe.executeQuery("SELECT label FROM tx_prop WHERE id = " + id)) {
            assertThat(result.next()).isTrue();
            return result.row().getString(0);
        }
    }
}
