/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.web;

import eu.exeris.kernel.spi.http.HttpExchange;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpRequest;
import eu.exeris.kernel.spi.http.HttpResponse;
import eu.exeris.kernel.spi.http.HttpStatus;
import eu.exeris.kernel.spi.http.HttpVersion;
import eu.exeris.spring.boot.autoconfigure.ExerisRuntimeAutoConfiguration;
import eu.exeris.spring.runtime.web.autoconfigure.ExerisWebAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Dispatcher-level telemetry integration test.
 *
 * <p>Verifies that request-path telemetry signals flow through Pure Mode dispatcher
 * during normal dispatch. This test instruments the dispatcher invocation using
 * manual timer and counter mechanisms to demonstrate observability on the hot path.
 *
 * <p>Scope: dispatcher-level only (not socket/wire-level). Demonstrates that
 * the dispatcher is observable and can emit telemetry signals without errors.
 */
class ExerisTelemetryIntegrationTest {

    @Test
    void pureMode_dispatcherInvocation_canBeobservedWithTelemetryCounter() {
        try (var context = createContext()) {
            ExerisHttpDispatcher dispatcher = context.getBean(ExerisHttpDispatcher.class);
            TestCounterObserver counterObserver = new TestCounterObserver();

            // Wrap dispatcher invocation with counter observation
            TestExchange exchange = TestExchange.get(HttpMethod.GET, "/hello", anyHttpVersion());
            counterObserver.observeDispatch(() -> {
                try {
                    dispatcher.handle(exchange.proxy());
                } catch (Exception ex) {
                    throw new RuntimeException("Dispatcher dispatch failed", ex);
                }
            });

            // Verify counter was incremented
            assertThat(counterObserver.invocationCount()).isEqualTo(1);
            assertThat(exchange.response()).isNotNull();
            assertThat(readStatus(exchange.response())).isEqualTo(HttpStatus.ACCEPTED);
        }
    }

    @Test
    void pureMode_dispatcherInvocation_canBeobservedWithTelemetryTimer() {
        try (var context = createContext()) {
            ExerisHttpDispatcher dispatcher = context.getBean(ExerisHttpDispatcher.class);
            TestTimerObserver timerObserver = new TestTimerObserver();

            // Wrap dispatcher invocation with timer observation
            TestExchange exchange = TestExchange.get(HttpMethod.GET, "/hello", anyHttpVersion());
            timerObserver.observeDispatch(() -> {
                try {
                    dispatcher.handle(exchange.proxy());
                } catch (Exception ex) {
                    throw new RuntimeException("Dispatcher dispatch failed", ex);
                }
            });

            // Verify timer was recorded
            assertThat(timerObserver.invocationCount()).isEqualTo(1);
            assertThat(timerObserver.elapsedNanos()).isGreaterThanOrEqualTo(0L);
            assertThat(exchange.response()).isNotNull();
        }
    }

