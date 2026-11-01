/*
 * Copyright (C) 2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
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
import eu.exeris.kernel.spi.telemetry.KernelEvent;
import eu.exeris.kernel.spi.telemetry.TelemetrySink;

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
 * and verifies correctness under repeated dispatch. This test validates the hot-path allocation budget.
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
        assertDispatchWithinBudget(new ExerisHttpDispatcher(routes(), new ExerisErrorMapper()), "no sinks");
    }

    /**
     * The same budget holds when the dispatcher binds a telemetry sink list around the handler, which
     * is every request once a {@code TelemetryProvider} bean such as the actuator bridge's is present.
     */
    @Test
    void emptyBodyDispatch_withTelemetrySinksBound_meanAllocation_perRequest_doesNotExceedHardBudget() {
        List<TelemetrySink> sinks = List.of(new IdleSink());
        assertDispatchWithinBudget(
                new ExerisHttpDispatcher(routes(), new ExerisErrorMapper(), sinks), "telemetry sinks bound");
    }

    private static ExerisRouteRegistry routes() {
        return ExerisRouteRegistry.builder()
                .register(HttpMethod.GET, "/alloc", request -> ExerisServerResponse.ok())
                .build();
    }

    private static void assertDispatchWithinBudget(ExerisHttpDispatcher dispatcher, String variant) {
        ThreadMXBean threadMx = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        Assumptions.assumeTrue(threadMx.isThreadAllocatedMemorySupported(),
                "ThreadMXBean per-thread allocation accounting not supported on this JVM");
        if (!threadMx.isThreadAllocatedMemoryEnabled()) {
            threadMx.setThreadAllocatedMemoryEnabled(true);
        }

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
                "ExerisDispatcherAllocationBaseline[%s]: iterations=%d total_alloc=%d B mean=%.1f B/dispatch mean_latency=%.1f ns%n",
                variant, MEASURED_ITERATIONS, totalAllocatedBytes, bytesPerDispatch, meanLatencyNanos);

        assertThat(bytesPerDispatch)
                .as("Pure Mode empty-body GET dispatch (%s) must allocate ≤ %d B/req on average; observed %.1f B/req over %d iterations (total %d B)",
                        variant,
                        ALLOCATION_BUDGET_BYTES_PER_DISPATCH,
                        bytesPerDispatch,
                        MEASURED_ITERATIONS,
                        totalAllocatedBytes)
                .isLessThanOrEqualTo((double) ALLOCATION_BUDGET_BYTES_PER_DISPATCH);
        assertThat(exchange.response())
                .as("Dispatcher must have produced a response on the measurement loop")
                .isNotNull();
    }

    /** A sink the handler never emits to; it only has to be present in the bound list. */
    private static final class IdleSink implements TelemetrySink {

        @Override
        public void emit(KernelEvent event) {
        }

        @Override
        public void increment(String name, long delta) {
        }

        @Override
        public void gauge(String name, long value) {
        }

        @Override
        public void latency(String name, long nanoseconds) {
        }

        @Override
        public String sinkName() {
            return "allocation-baseline";
        }

        @Override
        public void close() {
        }
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
