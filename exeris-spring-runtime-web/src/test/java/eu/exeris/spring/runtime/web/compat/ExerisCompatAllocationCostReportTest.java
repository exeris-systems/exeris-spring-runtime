/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.web.compat;

import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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
import eu.exeris.spring.runtime.web.ExerisErrorMapper;
import eu.exeris.spring.runtime.web.ExerisHttpDispatcher;
import eu.exeris.spring.runtime.web.ExerisRouteRegistry;
import eu.exeris.spring.runtime.web.ExerisServerResponse;
import eu.exeris.spring.runtime.web.autoconfigure.ExerisCompatAutoConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Compatibility cost report for the Phase 2 MVC bridge — informational only.
 *
 * <p>Measures mean allocation per dispatch for the same logical empty-body GET through:
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
 *
 * <p>Test scaffolding mirrors {@code ExerisDispatcherAllocationBaselineTest}: a direct
 * {@link HttpExchange} interface implementation (not {@link java.lang.reflect.Proxy})
 * and the {@link HttpRequest#noBody(HttpMethod, String, HttpVersion, java.util.List)}
 * factory. This keeps the Pure baseline reading consistent with the Phase 1 baseline test.
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

            HttpRequest request = HttpRequest.noBody(HttpMethod.GET, "/cost", HttpVersion.HTTP_1_1, List.of());
            TestExchange pureExchange = new TestExchange(request);
            TestExchange compatExchange = new TestExchange(request);

            for (int i = 0; i < WARMUP_ITERATIONS; i++) {
                pureDispatcher.handle(pureExchange);
            }
            for (int i = 0; i < WARMUP_ITERATIONS; i++) {
                compatDispatcher.handle(compatExchange);
            }

            long tid = Thread.currentThread().threadId();

            long pureStart = threadMx.getThreadAllocatedBytes(tid);
            for (int i = 0; i < MEASURED_ITERATIONS; i++) {
                pureDispatcher.handle(pureExchange);
            }
            long pureEnd = threadMx.getThreadAllocatedBytes(tid);
            long pureTotal = pureEnd - pureStart;
            double pureMean = pureTotal / (double) MEASURED_ITERATIONS;

            long compatStart = threadMx.getThreadAllocatedBytes(tid);
            for (int i = 0; i < MEASURED_ITERATIONS; i++) {
                compatDispatcher.handle(compatExchange);
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

    /**
     * Test config for the Compat half of the report. Deliberately does NOT register an
     * {@code ExerisRuntimeLifecycle} bean: {@link ExerisCompatAutoConfiguration} does
     * not declare a dependency on it (no {@code @ConditionalOnBean}, no {@code @Autowired}),
     * so wiring the lifecycle here would be vestigial — and the prior practice of forcing
     * its private {@code running} flag via reflection in test setup is avoided entirely.
     */
    @Configuration
    static class CostReportConfig {

        @Bean
        CostReportController costReportController() {
            return new CostReportController();
        }
    }

    @RestController
    static class CostReportController {

        @GetMapping("/cost")
        String cost() {
            return "ok";
        }
    }

    /**
     * Minimal single-threaded {@link HttpExchange}. Mirrors the scaffolding shape used by
     * {@code ExerisDispatcherAllocationBaselineTest} so the Pure baseline reading stays
     * consistent across the two tests.
     */
    private static final class TestExchange implements HttpExchange {

        private final HttpRequest request;
        private HttpResponse response;

        TestExchange(HttpRequest request) {
            this.request = request;
        }

        @Override
        public HttpRequest request() {
            return request;
        }

        @Override
        public void respond(HttpResponse response) {
            this.response = response;
        }

        HttpResponse response() {
            return response;
        }
    }
}
