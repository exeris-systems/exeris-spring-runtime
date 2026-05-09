/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.web;

import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.Locale;

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
        HttpRequest request = HttpRequest.noBody(HttpMethod.GET, "/alloc", HttpVersion.HTTP_1_1, List.of());
        TestExchange exchange = new TestExchange(request);

        // Warm up: let JIT compile the hot path so the measurement reflects steady state,
        // not interpreter / C1 transient allocations.
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            dispatcher.handle(exchange);
        }

        long tid = Thread.currentThread().threadId();
        long startBytes = threadMx.getThreadAllocatedBytes(tid);
        long startNanos = System.nanoTime();

        for (int i = 0; i < MEASURED_ITERATIONS; i++) {
            dispatcher.handle(exchange);
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

    /**
     * Minimal single-threaded {@link HttpExchange} used as the dispatcher's input/output sink.
     * A direct interface implementation rather than a {@link java.lang.reflect.Proxy} so the
     * baseline reflects production allocation cost rather than per-call {@code Object[]}
     * allocation in {@code Proxy.invoke}.
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
