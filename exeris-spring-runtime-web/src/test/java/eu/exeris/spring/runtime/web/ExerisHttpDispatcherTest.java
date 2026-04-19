/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.web;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.exceptions.http.HttpException;
import eu.exeris.kernel.spi.http.HttpExchange;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpRequest;
import eu.exeris.kernel.spi.http.HttpResponse;
import eu.exeris.kernel.spi.http.HttpVersion;
import eu.exeris.kernel.spi.telemetry.KernelEvent;
import eu.exeris.kernel.spi.telemetry.TelemetrySink;

class ExerisHttpDispatcherTest {

    @BeforeEach
    @SuppressWarnings("unused")
    void resetWarningFlags() throws Exception {
        resetAtomicBoolean("FALLBACK_WARNING_LOGGED");
        resetAtomicBoolean("FALLBACK_RESOLUTION_WARNING_LOGGED");
    }

    @Test
    void fallbackTelemetrySinks_bindOnlyWhenKernelScopeIsUnbound() throws Exception {
        AtomicInteger supplierCalls = new AtomicInteger();
        TelemetrySink fallbackSink = new NamedTelemetrySink("fallback");
        AtomicReference<List<TelemetrySink>> observedSinks = new AtomicReference<>();

        ExerisRequestHandler handler = request -> {
            observedSinks.set(KernelProviders.TELEMETRY_SINKS.isBound()
                    ? KernelProviders.TELEMETRY_SINKS.get()
                    : List.of());
            return ExerisServerResponse.ok().body("ok");
        };

        ExerisHttpDispatcher dispatcher = new ExerisHttpDispatcher(
                routeRegistry(handler),
                new ExerisErrorMapper(),
                () -> {
                    supplierCalls.incrementAndGet();
                    return List.of(fallbackSink);
                });

        dispatcher.handle(TestExchange.get(HttpMethod.GET, "/telemetry", anyHttpVersion()).proxy());

        assertThat(supplierCalls).hasValue(1);
        assertThat(observedSinks.get()).containsExactly(fallbackSink);
    }

    @Test
    void existingTelemetryBindingTakesPrecedenceOverFallbackSupplier() {
        AtomicInteger supplierCalls = new AtomicInteger();
        TelemetrySink existingSink = new NamedTelemetrySink("existing");
        TelemetrySink fallbackSink = new NamedTelemetrySink("fallback");
        AtomicReference<List<TelemetrySink>> observedSinks = new AtomicReference<>();

        ExerisRequestHandler handler = request -> {
            observedSinks.set(KernelProviders.TELEMETRY_SINKS.get());
            return ExerisServerResponse.ok().body("ok");
        };

        ExerisHttpDispatcher dispatcher = new ExerisHttpDispatcher(
                routeRegistry(handler),
                new ExerisErrorMapper(),
                () -> {
                    supplierCalls.incrementAndGet();
                    return List.of(fallbackSink);
                });

        ScopedValue.where(KernelProviders.TELEMETRY_SINKS, List.of(existingSink)).run(() -> {
            try {
                dispatcher.handle(TestExchange.get(HttpMethod.GET, "/telemetry", anyHttpVersion()).proxy());
            } catch (HttpException ex) {
                throw new RuntimeException(ex);
            }
        });

        assertThat(supplierCalls).hasValue(0);
        assertThat(observedSinks.get()).containsExactly(existingSink);
    }

    @Test
    void nullFallbackTelemetryPath_isHandledSafely() throws Exception {
        AtomicReference<Boolean> telemetryBound = new AtomicReference<>();

        ExerisRequestHandler handler = request -> {
            telemetryBound.set(KernelProviders.TELEMETRY_SINKS.isBound());
            return ExerisServerResponse.ok().body("ok");
        };

        ExerisHttpDispatcher dispatcher = new ExerisHttpDispatcher(
                routeRegistry(handler),
                new ExerisErrorMapper(),
                (java.util.function.Supplier<List<TelemetrySink>>) null);

        dispatcher.handle(TestExchange.get(HttpMethod.GET, "/telemetry", anyHttpVersion()).proxy());

        assertThat(telemetryBound.get()).isFalse();
    }

