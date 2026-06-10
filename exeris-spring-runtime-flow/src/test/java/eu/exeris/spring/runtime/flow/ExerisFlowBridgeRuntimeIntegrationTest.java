/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.flow;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.flow.FlowDefinitionBuilder;
import eu.exeris.kernel.spi.flow.FlowEngine;
import eu.exeris.kernel.spi.flow.model.FlowContext;
import eu.exeris.kernel.spi.flow.model.FlowDefinition;
import eu.exeris.kernel.spi.flow.model.FlowOutcome;
import eu.exeris.spring.boot.autoconfigure.ExerisRuntimeLifecycle;
import eu.exeris.spring.boot.autoconfigure.ExerisRuntimeProperties;
import eu.exeris.spring.boot.autoconfigure.ExerisSpringConfigProvider;
import eu.exeris.spring.boot.autoconfigure.KernelProviderScope;

/**
 * End-to-end runtime integration test for the flow bridge.
 *
 * <p>Boots a real {@link ExerisRuntimeLifecycle} with {@code exeris-kernel-community} on
 * the test classpath, so the kernel bootstrap discovers the community
 * {@code FlowProvider} via {@code ServiceLoader} and binds a real {@link FlowEngine}
 * into {@code KernelProviders.FLOW_ENGINE}. The test verifies the load-bearing
 * assumption of the flow module: that the engine reference can be captured across the
 * {@code ScopedValue} boundary and consumed by Spring beans on a different thread.
 *
 * <h2>What this proves vs the unit suite</h2>
 * <ul>
 *   <li>{@link ExerisRuntimeLifecycle#getFlowEngine()} is populated after a real kernel
 *       bootstrap and cleared after shutdown — same shape as the events bridge IT, locked
 *       in here as the Step 2 commitment from the PR #17 review.</li>
 *   <li>{@link FlowEngineSupplier#requireEngine()} fails loud both before
 *       {@link ExerisRuntimeLifecycle#start()} runs and after {@code stop()} clears the
 *       captured reference. This is the first {@code requireEngine()} consumer call site,
 *       paired with the failure-mode test as the PR #17 review required.</li>
 *   <li>{@link ExerisFlowTemplate#schedule(String, FlowContext)} reaches the kernel
 *       scheduler against a live community engine, the step action runs, and the seam
 *       works without any Spring auto-configuration glue (direct construction is enough).</li>
 * </ul>
 *
 * <h2>Mode</h2>
 * <p>PURE_MODE — the flow bridge is mode-agnostic; this test does not exercise web mode.
 *
 * @since 0.1.0
 */
class ExerisFlowBridgeRuntimeIntegrationTest {

    private static final long AWAIT_DISPATCH_SECONDS = 5L;

    @Test
    void lifecycleCapturesFlowEngineDuringBootAndClearsItOnStop() {
        ExerisRuntimeLifecycle lifecycle = newLifecycle();

        assertThat(lifecycle.getFlowEngine())
                .as("Engine must not be visible before start()")
                .isEmpty();

        lifecycle.start();
        try {
            assertThat(lifecycle.getFlowEngine())
                    .as("Engine must be captured once the kernel boot thread has bound FLOW_ENGINE")
                    .isPresent();
        } finally {
            lifecycle.stop();
        }

        assertThat(lifecycle.getFlowEngine())
                .as("Engine reference must be cleared after shutdown so it does not outlive the kernel")
                .isEmpty();
    }

    @Test
    void requireEngineFailsBeforeStartAndAfterStop() {
        ExerisRuntimeLifecycle lifecycle = newLifecycle();
        FlowEngineSupplier supplier = lifecycle::getFlowEngine;

        assertThatThrownBy(supplier::requireEngine)
                .as("Before start(), no FLOW_ENGINE has been bound — first call site must throw a clear message")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FlowEngine is not available");

        lifecycle.start();
        try {
            assertThat(supplier.requireEngine())
                    .as("After start(), the captured engine must be returned without exception")
                    .isNotNull();
        } finally {
            lifecycle.stop();
        }

        assertThatThrownBy(supplier::requireEngine)
                .as("After stop(), the captured reference is cleared so requireEngine() must throw again")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FlowEngine is not available");
    }

