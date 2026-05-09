/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.web;

import java.lang.management.ManagementFactory;
import java.lang.reflect.Proxy;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.management.ThreadMXBean;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import eu.exeris.kernel.spi.http.HttpExchange;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpRequest;
import eu.exeris.kernel.spi.http.HttpResponse;
import eu.exeris.kernel.spi.http.HttpVersion;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure Mode allocation baseline for the dispatcher hot path.
 *
 * <p>Asserts a hard budget of {@value #ALLOCATION_BUDGET_BYTES_PER_DISPATCH} bytes mean
 * allocation per empty-body GET dispatch through {@link ExerisHttpDispatcher}. Latency is
 * measured for visibility in the test log but intentionally not asserted — CI hardware,
 * shared runners, JIT and GC pauses make per-dispatch latency thresholds flaky without
 * dedicated benchmarking infrastructure (JMH).
 *
 * <p>The companion {@link ExerisDispatcherRepeatedDispatchSmokeTest} stays budget-free
 * and verifies correctness under repeated dispatch. This test is the closure-hardening
 * counterpart referenced in {@code docs/phases/phase-1-web-ingress.md} (Phase 1c, item 10).
 *
 * <p>Measurement uses {@link com.sun.management.ThreadMXBean#getThreadAllocatedBytes(long)}
 * (HotSpot extension). On JVMs that do not support it, the test self-skips via
 * {@link Assumptions#assumeTrue(boolean, String)} rather than failing.
 */
class ExerisDispatcherAllocationBaselineTest {

    private static final long ALLOCATION_BUDGET_BYTES_PER_DISPATCH = 1024L;
    private static final int WARMUP_ITERATIONS = 30_000;
    private static final int MEASURED_ITERATIONS = 30_000;

    @Test
    void emptyBodyDispatch_meanAllocation_perRequest_doesNotExceedHardBudget() {
        ThreadMXBean threadMx = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        Assumptions.assumeTrue(threadMx.isThreadAllocatedMemorySupported(),
                "ThreadMXBean per-thread allocation accounting not supported on this JVM");
        if (!threadMx.isThreadAllocatedMemoryEnabled()) {
            threadMx.setThreadAllocatedMemoryEnabled(true);
        }

        ExerisRouteRegistry routes = ExerisRouteRegistry.builder()
                .register(HttpMethod.GET, "/alloc", request -> ExerisServerResponse.ok())
                .build();
        ExerisHttpDispatcher dispatcher = new ExerisHttpDispatcher(routes, new ExerisErrorMapper());
        TestExchange exchange = TestExchange.get(HttpMethod.GET, "/alloc", anyHttpVersion());

        // Warm up: let JIT compile the hot path so the measurement reflects steady state,
        // not interpreter / C1 transient allocations.
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            dispatcher.handle(exchange.proxy());
        }

        long tid = Thread.currentThread().threadId();
        long startBytes = threadMx.getThreadAllocatedBytes(tid);
        long startNanos = System.nanoTime();

        for (int i = 0; i < MEASURED_ITERATIONS; i++) {
            dispatcher.handle(exchange.proxy());
        }

        long endNanos = System.nanoTime();
        long endBytes = threadMx.getThreadAllocatedBytes(tid);

        long totalAllocatedBytes = endBytes - startBytes;
        double bytesPerDispatch = totalAllocatedBytes / (double) MEASURED_ITERATIONS;
        long elapsedNanos = endNanos - startNanos;
        double meanLatencyNanos = elapsedNanos / (double) MEASURED_ITERATIONS;

        // Visibility-only: surfaced in test log, not asserted. Useful for trend tracking
        // across commits without committing to a brittle latency threshold.
        System.out.printf(Locale.ROOT,
                "ExerisDispatcherAllocationBaseline: iterations=%d total_alloc=%d B mean=%.1f B/dispatch mean_latency=%.1f ns%n",
                MEASURED_ITERATIONS, totalAllocatedBytes, bytesPerDispatch, meanLatencyNanos);

        assertThat(bytesPerDispatch)
                .as("Pure Mode empty-body GET dispatch must allocate ≤ %d B/req on average; observed %.1f B/req over %d iterations (total %d B)",
                        ALLOCATION_BUDGET_BYTES_PER_DISPATCH,
                        bytesPerDispatch,
                        MEASURED_ITERATIONS,
                        totalAllocatedBytes)
                .isLessThanOrEqualTo((double) ALLOCATION_BUDGET_BYTES_PER_DISPATCH);
        assertThat(exchange.response())
                .as("Dispatcher must have produced a response on the measurement loop")
                .isNotNull();
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
                    // probe next candidate
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
            for (var constructor : HttpRequest.class.getDeclaredConstructors()) {
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
                    // probe next constructor shape
                }
            }
            throw new IllegalStateException("Unable to construct HttpRequest for allocation baseline test");
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
