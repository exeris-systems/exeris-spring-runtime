/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.web.security;

import java.lang.management.ManagementFactory;

import com.sun.management.ThreadMXBean;
import org.junit.jupiter.api.Test;

import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpRoutePolicy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * ADR-063 obligation 2: {@code requirementFor} allocates nothing.
 *
 * <p>This is a contract requirement rather than an optimisation. The kernel calls it on the admission
 * path of <em>every</em> request, ahead of the dispatcher, so an allocation here is paid before a
 * request has been admitted — including by requests that are about to be refused. ADR-061 states the
 * requirement on the kernel side; this asserts this runtime's implementation honours it.
 *
 * <p>Measured with {@link com.sun.management.ThreadMXBean#getThreadAllocatedBytes(long)}, the same
 * instrument {@code ExerisDispatcherAllocationBaselineTest} uses.
 */
class RoutePolicyAllocationTest {

    private static final int WARMUP_ITERATIONS = 30_000;
    private static final int MEASURED_ITERATIONS = 30_000;

    /**
     * Not zero, deliberately. Per-thread allocation accounting attributes a small amount of unrelated
     * JVM bookkeeping to the measuring thread, so a hard zero would be flaky for reasons that have
     * nothing to do with this code. A budget this tight still fails on a single per-call allocation:
     * one substring of a short path is ~40 B, which over 30k iterations is three orders of magnitude
     * above this.
     */
    private static final double MAX_BYTES_PER_CALL = 0.5;

    @Test
    void requirementForAllocatesNothingPerCall() {
        ThreadMXBean threadMx = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        assumeTrue(threadMx.isThreadAllocatedMemorySupported(),
                "ThreadMXBean per-thread allocation accounting not supported on this JVM");

        HttpRoutePolicy policy = ExerisRoutePolicyCompiler.compile(ExerisHttpSecurity.create()
                .requestMatchers("/health", "/health/live", "/health/ready", "/db/ping", "/db/roundtrip")
                .permitAll()
                .requestMatchers(HttpMethod.GET, "/api/*/orders/**").hasAnyScope("orders:read")
                .requestMatchers(HttpMethod.POST, "/api/*/orders/**").hasAllScopes("orders:read", "orders:write")
                .anyRequest().authenticated());

        // Three shapes, so the measurement covers a literal hit, a wildcard hit through several
        // segments, and a miss that walks every rule before falling through to the unmatched answer.
        // The miss is the worst case and the one a scanner produces.
        String[] targets = {
                "/health",
                "/api/v1/orders/42/items?expand=all",
                "/no/rule/matches/this",
        };

        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            consume(policy, targets);
        }

        long tid = Thread.currentThread().threadId();
        long startBytes = threadMx.getThreadAllocatedBytes(tid);
        for (int i = 0; i < MEASURED_ITERATIONS; i++) {
            consume(policy, targets);
        }
        long endBytes = threadMx.getThreadAllocatedBytes(tid);

        long calls = (long) MEASURED_ITERATIONS * targets.length;
        double bytesPerCall = (endBytes - startBytes) / (double) calls;

        System.out.printf("requirementFor: %d calls, %d bytes total, %.4f B/call%n",
                calls, endBytes - startBytes, bytesPerCall);

        assertThat(bytesPerCall)
                .as("requirementFor must be allocation-free (ADR-063 obligation 2); measured %.4f B/call",
                        bytesPerCall)
                .isLessThanOrEqualTo(MAX_BYTES_PER_CALL);
    }

    /** Reads a field of the result so the call cannot be optimised away entirely. */
    private static void consume(HttpRoutePolicy policy, String[] targets) {
        for (String target : targets) {
            if (policy.requirementFor(HttpMethod.GET, target).kind() == null) {
                throw new AssertionError("unreachable");
            }
        }
    }
}