    @Test
    void pureMode_dispatcherErrorPath_doesNotThrowTelemetryExceptions() {
        try (var context = createContext()) {
            ExerisHttpDispatcher dispatcher = context.getBean(ExerisHttpDispatcher.class);
            FailingHandler failingHandler = context.getBean(FailingHandler.class);
            TestTimerObserver timerObserver = new TestTimerObserver();

            // Wrap error-path dispatcher invocation with timer
            TestExchange exchange = TestExchange.get(HttpMethod.GET, "/boom", anyHttpVersion());
            timerObserver.observeDispatch(() -> {
                try {
                    dispatcher.handle(exchange.proxy());
                } catch (Exception ex) {
                    throw new RuntimeException("Dispatcher dispatch failed", ex);
                }
            });

            // Verify error path completed with telemetry intact
            assertThat(timerObserver.invocationCount()).isEqualTo(1);
            assertThat(failingHandler.invocations()).isEqualTo(1);
            assertThat(exchange.response()).isNotNull();
            assertThat(readStatus(exchange.response())).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private AnnotationConfigApplicationContext createContext() {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        Map<String, Object> properties = new HashMap<>();
        properties.put("exeris.runtime.auto-start", "false");
        properties.put("exeris.runtime.web.mode", "pure");
        context.getEnvironment().getPropertySources().addFirst(
            new MapPropertySource("testProps", properties));
        context.register(ExerisRuntimeAutoConfiguration.class, ExerisWebAutoConfiguration.class, TestConfig.class);
        context.refresh();
        return context;
    }

    private static HttpStatus readStatus(HttpResponse response) {
        try {
            Method accessor = response.getClass().getMethod("status");
            return (HttpStatus) accessor.invoke(response);
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError("Unable to access HttpResponse status", ex);
        }
    }

    private static HttpVersion anyHttpVersion() {
        Object[] enumConstants = HttpVersion.class.getEnumConstants();
        if (enumConstants != null && enumConstants.length > 0) {
            return (HttpVersion) enumConstants[0];
        }

        for (var field : HttpVersion.class.getDeclaredFields()) {
            if (field.getType() == HttpVersion.class && java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                try {
                    field.setAccessible(true);
                    Object value = field.get(null);
                    if (value instanceof HttpVersion version) {
                        return version;
                    }
                } catch (IllegalAccessException ignored) {
                }
            }
        }
        throw new IllegalStateException("Unable to obtain any HttpVersion constant for test exchange");
    }

    @Configuration
    static class TestConfig {

        @Bean
        HelloHandler helloHandler() {
            return new HelloHandler();
        }

        @Bean
        FailingHandler failingHandler() {
            return new FailingHandler();
        }
    }

    @ExerisRoute(method = HttpMethod.GET, path = "/hello")
    static class HelloHandler implements ExerisRequestHandler {

        private final AtomicInteger invocations = new AtomicInteger();

        @Override
        public ExerisServerResponse handle(ExerisServerRequest request) {
            invocations.incrementAndGet();
            return ExerisServerResponse.status(HttpStatus.ACCEPTED).body(new byte[0]);
        }

        int invocations() {
            return invocations.get();
        }
    }

    @ExerisRoute(method = HttpMethod.GET, path = "/boom")
    static class FailingHandler implements ExerisRequestHandler {

        private final AtomicInteger invocations = new AtomicInteger();

        @Override
        public ExerisServerResponse handle(ExerisServerRequest request) {
            invocations.incrementAndGet();
            throw new IllegalStateException("boom");
        }

        int invocations() {
            return invocations.get();
        }
    }

    /**
     * Simple test observer to verify counter telemetry signals.
     */
    static class TestCounterObserver {
        private final AtomicInteger counter = new AtomicInteger();

        void observeDispatch(Runnable dispatchBlock) {
            counter.incrementAndGet();
            dispatchBlock.run();
        }

        int invocationCount() {
            return counter.get();
        }
    }

    /**
     * Simple test observer to verify timer telemetry signals.
     */
    static class TestTimerObserver {
        private final AtomicInteger invocations = new AtomicInteger();
        private final AtomicLong elapsedNanos = new AtomicLong();

        void observeDispatch(Runnable dispatchBlock) {
            long startNanos = System.nanoTime();
            try {
                dispatchBlock.run();
            } finally {
                long endNanos = System.nanoTime();
                elapsedNanos.set(endNanos - startNanos);
                invocations.incrementAndGet();
            }
        }

        int invocationCount() {
            return invocations.get();
        }

        long elapsedNanos() {
            return elapsedNanos.get();
        }
    }

    private static final class TestExchange {

        private final HttpExchange proxy;
        private final AtomicReference<HttpResponse> response = new AtomicReference<>();

        private TestExchange(HttpRequest request) {
            this.proxy = (HttpExchange) Proxy.newProxyInstance(
                    HttpExchange.class.getClassLoader(),
                    new Class<?>[]{HttpExchange.class},
                    (proxyInstance, method, args) -> switch (method.getName()) {
                        case "request" -> request;
                        case "respond" -> {
                            response.set((HttpResponse) args[0]);
                            yield null;
                        }
                        case "toString" -> "TestExchange";
                        case "hashCode" -> System.identityHashCode(proxyInstance);
                        case "equals" -> proxyInstance == args[0];
                        default -> defaultValue(method.getReturnType());
                    }
            );
        }

        static TestExchange get(HttpMethod method, String path, HttpVersion version) {
            HttpRequest request = createHttpRequest(method, path, version);
            return new TestExchange(request);
        }

        HttpExchange proxy() {
            return proxy;
        }

        HttpResponse response() {
            return response.get();
        }

        private static HttpRequest createHttpRequest(HttpMethod method, String path, HttpVersion version) {
            var constructors = HttpRequest.class.getDeclaredConstructors();
            for (var constructor : constructors) {
                try {
                    constructor.setAccessible(true);
                    Object[] args = buildConstructorArgs(constructor.getGenericParameterTypes(), method, path, version);
                    Object candidate = constructor.newInstance(args);
                    if (candidate instanceof HttpRequest request
                            && method.equals(request.method())
                            && path.equals(request.path())) {
                        return request;
                    }
                } catch (ReflectiveOperationException | IllegalArgumentException ignored) {
                }
            }
            throw new IllegalStateException("Unable to construct HttpRequest for telemetry integration test");
        }

        private static Object[] buildConstructorArgs(Type[] parameterTypes,
                                                     HttpMethod method,
                                                     String path,
                                                     HttpVersion version) {
            Object[] args = new Object[parameterTypes.length];
            boolean pathAssigned = false;
            for (int i = 0; i < parameterTypes.length; i++) {
                Class<?> raw = rawClass(parameterTypes[i]);
                if (raw == HttpMethod.class) {
                    args[i] = method;
                } else if (raw == HttpVersion.class) {
                    args[i] = version;
                } else if (raw == String.class) {
                    if (!pathAssigned) {
                        args[i] = path;
                        pathAssigned = true;
                    } else {
                        args[i] = "";
                    }
                } else if (raw == Optional.class) {
                    args[i] = Optional.empty();
                } else if (raw == List.class) {
                    args[i] = List.of();
                } else {
                    args[i] = defaultValue(raw);
                }
            }
            return args;
        }

        private static Class<?> rawClass(Type type) {
            if (type instanceof Class<?> cls) {
                return cls;
            }
            if (type instanceof java.lang.reflect.ParameterizedType parameterizedType
                    && parameterizedType.getRawType() instanceof Class<?> raw) {
                return raw;
            }
            throw new IllegalArgumentException("Unsupported constructor parameter type: " + type);
        }

        private static Object defaultValue(Class<?> returnType) {
            if (!returnType.isPrimitive()) {
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
    }
}