    @Test
    void templateSchedulesAndExecutesSingleStepFlowAgainstRealKernelEngine() throws InterruptedException {
        ExerisRuntimeLifecycle lifecycle = newLifecycle();
        lifecycle.start();
        try {
            FlowEngineSupplier supplier = lifecycle::getFlowEngine;
            ExerisFlowTemplate template = new ExerisFlowTemplate(supplier);

            CountDownLatch executed = new CountDownLatch(1);
            AtomicReference<FlowContext> seenInsideStep = new AtomicReference<>();

            ExerisFlowDefinition definition = new ExerisFlowDefinition() {
                @Override public String name() { return "ping"; }
                @Override public FlowDefinition define(FlowDefinitionBuilder b) {
                    return b.step("emit", ctx -> {
                                seenInsideStep.set(ctx);
                                executed.countDown();
                                return FlowOutcome.COMPLETE;
                            }, null)
                            .timeoutDuration(5_000_000_000L)
                            .build();
                }
            };

            // Compile & register through the same path the registrar would use, so this
            // test covers the FlowExecutionPlanFactory.compile -> template.registerPlan
            // contract end-to-end against a real kernel engine.
            FlowEngine engine = supplier.requireEngine();
            FlowDefinitionBuilder builder = engine.plans().newDefinition(definition.name());
            FlowDefinition def = definition.define(builder);
            template.registerPlan(definition.name(), engine.plans().compile(def));

            FlowContext seed = template.schedule("ping");

            assertThat(executed.await(AWAIT_DISPATCH_SECONDS, TimeUnit.SECONDS))
                    .as("Flow step must be invoked within %d seconds via the live kernel scheduler",
                            AWAIT_DISPATCH_SECONDS)
                    .isTrue();
            assertThat(seenInsideStep.get())
                    .as("Step lambda must receive a FlowContext for the scheduled instance")
                    .isNotNull();
            assertThat(seenInsideStep.get().definitionName()).isEqualTo("ping");
            // Seed and step-side context refer to the same instance id — the kernel
            // copies / advances the context internally, but instance identity is stable.
            assertThat(seenInsideStep.get().instanceIdMost()).isEqualTo(seed.instanceIdMost());
            assertThat(seenInsideStep.get().instanceIdLeast()).isEqualTo(seed.instanceIdLeast());
        } finally {
            lifecycle.stop();
        }
    }

    /**
     * Provider-scope runtime IT — proves, against a live community kernel with a real JDBC
     * {@code PersistenceEngine}, that:
     * <ol>
     *   <li><b>Control:</b> a step registered through the <em>raw</em> kernel builder executes
     *       on a flow scheduler worker thread with {@code KernelProviders.PERSISTENCE_ENGINE}
     *       <em>unbound</em> — the exact gap that made every saga step touching the compat
     *       {@code ExerisDataSource} fail ({@code FAILED_ROLLEDBACK} at step 0) while the
     *       kernel-owned snapshot store kept working.</li>
     *   <li><b>Fix:</b> the same step registered through {@link ProviderScopedFlowDefinitionBuilder}
     *       (the decorator the registrar applies to every {@code ExerisFlowDefinition}) observes
     *       the slot re-bound from the lifecycle's captured reference.</li>
     * </ol>
     *
     * <p>If the control assertion ever flips — i.e. the kernel starts propagating the bootstrap
     * scope to flow worker threads — the runtime-side wrap has collapsed to a pass-through and
     * can be retired; treat that failure as a removal signal, not a regression.
     */
    @Test
    void stepBodiesObserveKernelProviderScopeOnFlowWorkerThreads() throws InterruptedException {
        ExerisRuntimeLifecycle lifecycle = newPersistenceLifecycle(
                "jdbc:h2:mem:exeris_flow_provider_scope_it;DB_CLOSE_DELAY=-1");
        lifecycle.start();
        try {
            assertThat(lifecycle.getPersistenceEngine())
                    .as("kernel must capture a PersistenceEngine for this IT to be meaningful")
                    .isPresent();

            FlowEngine engine = lifecycle.getFlowEngine().orElseThrow();
            ExerisFlowTemplate template = new ExerisFlowTemplate(lifecycle::getFlowEngine);

            CountDownLatch controlRan = new CountDownLatch(1);
            AtomicBoolean controlSawBinding = new AtomicBoolean(true);
            FlowDefinition control = engine.plans().newDefinition("scope-control")
                    .step("observe-raw", ctx -> {
                        controlSawBinding.set(KernelProviders.PERSISTENCE_ENGINE.isBound());
                        controlRan.countDown();
                        return FlowOutcome.COMPLETE;
                    }, null)
                    .timeoutDuration(5_000_000_000L)
                    .build();
            template.registerPlan("scope-control", engine.plans().compile(control));

            CountDownLatch wrappedRan = new CountDownLatch(1);
            AtomicBoolean wrappedSawBinding = new AtomicBoolean(false);
            FlowDefinitionBuilder scopedBuilder = new ProviderScopedFlowDefinitionBuilder(
                    engine.plans().newDefinition("scope-wrapped"),
                    KernelProviderScope.fromLifecycle(lifecycle));
            FlowDefinition wrapped = scopedBuilder
                    .step("observe-wrapped", ctx -> {
                        wrappedSawBinding.set(KernelProviders.PERSISTENCE_ENGINE.isBound());
                        wrappedRan.countDown();
                        return FlowOutcome.COMPLETE;
                    }, null)
                    .timeoutDuration(5_000_000_000L)
                    .build();
            template.registerPlan("scope-wrapped", engine.plans().compile(wrapped));

            template.schedule("scope-control");
            template.schedule("scope-wrapped");

            assertThat(controlRan.await(AWAIT_DISPATCH_SECONDS, TimeUnit.SECONDS)).isTrue();
            assertThat(wrappedRan.await(AWAIT_DISPATCH_SECONDS, TimeUnit.SECONDS)).isTrue();

            assertThat(controlSawBinding.get())
                    .as("control: flow worker threads do not inherit the bootstrap ScopedValue "
                            + "scope — the gap this fix covers (see javadoc: a flip here is a "
                            + "removal signal for the wrap, not a regression)")
                    .isFalse();
            assertThat(wrappedSawBinding.get())
                    .as("wrapped: step body must observe PERSISTENCE_ENGINE re-bound from the "
                            + "lifecycle's captured reference")
                    .isTrue();
        } finally {
            lifecycle.stop();
        }
    }

