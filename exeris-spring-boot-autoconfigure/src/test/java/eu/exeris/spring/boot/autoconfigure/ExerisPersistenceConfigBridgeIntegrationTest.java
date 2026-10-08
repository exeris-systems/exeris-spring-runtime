/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.boot.autoconfigure;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import eu.exeris.kernel.spi.persistence.EngineStats;
import eu.exeris.kernel.spi.persistence.PersistenceEngine;

/**
 * Runtime integration tests for the persistence configuration bridge of
 * {@link ExerisSpringConfigProvider} against a live kernel.
 *
 * <p>Verifies that Spring persistence properties (including pool sizing) are mapped
 * to kernel configuration keys and applied by the booted {@link PersistenceEngine}.
 * Odd pool size values ensure configuration mapping verification independently of the
 * default processor-derived sizing fallback.
 *
 * <h2>Mode</h2>
 * <p>PURE_MODE.
 *
 * @since 0.7
 */
class ExerisPersistenceConfigBridgeIntegrationTest {

    /** Odd by construction — unreachable by the kernel's {@code cores * 2} adaptive fallback. */
    private static final int CONFIGURED_MAX_POOL_SIZE = 17;

    private static final AtomicInteger DB_COUNTER = new AtomicInteger();

    @Test
    void maxPoolSizeConfiguredInSpring_reachesTheKernelPool() {
        withKernel(Map.of("exeris.runtime.persistence.max-pool-size",
                        Integer.toString(CONFIGURED_MAX_POOL_SIZE)),
                engine -> assertThat(engine.stats().maxConnections())
                        .as("exeris.runtime.persistence.max-pool-size must reach the shared pool; "
                                + "an even value here means the kernel fell back to its adaptive "
                                + "cores*2 default because the raw-key lookup missed")
                        .isEqualTo(CONFIGURED_MAX_POOL_SIZE));
    }

    @Test
    void minIdleAboveTheAdaptiveDefault_bootsWhenMaxPoolSizeIsConfigured() {
        // The production failure shape. Before max-pool-size was plumbed this pairing threw
        // during kernel bootstrap on any host whose adaptive max landed below the configured
        // min; where it did not throw, the pool silently got the CPU-derived size instead.
        Map<String, String> properties = Map.of(
                "exeris.runtime.persistence.min-pool-size", "16",
                "exeris.runtime.persistence.max-pool-size", Integer.toString(CONFIGURED_MAX_POOL_SIZE));

        assertThatCode(() -> withKernel(properties,
                engine -> assertThat(engine.stats().maxConnections())
                        .isEqualTo(CONFIGURED_MAX_POOL_SIZE)))
                .as("min-idle 16 with max 17 is a valid pool; boot must not fail on a "
                        + "CPU-derived max")
                .doesNotThrowAnyException();
    }

    @Test
    void maxPoolSizeUnset_leavesTheKernelAdaptiveDefaultInPlace() {
        // Non-vacuity anchor for the two tests above: unset means unset. The bridge must not
        // start asserting a pool size of its own — the kernel default has to remain visible.
        withKernel(Map.of(), engine -> {
            int max = engine.stats().maxConnections();
            assertThat(max)
                    .as("kernel adaptive default: clamp(availableProcessors() * 2, 2, 32)")
                    .isBetween(2, 32)
                    .isNotEqualTo(CONFIGURED_MAX_POOL_SIZE);
        });
    }

    // ===========================================================================
    // Harness
    // ===========================================================================

    @FunctionalInterface
    private interface EngineAssertion {
        void run(PersistenceEngine engine);
    }

    private static void withKernel(Map<String, String> extraProperties, EngineAssertion body) {
        String db = "exeris_cfg_bridge_it_" + DB_COUNTER.incrementAndGet();

        ExerisRuntimeProperties properties = new ExerisRuntimeProperties(
                true,
                false,
                new ExerisRuntimeProperties.WebProperties(ExerisRuntimeProperties.Mode.PURE),
                new ExerisRuntimeProperties.LifecycleProperties(30),
                new ExerisRuntimeProperties.ShutdownProperties(true, 30)
        );

        // Ephemeral HTTP port: the kernel boot DAG initialises the HTTP subsystem
        // unconditionally and this test does not use it — keep it out of the way rather than
        // racing another test for a fixed port.
        Map<String, String> all = new LinkedHashMap<>();
        all.put("exeris.runtime.network.port", "0");
        all.put("exeris.runtime.persistence.jdbc-url", "jdbc:h2:mem:" + db + ";DB_CLOSE_DELAY=-1");
        all.put("exeris.runtime.persistence.username", "sa");
        all.put("exeris.runtime.persistence.password", "");
        all.put("exeris.runtime.persistence.run-migrations", "false");
        all.putAll(extraProperties);

        MockEnvironment env = new MockEnvironment();
        all.forEach(env::withProperty);

        ExerisRuntimeLifecycle lifecycle = new ExerisRuntimeLifecycle(
                properties,
                new ExerisSpringConfigProvider(env),
                Optional.empty()
        );

        lifecycle.start();
        try {
            PersistenceEngine engine = lifecycle.getPersistenceEngine().orElseThrow(
                    () -> new AssertionError(
                            "kernel bootstrap did not bind a PersistenceEngine — check that "
                                    + "exeris-kernel-community is on the test classpath"));
            body.run(engine);
        } finally {
            lifecycle.stop();
        }
    }
}
