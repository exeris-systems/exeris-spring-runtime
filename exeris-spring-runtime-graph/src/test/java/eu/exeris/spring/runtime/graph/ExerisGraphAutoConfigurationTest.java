/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.graph;

import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

import eu.exeris.kernel.spi.graph.GraphEngine;
import eu.exeris.spring.boot.autoconfigure.ExerisRuntimeLifecycle;
import eu.exeris.spring.boot.autoconfigure.ExerisRuntimeProperties;
import eu.exeris.spring.boot.autoconfigure.ExerisSpringConfigProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * Phase 4C Step 2 autoconfig wiring tests (per ADR-030 Engineering Protocol Deliverable 2).
 *
 * <p>Covers the three-state activation matrix from {@link ExerisGraphProperties}:
 *
 * <ul>
 *   <li>Property disabled (default) — autoconfig stands down; no supplier bean exposed.</li>
 *   <li>Property enabled, no kernel engine, {@code requireEngine=true} (default) —
 *       supplier bean present, {@code tryGet()} returns empty, {@code requireEngine()}
 *       throws {@link IllegalStateException} with an operator-readable diagnostic.</li>
 *   <li>Property enabled, kernel engine bound, {@code requireEngine=true} —
 *       supplier bean returns the captured engine via {@code tryGet()} /
 *       {@code requireEngine()}.</li>
 * </ul>
 *
 * <p>This test exercises only Step 2 surface ({@link ExerisGraphAutoConfiguration},
 * {@link ExerisGraphProperties}, {@link GraphEngineSupplier}). The
 * {@code ExerisGraphTemplate} + {@code @ExerisGraphQuery} surface is Step 3 and is not
 * covered here.
 */
class ExerisGraphAutoConfigurationTest {

    @Test
    void propertyDisabled_autoconfigStandsDown_noSupplierBean() {
        try (var context = createContext(Map.of())) {
            assertThat(context.getBeanNamesForType(GraphEngineSupplier.class))
                    .as("disabled property → no GraphEngineSupplier bean")
                    .isEmpty();
        }
    }

    @Test
    void propertyEnabled_noEngineCaptured_tryGetEmpty_requireEngineThrows() {
        try (var context = createContext(Map.of(
                "exeris.runtime.graph.enabled", "true"))) {
            GraphEngineSupplier supplier = context.getBean(GraphEngineSupplier.class);

            assertThat(supplier.tryGet())
                    .as("kernel did not bind GRAPH_ENGINE → tryGet returns empty")
                    .isEmpty();

            assertThatThrownBy(supplier::requireEngine)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("GraphEngine is not available")
                    .hasMessageContaining("exeris.runtime.graph.require-engine=false");
        }
    }

    @Test
    void propertyEnabled_engineCaptured_supplierReturnsIt() {
        GraphEngine engine = mock(GraphEngine.class);
        try (var context = createContext(Map.of(
                "exeris.runtime.graph.enabled", "true"),
                lifecycle -> setCapturedGraphEngine(lifecycle, engine))) {
            GraphEngineSupplier supplier = context.getBean(GraphEngineSupplier.class);

            assertThat(supplier.tryGet())
                    .as("captured engine surfaces through the supplier")
                    .contains(engine);
            assertThat(supplier.requireEngine()).isSameAs(engine);
        }
    }

    // ---- helpers ----

    private AnnotationConfigApplicationContext createContext(Map<String, Object> properties) {
        return createContext(properties, _ -> {});
    }

    private AnnotationConfigApplicationContext createContext(
            Map<String, Object> properties,
            java.util.function.Consumer<ExerisRuntimeLifecycle> lifecycleSetup) {
        var context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(
                new MapPropertySource("testProps", Map.copyOf(properties)));
        // Register a minimal ExerisRuntimeLifecycle stub so @ConditionalOnBean is satisfied.
        ExerisRuntimeLifecycle lifecycle = new ExerisRuntimeLifecycle(
                new ExerisRuntimeProperties(),
                mock(ExerisSpringConfigProvider.class),
                Optional.empty());
        lifecycleSetup.accept(lifecycle);
        context.getBeanFactory().registerSingleton("exerisRuntimeLifecycle", lifecycle);
        context.register(ExerisGraphAutoConfiguration.class);
        context.refresh();
        return context;
    }

    /**
     * Sets the captured GraphEngine via reflection — the field is package-private to
     * autoconfigure, and tests cross-package; reflection here is acceptable because the
     * production path (boot thread capturing from ScopedValue) is integration-tested in
     * Step 5, not this unit test.
     */
    private static void setCapturedGraphEngine(ExerisRuntimeLifecycle lifecycle, GraphEngine engine) {
        try {
            var field = ExerisRuntimeLifecycle.class.getDeclaredField("capturedGraphEngine");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            var ref = (java.util.concurrent.atomic.AtomicReference<GraphEngine>) field.get(lifecycle);
            ref.set(engine);
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError("failed to wire captured GraphEngine via reflection", ex);
        }
    }
}
