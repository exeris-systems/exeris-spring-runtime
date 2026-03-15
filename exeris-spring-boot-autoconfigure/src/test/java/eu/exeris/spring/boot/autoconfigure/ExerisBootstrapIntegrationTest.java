/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.boot.autoconfigure;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for the Exeris bootstrap lifecycle within a Spring application context.
 *
 * <h2>Phase 0 Exit Criteria</h2>
 * <p>These tests verify that Spring and the Exeris runtime can co-start in a single JVM
 * without any servlet container (Tomcat, Jetty, Undertow) or reactive runtime (Netty,
 * WebFlux) appearing on the classpath.
 *
 * <h2>Runtime Coordinator</h2>
 * <p>{@link ExerisRuntimeLifecycle#start()} is a stub in Phase 0. The actual
 * {@code KernelBootstrap} invocation is guarded and will only succeed when the kernel API
 * is confirmed. Until then, the Spring context must still start and expose the expected beans.
 *
 * @since 0.1.0
 */
class ExerisBootstrapIntegrationTest {

    @Test
    void contextLoads_withDefaultProperties() {
        try (var ctx = createContext(Map.of())) {
            assertThat(ctx.getBeanNamesForType(ExerisRuntimeProperties.class)).hasSize(1);
            assertThat(ctx.getBeanNamesForType(ExerisSpringConfigProvider.class)).hasSize(1);
            assertThat(ctx.getBeanNamesForType(ExerisRuntimeLifecycle.class)).hasSize(1);
        }
    }

    @Test
    void exerisIsEnabled_byDefault() {
        try (var ctx = createContext(Map.of())) {
            ExerisRuntimeProperties props = ctx.getBean(ExerisRuntimeProperties.class);
            assertThat(props.enabled()).isTrue();
        }
    }

    @Test
    void exerisCanBeDisabled_viaProperty() {
        try (var ctx = createContext(Map.of("exeris.runtime.enabled", "false"))) {
            assertThat(ctx.getBeanNamesForType(ExerisRuntimeLifecycle.class)).isEmpty();
            assertThat(ctx.getBeanNamesForType(ExerisSpringConfigProvider.class)).isEmpty();
        }
    }

    @Test
    void defaultMode_isPure() {
        try (var ctx = createContext(Map.of())) {
            ExerisRuntimeProperties props = ctx.getBean(ExerisRuntimeProperties.class);
            assertThat(props.web().mode()).isEqualTo(ExerisRuntimeProperties.Mode.PURE);
        }
    }

    @Test
    void configProviderPriority_is150() {
        try (var ctx = createContext(Map.of())) {
            ExerisSpringConfigProvider provider = ctx.getBean(ExerisSpringConfigProvider.class);
            assertThat(provider.priority()).isEqualTo(150);
        }
    }

    @Test
    void noTomcatOrNettyOnClasspath() {
        assertThat(isClassPresent("org.apache.catalina.startup.Tomcat"))
                .as("Tomcat must not be on the classpath")
                .isFalse();
        assertThat(isClassPresent("io.netty.bootstrap.ServerBootstrap"))
                .as("Netty must not be on the classpath")
                .isFalse();
        assertThat(isClassPresent("io.undertow.Undertow"))
                .as("Undertow must not be on the classpath")
                .isFalse();
        assertThat(isClassPresent("org.eclipse.jetty.server.Server"))
                .as("Jetty must not be on the classpath")
                .isFalse();
    }

    private static boolean isClassPresent(String className) {
        try {
            Class.forName(className, false, ExerisBootstrapIntegrationTest.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException _) {
            return false;
        }
    }

    private AnnotationConfigApplicationContext createContext(Map<String, Object> properties) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(
                new MapPropertySource("testProps", properties));
        context.register(ExerisRuntimeAutoConfiguration.class);
        context.refresh();
        return context;
    }
}