    /**
     * Step 4 closure runtime IT — proves the kernel 0.8.0 + ADR-022 wiring is reachable
     * from the Spring side end-to-end:
     * <ol>
     *   <li>Lifecycle A schedules a flow whose first step returns {@code PARK}; the kernel
     *       persists a {@code state = PARKED} row in {@code exeris_saga_state} via
     *       {@code JdbcFlowSnapshotStore}.</li>
     *   <li>Lifecycle A stops; the H2 in-memory database stays alive for the JVM
     *       ({@code DB_CLOSE_DELAY=-1}) so the row survives.</li>
     *   <li>We assert directly against the DB that the row exists with the expected
     *       composite-PK + {@code state = 'PARKED'} — that's the unambiguous machine
     *       check that {@code exeris.runtime.flow.persistence-enabled=true} actually
     *       reaches the kernel via {@code flowKernelKeyAlias} and the kernel wires
     *       {@code JdbcFlowSnapshotStore} instead of the in-memory fallback.</li>
     *   <li>Lifecycle B starts against the same DB. The kernel rehydrates parked snapshots
     *       on demand; the Spring template's {@code lookupParked(idMost, idLeast)} call
     *       drives the rehydration path through {@code KernelProviders.FLOW_SNAPSHOT_STORE}.</li>
     * </ol>
     *
     * <p>The plan is re-registered on lifecycle B under the same name — the kernel matches
     * plans by {@code definitionName}, and a fresh lifecycle starts with an empty plan
     * registry. This re-registration step is the Spring-side contract documented in
     * {@code phase-4-invariants.md}: durable saga recovery requires that applications
     * register the same plan definitions on every lifecycle restart.
     *
     * <p>This is the canonical Phase 4B Step 4 deliverable — the IT mentioned in the
     * master phase doc as the closure gate before {@code 0.5.0-preview} ships durable
     * saga state by default.
     */
    @Test
    void parkedFlowSnapshotsSurviveLifecycleRestartViaJdbcStore() throws InterruptedException, SQLException {
        String jdbcUrl = "jdbc:h2:mem:exeris_flow_persistence_restart_it;DB_CLOSE_DELAY=-1";

        // ===== Lifecycle A — schedule, let the flow park, stop =====
        long instanceIdMost;
        long instanceIdLeast;
        {
            ExerisRuntimeLifecycle lifecycleA = newPersistenceLifecycle(jdbcUrl);
            lifecycleA.start();
            try {
                FlowEngine engineA = lifecycleA.getFlowEngine().orElseThrow();
                ExerisFlowTemplate templateA = new ExerisFlowTemplate(lifecycleA::getFlowEngine);

                CountDownLatch step0Entered = new CountDownLatch(1);
                ExerisFlowDefinition def = parkAndResumeDefinition(step0Entered, new CountDownLatch(1));

                FlowDefinition built = def.define(engineA.plans().newDefinition(def.name()));
                templateA.registerPlan(def.name(), engineA.plans().compile(built));

                FlowContext seed = templateA.schedule(def.name());
                instanceIdMost = seed.instanceIdMost();
                instanceIdLeast = seed.instanceIdLeast();

                assertThat(step0Entered.await(AWAIT_DISPATCH_SECONDS, TimeUnit.SECONDS))
                        .as("Step 0 must run and return PARK within %d seconds", AWAIT_DISPATCH_SECONDS)
                        .isTrue();
                // Wait for the kernel's async JDBC commit. The step-return path is
                // synchronous, but the H2 commit runs on a separate IO thread. Poll the
                // saga_state row up to 5 seconds rather than a blind Thread.sleep — fast
                // path completes in milliseconds on an unloaded machine; bounded wait
                // keeps the test stable under CI load / TSAN slowdown without inflating
                // happy-path time.
                awaitParkedSnapshot(jdbcUrl, seed.instanceIdMost(), seed.instanceIdLeast(),
                        AWAIT_DISPATCH_SECONDS);
            } finally {
                lifecycleA.stop();
            }
        }

        // ===== Direct DB assertion — the parked row must be persisted =====
        try (Connection conn = DriverManager.getConnection(jdbcUrl, "sa", "")) {
            // First confirm the table even exists (rules out migration not running)
            try (PreparedStatement metaStmt = conn.prepareStatement(
                     "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES "
                             + "WHERE UPPER(TABLE_NAME) = 'EXERIS_SAGA_STATE'");
                 ResultSet metaRs = metaStmt.executeQuery()) {
                assertThat(metaRs.next()).isTrue();
                assertThat(metaRs.getInt(1))
                        .as("exeris_saga_state table must exist after lifecycle A (kernel "
                                + "Flyway migration V0.7.0__create_saga_state.sql must have run)")
                        .isEqualTo(1);
            }
            try (PreparedStatement stmt = conn.prepareStatement(
                     "SELECT state, definition_name FROM exeris_saga_state "
                             + "WHERE instance_id_most = ? AND instance_id_least = ?")) {
                stmt.setLong(1, instanceIdMost);
                stmt.setLong(2, instanceIdLeast);
                try (ResultSet rs = stmt.executeQuery()) {
                    assertThat(rs.next())
                            .as("exeris_saga_state must contain a row for the parked flow — "
                                    + "this is the load-bearing proof that "
                                    + "exeris.runtime.flow.persistence-enabled=true reaches the "
                                    + "kernel via the flow.* key alias AND that the kernel "
                                    + "wired JdbcFlowSnapshotStore instead of the in-memory fallback")
                            .isTrue();
                    assertThat(rs.getString("state"))
                            .as("Snapshot state column must be PARKED")
                            .isEqualTo("PARKED");
                    assertThat(rs.getString("definition_name"))
                            .isEqualTo("pause-and-resume");
                    assertThat(rs.next()).as("Exactly one row expected for this instance").isFalse();
                }
            }
        }

        // ===== Lifecycle B — rehydrate parked flow from JDBC store =====
        ExerisRuntimeLifecycle lifecycleB = newPersistenceLifecycle(jdbcUrl);
        lifecycleB.start();
        try {
            FlowEngine engineB = lifecycleB.getFlowEngine().orElseThrow();
            ExerisFlowTemplate templateB = new ExerisFlowTemplate(lifecycleB::getFlowEngine);

            // Re-register the same plan under the same name — kernel matches by definitionName
            // and lifecycle B starts with an empty plan registry. This is the Spring-side
            // contract for durable recovery: applications register plans on every start.
            ExerisFlowDefinition def = parkAndResumeDefinition(new CountDownLatch(1), new CountDownLatch(1));
            FlowDefinition built = def.define(engineB.plans().newDefinition(def.name()));
            templateB.registerPlan(def.name(), engineB.plans().compile(built));

            Optional<FlowContext> rehydrated = templateB.lookupParked(instanceIdMost, instanceIdLeast);
            assertThat(rehydrated)
                    .as("Parked snapshot must be discoverable on lifecycle B via the kernel's "
                            + "FlowSnapshotStore — this proves end-to-end durable recovery "
                            + "across a Spring lifecycle restart")
                    .isPresent();
            assertThat(rehydrated.get().definitionName()).isEqualTo("pause-and-resume");
            assertThat(rehydrated.get().instanceIdMost()).isEqualTo(instanceIdMost);
            assertThat(rehydrated.get().instanceIdLeast()).isEqualTo(instanceIdLeast);
        } finally {
            lifecycleB.stop();
        }
    }

