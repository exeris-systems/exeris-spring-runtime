/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.web.compat;

import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.management.ThreadMXBean;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import eu.exeris.kernel.spi.http.HttpExchange;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpRequest;
import eu.exeris.kernel.spi.http.HttpResponse;
import eu.exeris.kernel.spi.http.HttpVersion;
import eu.exeris.spring.boot.autoconfigure.ExerisRuntimeLifecycle;
import eu.exeris.spring.boot.autoconfigure.ExerisRuntimeProperties;
import eu.exeris.spring.runtime.web.ExerisErrorMapper;
import eu.exeris.spring.runtime.web.ExerisHttpDispatcher;
import eu.exeris.spring.runtime.web.ExerisRouteRegistry;
import eu.exeris.spring.runtime.web.ExerisServerResponse;
import eu.exeris.spring.runtime.web.autoconfigure.ExerisCompatAutoConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Compatibility cost report for the Phase 2 MVC bridge — informational only.
 *
 * <p>Measures mean allocation per dispatch for the same logical request through:
 * <ul>
 *   <li>the Pure Mode dispatcher ({@link ExerisHttpDispatcher} + {@link ExerisRouteRegistry}),</li>
 *   <li>the Compatibility Mode dispatcher ({@link ExerisCompatDispatcher} + {@code @RestController}).</li>
 * </ul>
 *
 * <p>Logs the side-by-side numbers and their delta. <strong>There is no assertion on
 * the magnitude of the compatibility cost itself</strong> — Pure Mode keeps its hard
 * budget in {@code ExerisDispatcherAllocationBaselineTest}; this test exists to satisfy
 * ADR-011's obligation that "compatibility-mode allocation cost is documented, never
 * hidden". The output is the documentation. Sanity assertions only verify both
 * dispatchers actually produced a response.
 *
 * <p>Phase 2c closure-hardening counterpart referenced in
 * {@code docs/phases/phase-2-spring-compat.md} (Phase 2d, item 21).
 */
class ExerisCompatAllocationCostReportTest {

    private static final int WARMUP_ITERATIONS = 30_000;
    private static final int MEASURED_ITERATIONS = 30_000;

    @Test
    void emptyBodyDispatch_compatVsPure_compatibilityCostReport() {
        ThreadMXBean threadMx = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        Assumptions.assumeTrue(threadMx.isThreadAllocatedMemorySupported(),
                "ThreadMXBean per-thread allocation accounting not supported on this JVM");
        if (!threadMx.isThreadAllocatedMemoryEnabled()) {
            threadMx.setThreadAllocatedMemoryEnabled(true);
        }

        ExerisRouteRegistry pureRoutes = ExerisRouteRegistry.builder()
                .register(HttpMethod.GET, "/cost", request -> ExerisServerResponse.ok())
                .build();
        ExerisHttpDispatcher pureDispatcher = new ExerisHttpDispatcher(pureRoutes, new ExerisErrorMapper());

        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(
                    new MapPropertySource("testProps", Map.of("exeris.runtime.web.mode", "compatibility")));
            context.register(ExerisCompatAutoConfiguration.class);
            context.register(CostReportConfig.class);
            context.refresh();
            ExerisCompatDispatcher compatDispatcher = context.getBean(ExerisCompatDispatcher.class);

            TestExchange pureExchange = TestExchange.get(HttpMethod.GET, "/cost", HttpVersion.HTTP_1_1);
            TestExchange compatExchange = TestExchange.get(HttpMethod.GET, "/cost", HttpVersion.HTTP_1_1);

            for (int i = 0; i < WARMUP_ITERATIONS; i++) {
                pureDispatcher.handle(pureExchange.proxy());
            }
            for (int i = 0; i < WARMUP_ITERATIONS; i++) {
                compatDispatcher.handle(compatExchange.proxy());
            }

            long tid = Thread.currentThread().threadId();

            long pureStart = threadMx.getThreadAllocatedBytes(tid);
            for (int i = 0; i < MEASURED_ITERATIONS; i++) {
                pureDispatcher.handle(pureExchange.proxy());
            }
            long pureEnd = threadMx.getThreadAllocatedBytes(tid);
            long pureTotal = pureEnd - pureStart;
            double pureMean = pureTotal / (double) MEASURED_ITERATIONS;

            long compatStart = threadMx.getThreadAllocatedBytes(tid);
            for (int i = 0; i < MEASURED_ITERATIONS; i++) {
                compatDispatcher.handle(compatExchange.proxy());
            }
            long compatEnd = threadMx.getThreadAllocatedBytes(tid);
            long compatTotal = compatEnd - compatStart;
            double compatMean = compatTotal / (double) MEASURED_ITERATIONS;

            double overhead = compatMean - pureMean;
            double ratio = pureMean > 0 ? compatMean / pureMean : Double.POSITIVE_INFINITY;

            System.out.printf(Locale.ROOT,
                    "ExerisCompatAllocationCostReport: iterations=%d pure=%.1f B/dispatch compat=%.1f B/dispatch overhead=%.1f B/dispatch ratio=%.2fx%n",
                    MEASURED_ITERATIONS, pureMean, compatMean, overhead, ratio);

            assertThat(pureExchange.response())
                    .as("Pure dispatcher must have produced a response on the measurement loop")
                    .isNotNull();
            assertThat(compatExchange.response())
                    .as("Compat dispatcher must have produced a response on the measurement loop")
                    .isNotNull();
        }
    }

    @Configuration
    static class CostReportConfig {

        @Bean
        ExerisRuntimeLifecycle exerisRuntimeLifecycle() {
            ExerisRuntimeProperties properties = new ExerisRuntimeProperties(
                    true,
                    false,
                    new ExerisRuntimeProperties.WebProperties(ExerisRuntimeProperties.Mode.COMPATIBILITY),
                    new ExerisRuntimeProperties.LifecycleProperties(),
                    new ExerisRuntimeProperties.ShutdownProperties());
            ExerisRuntimeLifecycle lifecycle = new ExerisRuntimeLifecycle(properties, null, Optional.empty());
            forceRunning(lifecycle);
            return lifecycle;
        }

        @Bean
        CostReportController costReportController() {
            return new CostReportController();
        }

        private static void forceRunning(ExerisRuntimeLifecycle lifecycle) {
            try {
                var running = ExerisRuntimeLifecycle.class.getDeclaredField("running");
                running.setAccessible(true);
                running.setBoolean(lifecycle, true);
            } catch (ReflectiveOperationException ex) {
                throw new IllegalStateException("Unable to mark ExerisRuntimeLifecycle as running for cost report", ex);
            }
        }
    }

    @RestController
    static class CostReportController {

        @GetMapping("/cost")
        String cost() {
            return "ok";
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
            return new TestExchange(createHttpRequest(method, path, version));
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
            throw new IllegalStateException("Unable to construct HttpRequest for compat cost report");
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
