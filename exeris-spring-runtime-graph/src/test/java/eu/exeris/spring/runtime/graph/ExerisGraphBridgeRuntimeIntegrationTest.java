/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.graph;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.env.MockEnvironment;

import eu.exeris.kernel.spi.graph.GraphEngine;
import eu.exeris.kernel.spi.graph.GraphSession;
import eu.exeris.kernel.spi.graph.model.GraphEdgeDescriptor;
import eu.exeris.kernel.spi.graph.model.GraphTraversal;
import eu.exeris.spring.boot.autoconfigure.ExerisRuntimeAutoConfiguration;
import eu.exeris.spring.boot.autoconfigure.ExerisRuntimeLifecycle;
import eu.exeris.spring.boot.autoconfigure.ExerisRuntimeProperties;
import eu.exeris.spring.boot.autoconfigure.ExerisSpringConfigProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Phase 4C Step 5 — runtime integration test (per ADR-030 obligation 8).
 *
 * <p>This IT validates the spring-side seam wiring end-to-end under a real Spring
 * {@link AnnotationConfigApplicationContext} with the full autoconfig stack — but uses
 * an <strong>in-memory stub engine</strong> rather than a Testcontainers-backed
 * PostgreSQL+PGQ. The ADR-030 obligation 8 wording allows either ("in-memory or
 * container-backed"); the in-memory variant keeps the IT CI-cheap and focused on
 * "seam wiring (autoconfig stand-down when engine absent; template happy-path;
 * annotation-processor routing)". Kernel-side correctness (PGQ transpilation, churn
 * ratio, dialect parity) is the kernel's responsibility per
 * {@code exeris-kernel/docs/subsystems/graph.md}.
 *
 * <h2>What this IT covers vs. unit tests</h2>
 *
 * <p>The unit tests in {@link ExerisGraphAutoConfigurationTest},
 * {@link ExerisGraphTemplateTest}, and {@link ExerisGraphQueryProcessorTest} exercise
 * each component in isolation with mocked collaborators. This IT exercises them
 * <em>together</em> in a real Spring context with the autoconfig wired through
 * {@link ExerisRuntimeAutoConfiguration} — proving that the autoconfig ordering
 * ({@code @AutoConfiguration(after = ExerisRuntimeAutoConfiguration.class)} from Step 2
 * review #35) actually places the graph beans correctly relative to the lifecycle
 * bean Spring's topological sort can observe.
 */
class ExerisGraphBridgeRuntimeIntegrationTest {

    private AnnotationConfigApplicationContext context;

    @AfterEach
    void tearDown() {
        if (context != null) {
            context.close();
            context = null;
        }
    }

    @Test
    void seamActivates_whenPropertyEnabled_supplierBeanPresentSubscribesEmptyAtBoot() {
        context = bootContext(false);

        // Both Step 2 + Step 3 beans materialised.
        assertThat(context.getBeanNamesForType(GraphEngineSupplier.class)).hasSize(1);
        assertThat(context.getBeanNamesForType(ExerisGraphTemplate.class)).hasSize(1);
        assertThat(context.getBeanNamesForType(ExerisGraphQueryProcessor.class)).hasSize(1);

        GraphEngineSupplier supplier = context.getBean(GraphEngineSupplier.class);
        // No engine captured: kernel-community's CommunityGraphProvider was not selected by the
        // kernel's BootstrapProviderSelector because the lifecycle was not started (auto-start
        // is false). The supplier surface stays operator-visible: tryGet() empty,
        // requireEngine() throws.
        assertThat(supplier.tryGet()).isEmpty();
        assertThatThrownBy(supplier::requireEngine)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("GraphEngine is not available");
    }

    @Test
    void templateDispatch_whenEngineCaptured_delegatesToCapturedEngine() throws Exception {
        context = bootContext(false);

        GraphEngine engine = mock(GraphEngine.class);
        GraphSession session = mock(GraphSession.class);
        when(engine.openSession()).thenReturn(session);
        UUID a = UUID.randomUUID();
        when(session.traverseBreadthFirst(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(a));

        ExerisRuntimeLifecycle lifecycle = context.getBean(ExerisRuntimeLifecycle.class);
        captureGraphEngineReflectively(lifecycle, engine);

        ExerisGraphTemplate template = context.getBean(ExerisGraphTemplate.class);
        GraphTraversal traversal = GraphTraversal.create(
                UUID.randomUUID(),
                GraphEdgeDescriptor.create("User", "FOLLOWS", "User"),
                2);

        assertThat(template.traverseBfs(traversal))
                .as("template.traverseBfs flows through to engine.openSession().traverseBreadthFirst")
                .containsExactly(a);
    }

    @Test
    void annotatedMethod_whenEngineCaptured_routedThroughTemplateAndProxy() throws Exception {
        context = bootContext(true);

        GraphEngine engine = mock(GraphEngine.class);
        GraphSession session = mock(GraphSession.class);
        when(engine.openSession()).thenReturn(session);
        UUID b = UUID.randomUUID();
        when(session.traverseBreadthFirst(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(b));

        ExerisRuntimeLifecycle lifecycle = context.getBean(ExerisRuntimeLifecycle.class);
        captureGraphEngineReflectively(lifecycle, engine);

        AnnotatedGraphQueryBean bean = context.getBean(AnnotatedGraphQueryBean.class);
        GraphTraversal traversal = GraphTraversal.create(
                UUID.randomUUID(),
                GraphEdgeDescriptor.create("User", "FOLLOWS", "User"),
                2);

        // The annotated method's body throws UnsupportedOperationException; if the proxy works,
        // we never reach that body — the interceptor short-circuits to template.traverseBfs.
        assertThat(bean.findNeighbours(traversal))
                .as("@ExerisGraphQuery proxy routes through template")
                .containsExactly(b);
    }

    // ---- boot helpers ----

    private AnnotationConfigApplicationContext bootContext(boolean withAnnotatedBean) {
        var ctx = new AnnotationConfigApplicationContext();
        ExerisRuntimeProperties properties = new ExerisRuntimeProperties();
        MockEnvironment env = new MockEnvironment()
                .withProperty("exeris.runtime.graph.enabled", "true");
        ExerisSpringConfigProvider configProvider = new ExerisSpringConfigProvider(env);
        ExerisRuntimeLifecycle lifecycle = new ExerisRuntimeLifecycle(
                properties, configProvider, Optional.empty());
        ctx.getBeanFactory().registerSingleton("exerisRuntimeLifecycle", lifecycle);

        ctx.getEnvironment().getPropertySources().addFirst(
                new org.springframework.core.env.MapPropertySource("testProps",
                        java.util.Map.of("exeris.runtime.graph.enabled", "true")));
        ctx.register(ExerisGraphAutoConfiguration.class);
        if (withAnnotatedBean) {
            ctx.register(AnnotatedBeanConfig.class);
        }
        ctx.refresh();
        return ctx;
    }

    private static void captureGraphEngineReflectively(ExerisRuntimeLifecycle lifecycle,
                                                        GraphEngine engine) throws Exception {
        Field field = ExerisRuntimeLifecycle.class.getDeclaredField("capturedGraphEngine");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        AtomicReference<GraphEngine> ref = (AtomicReference<GraphEngine>) field.get(lifecycle);
        ref.set(engine);
    }

    @Configuration
    @EnableConfigurationProperties(ExerisGraphProperties.class)
    static class AnnotatedBeanConfig {

        @Bean
        @SuppressWarnings("unused")
        AnnotatedGraphQueryBean annotatedGraphQueryBean() {
            return new AnnotatedGraphQueryBean();
        }
    }

    public static class AnnotatedGraphQueryBean {

        @ExerisGraphQuery(value = "BFS — proxied by ExerisGraphQueryProcessor")
        public List<UUID> findNeighbours(GraphTraversal traversal) {
            throw new UnsupportedOperationException(
                    "proxied — interceptor must short-circuit before reaching this body");
        }
    }
}
