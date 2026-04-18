/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.boot.autoconfigure;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.LockSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;

import eu.exeris.kernel.spi.http.HttpHandler;

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
        try (var ctx = createContext(Map.of("exeris.runtime.auto-start", "false"))) {
            assertThat(ctx.getBeanNamesForType(ExerisRuntimeProperties.class)).hasSize(1);
            assertThat(ctx.getBeanNamesForType(ExerisSpringConfigProvider.class)).hasSize(1);
            assertThat(ctx.getBeanNamesForType(ExerisRuntimeLifecycle.class)).hasSize(1);
        }
    }

    @Test
    void exerisIsEnabled_byDefault() {
        try (var ctx = createContext(Map.of("exeris.runtime.auto-start", "false"))) {
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
        try (var ctx = createContext(Map.of("exeris.runtime.auto-start", "false"))) {
            ExerisRuntimeProperties props = ctx.getBean(ExerisRuntimeProperties.class);
            assertThat(props.web().mode()).isEqualTo(ExerisRuntimeProperties.Mode.PURE);
        }
    }

    @Test
    void configProviderPriority_is150() {
        try (var ctx = createContext(Map.of("exeris.runtime.auto-start", "false"))) {
            ExerisSpringConfigProvider provider = ctx.getBean(ExerisSpringConfigProvider.class);
            assertThat(provider.priority()).isEqualTo(150);
        }
    }

    @Test
    void noServletContainerOnClasspath() {
        assertThat(isClassPresent("org.apache.catalina.startup.Tomcat"))
                .as("Tomcat must not be on the classpath")
                .isFalse();
        assertThat(isClassPresent("io.undertow.Undertow"))
                .as("Undertow must not be on the classpath")
                .isFalse();
        assertThat(isClassPresent("org.eclipse.jetty.server.Server"))
                .as("Jetty must not be on the classpath")
                .isFalse();
    }

    @Test
    void lifecycleStartup_withHttpHandlerOverride_doesNotFailFromUnboundScopedValue() throws Exception {
        int port = reserveLoopbackPort();
        withKernelHttpSystemProperties(port, () -> {
            try (var ctx = createContext(
                    Map.of(
                            "exeris.runtime.auto-start", "false",
                            "exeris.runtime.network.port", Integer.toString(port),
                            "exeris.runtime.persistence.jdbc-url", "jdbc:h2:mem:exeris_bootstrap_test;DB_CLOSE_DELAY=-1",
                            "exeris.runtime.persistence.username", "sa",
                            "exeris.runtime.persistence.password", "",
                            "exeris.runtime.persistence.run-migrations", "false"
                    ),
                    LifecycleHandlerConfig.class)) {
                ExerisRuntimeLifecycle lifecycle = ctx.getBean(ExerisRuntimeLifecycle.class);
                ExecutorService executor = Executors.newSingleThreadExecutor();
                Future<?> startFuture = executor.submit(lifecycle::start);

                try {
                    awaitLifecycleStart(lifecycle, startFuture, Duration.ofSeconds(10));
                    assertThat(lifecycle.isRunning()).isTrue();
                } finally {
                    lifecycle.stop();
                    assertThatCode(() -> startFuture.get(10, TimeUnit.SECONDS)).doesNotThrowAnyException();
                    executor.shutdownNow();
                }
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        });
    }

    private static boolean isClassPresent(String className) {
        try {
            Class.forName(className, false, ExerisBootstrapIntegrationTest.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException _) {
            return false;
        }
    }

    private static void awaitLifecycleStart(ExerisRuntimeLifecycle lifecycle,
                                            Future<?> startFuture,
                                            Duration timeout) throws Exception {
        long deadlineNanos = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadlineNanos) {
            if (lifecycle.isRunning()) {
                return;
            }
            if (startFuture.isDone()) {
                startFuture.get();
                return;
            }
            LockSupport.parkNanos(Duration.ofMillis(50).toNanos());
        }
        throw new TimeoutException("Exeris lifecycle did not reach running state within " + timeout);
    }

    private static int reserveLoopbackPort() throws Exception {
        try (ServerSocket socket = new ServerSocket()) {
            socket.bind(new InetSocketAddress("127.0.0.1", 0));
            return socket.getLocalPort();
        }
    }

    private static void withKernelHttpSystemProperties(int port, Runnable action) {
        Map<String, String> previous = new LinkedHashMap<>();
        previous.put("exeris.http.mode", System.getProperty("exeris.http.mode"));
        previous.put("exeris.http.bindHost", System.getProperty("exeris.http.bindHost"));
        previous.put("exeris.http.port", System.getProperty("exeris.http.port"));
        previous.put("http.mode", System.getProperty("http.mode"));
        previous.put("http.bindHost", System.getProperty("http.bindHost"));
        previous.put("http.port", System.getProperty("http.port"));

        System.setProperty("exeris.http.mode", "SERVER");
        System.setProperty("exeris.http.bindHost", "127.0.0.1");
        System.setProperty("exeris.http.port", Integer.toString(port));
        System.setProperty("http.mode", "SERVER");
        System.setProperty("http.bindHost", "127.0.0.1");
        System.setProperty("http.port", Integer.toString(port));

        try {
            action.run();
        } finally {
            previous.forEach((key, value) -> {
                if (value == null) {
                    System.clearProperty(key);
                } else {
                    System.setProperty(key, value);
                }
            });
        }
    }

    private AnnotationConfigApplicationContext createContext(Map<String, Object> properties,
                                                             Class<?>... additionalConfigs) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(
                new MapPropertySource("testProps", new LinkedHashMap<>(properties)));
        context.register(ExerisRuntimeAutoConfiguration.class);
        if (additionalConfigs.length > 0) {
            context.register(additionalConfigs);
        }
        context.refresh();
        return context;
    }

    @Configuration(proxyBeanMethods = false)
    static class LifecycleHandlerConfig {

        @Bean
        HttpHandler lifecycleTestHandler() {
            return eu.exeris.kernel.spi.http.HttpExchange::request;
        }
    }
}
