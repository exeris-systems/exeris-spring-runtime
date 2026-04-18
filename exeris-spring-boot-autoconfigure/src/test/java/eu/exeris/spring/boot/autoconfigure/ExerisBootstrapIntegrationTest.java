/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.boot.autoconfigure;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.LockSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MapPropertySource;

import eu.exeris.kernel.spi.http.HttpExchange;
import eu.exeris.kernel.spi.http.HttpHandler;

/**
 * Integration tests for the real Exeris boot and shutdown bridge inside a Spring context.
 *
 * <p>These assertions cover truthful Pure Mode bootstrap behavior: Spring bean wiring,
 * Exeris lifecycle coordination, clean stop behavior, and a servlet/reactive-free baseline.
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
    void noServletOrReactiveRuntimeOnClasspath() {
        assertThat(isClassPresent("org.apache.catalina.startup.Tomcat"))
                .as("Tomcat must not be on the classpath")
                .isFalse();
        assertThat(isClassPresent("io.undertow.Undertow"))
                .as("Undertow must not be on the classpath")
                .isFalse();
        assertThat(isClassPresent("org.eclipse.jetty.server.Server"))
                .as("Jetty must not be on the classpath")
                .isFalse();
        assertThat(isClassPresent("reactor.netty.http.server.HttpServer"))
                .as("Reactor Netty must not be on the classpath")
                .isFalse();
        assertThat(isClassPresent("org.springframework.web.reactive.DispatcherHandler"))
                .as("Spring WebFlux must not be on the classpath")
                .isFalse();
    }

    @Test
    void lifecycleStartup_timeoutUsesDedicatedLifecycleTimeout() {
        ExerisRuntimeProperties properties = new ExerisRuntimeProperties(
                true,
                false,
                new ExerisRuntimeProperties.WebProperties(ExerisRuntimeProperties.Mode.PURE),
                new ExerisRuntimeProperties.LifecycleProperties(1),
                new ExerisRuntimeProperties.ShutdownProperties(true, 30)
        );
        CountDownLatch propertyReadStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExerisRuntimeLifecycle lifecycle = new ExerisRuntimeLifecycle(
                properties,
                new ExerisSpringConfigProvider(blockingEnvironment(propertyReadStarted, release)),
                java.util.Optional.empty()
        );

        try {
            long startedAt = System.nanoTime();
            assertThatThrownBy(lifecycle::start)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("startup timed out");
            assertThat(Duration.ofNanos(System.nanoTime() - startedAt)).isLessThan(Duration.ofSeconds(5));
            assertThat(lifecycle.isRunning()).isFalse();
            assertThat(propertyReadStarted.await(2, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for blocking startup evidence", ex);
        } finally {
            release.countDown();
            lifecycle.stop();
        }
    }

    @Test
    void prepareBootstrap_withNullEnvironmentStillReservesSingleSlot() {
        ExerisSpringConfigProvider provider = new ExerisSpringConfigProvider(null);

        provider.prepareBootstrap();
        try {
            assertThatThrownBy(provider::prepareBootstrap)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("already prepared");
        } finally {
            provider.clearBootstrap();
        }

        assertThatCode(provider::prepareBootstrap).doesNotThrowAnyException();
        provider.clearBootstrap();
    }

    @Test
    void lifecycleStartup_withHttpHandlerOverride_doesNotFailFromUnboundScopedValue() throws Exception {
        int port = 0;
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
                    assertThatCode(() -> startFuture.get(10, TimeUnit.SECONDS)).doesNotThrowAnyException();
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

    @Test
    void lifecycleStart_remainsRunningUntilStopRequested() throws Exception {
        int port = 0;
        withKernelHttpSystemProperties(port, () -> {
            try (var ctx = createContext(
                    Map.of(
                            "exeris.runtime.auto-start", "false",
                            "exeris.runtime.network.port", Integer.toString(port),
                            "exeris.runtime.persistence.jdbc-url", "jdbc:h2:mem:exeris_bootstrap_scope_test;DB_CLOSE_DELAY=-1",
                            "exeris.runtime.persistence.username", "sa",
                            "exeris.runtime.persistence.password", "",
                            "exeris.runtime.persistence.run-migrations", "false"
                    ),
                    LifecycleHandlerConfig.class)) {
                ExerisRuntimeLifecycle lifecycle = ctx.getBean(ExerisRuntimeLifecycle.class);

                lifecycle.start();
                Thread runtimeThread = awaitRuntimeThread(Duration.ofSeconds(10));
                assertThat(lifecycle.isRunning()).isTrue();
                assertThat(runtimeThread).isNotNull();
                assertThat(runtimeThread.isAlive()).isTrue();

                lifecycle.stop();
                runtimeThread.join(Duration.ofSeconds(10).toMillis());
                assertThat(lifecycle.isRunning()).isFalse();
                assertThat(runtimeThread.isAlive()).isFalse();
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        });
    }

    @Test
    void lifecycleRunning_keepsNonDaemonBootThreadAlive() throws Exception {
        int port = 0;
        withKernelHttpSystemProperties(port, () -> {
            try (var ctx = createContext(
                    Map.of(
                            "exeris.runtime.auto-start", "false",
                            "exeris.runtime.network.port", Integer.toString(port),
                            "exeris.runtime.persistence.jdbc-url", "jdbc:h2:mem:exeris_bootstrap_thread_test;DB_CLOSE_DELAY=-1",
                            "exeris.runtime.persistence.username", "sa",
                            "exeris.runtime.persistence.password", "",
                            "exeris.runtime.persistence.run-migrations", "false"
                    ),
                    LifecycleHandlerConfig.class)) {
                ExerisRuntimeLifecycle lifecycle = ctx.getBean(ExerisRuntimeLifecycle.class);

                lifecycle.start();
                try {
                    assertThat(lifecycle.isRunning()).isTrue();
                    Thread runtimeThread = awaitRuntimeThread(Duration.ofSeconds(10));
                    assertThat(runtimeThread).isNotNull();
                    assertThat(runtimeThread.isAlive()).isTrue();
                    assertThat(runtimeThread.isDaemon()).isFalse();
                } finally {
                    lifecycle.stop();
                }
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        });
    }

    @Test
    void concurrentStopDuringStartup_leavesLifecycleStopped() throws Exception {
        int port = 0;
        withKernelHttpSystemProperties(port, () -> {
            try (var ctx = createContext(
                    Map.of(
                            "exeris.runtime.auto-start", "false",
                            "exeris.runtime.network.port", Integer.toString(port),
                            "exeris.runtime.persistence.jdbc-url", "jdbc:h2:mem:exeris_bootstrap_race_test;DB_CLOSE_DELAY=-1",
                            "exeris.runtime.persistence.username", "sa",
                            "exeris.runtime.persistence.password", "",
                            "exeris.runtime.persistence.run-migrations", "false"
                    ),
                    LifecycleHandlerConfig.class)) {
                ExerisRuntimeLifecycle lifecycle = ctx.getBean(ExerisRuntimeLifecycle.class);
                ExecutorService executor = Executors.newSingleThreadExecutor();
                Future<?> startFuture = executor.submit(lifecycle::start);

                try {
                    awaitLifecycleStartupAttempt(lifecycle, startFuture, Duration.ofSeconds(10));
                    lifecycle.stop();
                    assertThatCode(() -> startFuture.get(10, TimeUnit.SECONDS)).doesNotThrowAnyException();
                    assertThat(lifecycle.isRunning()).isFalse();
                } finally {
                    lifecycle.stop();
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

    private static Thread awaitRuntimeThread(Duration timeout) throws TimeoutException {
        long deadlineNanos = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadlineNanos) {
            Thread runtimeThread = findRuntimeThread();
            if (runtimeThread != null) {
                return runtimeThread;
            }
            LockSupport.parkNanos(Duration.ofMillis(10).toNanos());
        }
        throw new TimeoutException("Exeris lifecycle thread was not observed within " + timeout);
    }

    private static Thread findRuntimeThread() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(thread -> "exeris-runtime-lifecycle".equals(thread.getName()))
                .filter(Thread::isAlive)
                .findFirst()
                .orElse(null);
    }

    private static Environment blockingEnvironment(CountDownLatch propertyReadStarted,
                                                   CountDownLatch release) {
        return (Environment) Proxy.newProxyInstance(
                ExerisBootstrapIntegrationTest.class.getClassLoader(),
                new Class<?>[]{Environment.class},
                (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return switch (method.getName()) {
                            case "toString" -> "blocking-test-environment";
                            case "hashCode" -> System.identityHashCode(proxy);
                            case "equals" -> proxy == args[0];
                            default -> null;
                        };
                    }

                    propertyReadStarted.countDown();
                    try {
                        release.await();
                    } catch (InterruptedException _) {
                        Thread.currentThread().interrupt();
                    }
                    return defaultEnvironmentValue(method.getReturnType());
                }
        );
    }

    private static Object defaultEnvironmentValue(Class<?> returnType) {
        if (!returnType.isPrimitive()) {
            if (returnType == String[].class) {
                return new String[0];
            }
            return null;
        }
        if (returnType == boolean.class) {
            return false;
        }
        if (returnType == byte.class) {
            return (byte) 0;
        }
        if (returnType == short.class) {
            return (short) 0;
        }
        if (returnType == int.class) {
            return 0;
        }
        if (returnType == long.class) {
            return 0L;
        }
        if (returnType == float.class) {
            return 0f;
        }
        if (returnType == double.class) {
            return 0d;
        }
        if (returnType == char.class) {
            return '\0';
        }
        return null;
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

    private static void awaitLifecycleStartupAttempt(ExerisRuntimeLifecycle lifecycle,
                                                     Future<?> startFuture,
                                                     Duration timeout) throws Exception {
        long deadlineNanos = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadlineNanos) {
            if (findRuntimeThread() != null || lifecycle.isRunning()) {
                return;
            }
            if (startFuture.isDone()) {
                startFuture.get();
                return;
            }
            LockSupport.parkNanos(Duration.ofMillis(10).toNanos());
        }
        throw new TimeoutException("Exeris lifecycle did not begin startup within " + timeout);
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
            return LifecycleHandlerConfig::ignoreExchange;
        }

        private static void ignoreExchange(HttpExchange exchange) {
            Objects.requireNonNull(exchange, "exchange");
        }
    }
}
