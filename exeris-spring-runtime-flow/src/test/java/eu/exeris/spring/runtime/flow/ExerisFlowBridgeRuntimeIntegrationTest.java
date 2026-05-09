/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.flow;

import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import eu.exeris.kernel.spi.flow.FlowDefinitionBuilder;
import eu.exeris.kernel.spi.flow.FlowEngine;
import eu.exeris.kernel.spi.flow.model.FlowContext;
import eu.exeris.kernel.spi.flow.model.FlowDefinition;
import eu.exeris.kernel.spi.flow.model.FlowOutcome;
import eu.exeris.spring.boot.autoconfigure.ExerisRuntimeLifecycle;
import eu.exeris.spring.boot.autoconfigure.ExerisRuntimeProperties;
import eu.exeris.spring.boot.autoconfigure.ExerisSpringConfigProvider;

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

    private static ExerisRuntimeLifecycle newLifecycle() {
        ExerisRuntimeProperties properties = new ExerisRuntimeProperties(
                true,
                false,
                new ExerisRuntimeProperties.WebProperties(ExerisRuntimeProperties.Mode.PURE),
                new ExerisRuntimeProperties.LifecycleProperties(30),
                new ExerisRuntimeProperties.ShutdownProperties(true, 30)
        );
        // H2 in-memory + ephemeral HTTP port — same shape as the events bridge IT.
        // The flow bridge does not depend on persistence or HTTP, but the kernel boot
        // DAG initialises both unconditionally; we keep them out of the way.
        MockEnvironment env = new MockEnvironment()
                .withProperty("exeris.runtime.network.port", "0")
                .withProperty("exeris.runtime.persistence.jdbc-url",
                        "jdbc:h2:mem:exeris_flow_runtime_it;DB_CLOSE_DELAY=-1")
                .withProperty("exeris.runtime.persistence.username", "sa")
                .withProperty("exeris.runtime.persistence.password", "")
                .withProperty("exeris.runtime.persistence.run-migrations", "false");
        return new ExerisRuntimeLifecycle(
                properties,
                new ExerisSpringConfigProvider(env),
                Optional.empty()
        );
    }
}