    /**
     * Two-step pause-and-resume flow. Step 0 latches and parks; step 1 latches and completes.
     * Both latches are inputs so the same definition can be reused across lifecycles with
     * fresh observers.
     */
    private static ExerisFlowDefinition parkAndResumeDefinition(CountDownLatch step0Entered,
                                                                  CountDownLatch step1Ran) {
        return new ExerisFlowDefinition() {
            @Override
            public String name() {
                return "pause-and-resume";
            }

            @Override
            public FlowDefinition define(FlowDefinitionBuilder b) {
                return b
                        .step("park-self", ctx -> {
                            step0Entered.countDown();
                            return FlowOutcome.PARK;
                        }, null)
                        .step("resume", ctx -> {
                            step1Ran.countDown();
                            return FlowOutcome.COMPLETE;
                        }, null)
                        .transition(0, 1)
                        .timeoutDuration(60_000_000_000L)
                        .build();
            }
        };
    }

    /**
     * Polls the {@code exeris_saga_state} table at 50 ms intervals up to
     * {@code timeoutSeconds} until a {@code PARKED} row for the given instance id
     * appears. Replaces the blind {@code Thread.sleep(250)} that was racing against
     * the kernel's async H2 commit thread — bounded wait stays stable under CI load
     * without inflating happy-path runtime.
     */
    private static void awaitParkedSnapshot(String jdbcUrl, long instanceIdMost, long instanceIdLeast,
                                             long timeoutSeconds) throws InterruptedException, SQLException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        while (System.nanoTime() < deadline) {
            try (Connection conn = DriverManager.getConnection(jdbcUrl, "sa", "");
                 PreparedStatement stmt = conn.prepareStatement(
                         "SELECT COUNT(*) FROM exeris_saga_state "
                                 + "WHERE instance_id_most = ? AND instance_id_least = ? AND state = 'PARKED'")) {
                stmt.setLong(1, instanceIdMost);
                stmt.setLong(2, instanceIdLeast);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next() && rs.getInt(1) > 0) {
                        return;
                    }
                }
            } catch (SQLException _) {
                // Table may not exist yet on the first poll iteration if migrations
                // ran concurrently with the first step. Swallow and retry; the deadline
                // bounds the overall wait.
            }
            Thread.sleep(50);
        }
    }

    private static ExerisRuntimeLifecycle newLifecycle() {
        return newLifecycleWith("jdbc:h2:mem:exeris_flow_runtime_it;DB_CLOSE_DELAY=-1", false);
    }

    private static ExerisRuntimeLifecycle newPersistenceLifecycle(String jdbcUrl) {
        return newLifecycleWith(jdbcUrl, true);
    }

    private static ExerisRuntimeLifecycle newLifecycleWith(String jdbcUrl, boolean runMigrations) {
        ExerisRuntimeProperties properties = new ExerisRuntimeProperties(
                true,
                false,
                new ExerisRuntimeProperties.WebProperties(ExerisRuntimeProperties.Mode.PURE),
                new ExerisRuntimeProperties.LifecycleProperties(30),
                new ExerisRuntimeProperties.ShutdownProperties(true, 30)
        );
        // H2 in-memory + ephemeral HTTP port — same shape as the events bridge IT.
        // The flow bridge does not depend on HTTP, but the kernel boot DAG initialises
        // it unconditionally; we keep it out of the way.
        MockEnvironment env = new MockEnvironment()
                .withProperty("exeris.runtime.network.port", "0")
                .withProperty("exeris.runtime.persistence.jdbc-url", jdbcUrl)
                .withProperty("exeris.runtime.persistence.username", "sa")
                .withProperty("exeris.runtime.persistence.password", "")
                .withProperty("exeris.runtime.persistence.run-migrations", Boolean.toString(runMigrations))
                // Step 4 closure: flow module is opt-in, persistence default is now true (ADR-022).
                // Setting it explicitly here in case the test default changes again later.
                .withProperty("exeris.runtime.flow.enabled", "true")
                .withProperty("exeris.runtime.flow.persistence-enabled", Boolean.toString(runMigrations));
        return new ExerisRuntimeLifecycle(
                properties,
                new ExerisSpringConfigProvider(env),
                Optional.empty()
        );
    }
}