    @Test
    void fallbackTelemetryBinding_logsWarningOnlyOnce_whenFallbackSinksAreActuallyUsed() throws Exception {
        Logger julLogger = Logger.getLogger(ExerisHttpDispatcher.class.getName());
        Level previousLevel = julLogger.getLevel();
        julLogger.setLevel(Level.ALL);
        CapturingLogHandler logHandler = new CapturingLogHandler();

        try (logHandler) {
            julLogger.addHandler(logHandler);

            ExerisRequestHandler handler = request -> ExerisServerResponse.ok().body("ok");
            ExerisHttpDispatcher dispatcher = new ExerisHttpDispatcher(
                    routeRegistry(handler),
                    new ExerisErrorMapper(),
                    () -> List.of(new NamedTelemetrySink("fallback")));

            dispatcher.handle(TestExchange.get(HttpMethod.GET, "/telemetry", anyHttpVersion()).proxy());
            dispatcher.handle(TestExchange.get(HttpMethod.GET, "/telemetry", anyHttpVersion()).proxy());

            List<String> warnings = logHandler.messages().stream()
                    .filter(message -> message.contains("fallback telemetry sinks") || message.contains("telemetry bootstrap"))
                    .toList();

            assertThat(warnings).hasSize(1);
            assertThat(warnings.get(0))
                    .contains("tests")
                    .contains("compat")
                    .contains("telemetry bootstrap");
        } finally {
            julLogger.removeHandler(logHandler);
            julLogger.setLevel(previousLevel);
        }
    }

    @Test
    void fallbackTelemetrySupplierFailure_doesNotBreakRequestHandling_orRetrySupplierResolution() throws Exception {
        AtomicInteger handlerInvocations = new AtomicInteger();
        AtomicInteger supplierCalls = new AtomicInteger();
        TestExchange firstExchange = TestExchange.get(HttpMethod.GET, "/telemetry", anyHttpVersion());
        TestExchange secondExchange = TestExchange.get(HttpMethod.GET, "/telemetry", anyHttpVersion());

        ExerisRequestHandler handler = request -> {
            handlerInvocations.incrementAndGet();
            return ExerisServerResponse.ok().body("ok");
        };

        ExerisHttpDispatcher dispatcher = new ExerisHttpDispatcher(
                routeRegistry(handler),
                new ExerisErrorMapper(),
                () -> {
                    supplierCalls.incrementAndGet();
                    throw new IllegalStateException("fallback sink creation failed");
                });

        dispatcher.handle(firstExchange.proxy());
        dispatcher.handle(secondExchange.proxy());

        assertThat(supplierCalls).hasValue(1);
        assertThat(handlerInvocations).hasValue(2);
        assertThat(firstExchange.response()).isNotNull();
        assertThat(secondExchange.response()).isNotNull();
    }

    private static ExerisRouteRegistry routeRegistry(ExerisRequestHandler handler) {
        return ExerisRouteRegistry.builder()
                .register(HttpMethod.GET, "/telemetry", handler)
                .build();
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
                } catch (IllegalAccessException _) {
                    // Ignore inaccessible constants while probing a usable test HttpVersion.
                }
            }
        }
        throw new IllegalStateException("Unable to obtain any HttpVersion constant for test exchange");
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
                } catch (ReflectiveOperationException | IllegalArgumentException _) {
                    // Ignore constructor shapes that do not match this lightweight test request.
                }
            }
            throw new IllegalStateException("Unable to construct HttpRequest for test exchange");
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

    private static void resetAtomicBoolean(String fieldName) throws Exception {
        Field field = ExerisHttpDispatcher.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        ((java.util.concurrent.atomic.AtomicBoolean) field.get(null)).set(false);
    }

    private static final class CapturingLogHandler extends Handler implements AutoCloseable {

        private final List<String> messages = new ArrayList<>();

        @Override
        public void publish(LogRecord logEntry) {
            if (logEntry != null && logEntry.getLevel().intValue() >= Level.WARNING.intValue()) {
                messages.add(logEntry.getMessage());
            }
        }

        @Override
        public void flush() {
            // No-op for in-memory capture.
        }

        @Override
        public void close() {
            // No-op for in-memory capture.
        }

        List<String> messages() {
            return List.copyOf(messages);
        }
    }

    private record NamedTelemetrySink(String sinkName) implements TelemetrySink {
        @Override
        public void emit(KernelEvent event) {
            // No-op for test telemetry binding checks.
        }

        @Override
        public void increment(String metric, long value) {
            // No-op for test telemetry binding checks.
        }

        @Override
        public void gauge(String metric, long value) {
            // No-op for test telemetry binding checks.
        }

        @Override
        public void latency(String metric, long value) {
            // No-op for test telemetry binding checks.
        }

        @Override
        public void close() {
            // No-op for test telemetry binding checks.
        }
    }
}
