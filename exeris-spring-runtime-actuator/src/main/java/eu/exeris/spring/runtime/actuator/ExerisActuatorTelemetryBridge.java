/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.actuator;

import eu.exeris.kernel.spi.telemetry.KernelEvent;
import eu.exeris.kernel.spi.telemetry.TelemetrySink;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Micrometer {@link MeterBinder} that bridges Exeris kernel telemetry events to
 * Micrometer metrics.
 *
 * <p>Also implements {@link TelemetrySink} so that in non-kernel-scope paths
 * (testkit, development without a live kernel) Exeris telemetry calls are forwarded
 * to the Micrometer registry through this bridge.
 *
 * <h2>ScopedValue Contract</h2>
 * <p>{@link #bindTo(MeterRegistry)} is called by Micrometer on the Spring context startup
 * thread — NOT on a kernel-owned Virtual Thread. No {@code KernelProviders} ScopedValue
 * is bound at this point. This method MUST NOT perform any ScopedValue reads.
 * All meter registrations use Gauge functions pointing to in-memory {@link AtomicLong}
 * fields that are updated purely from incoming {@link TelemetrySink} calls.
 *
 * <h2>Mode</h2>
 * <p>Pure Mode diagnostic boundary — operational visibility only. No data-plane execution.
 *
 * <h2>Dynamic metric names ({@link #increment}, {@link #gauge}, {@link #latency})</h2>
 * <p>Arbitrary metric names emitted by kernel subsystems are not yet bridged to
 * Micrometer. Those methods are reserved for a future dynamic counter/gauge bridge
 * that will require explicit registration of known Exeris metric name constants.
 *
 * @since 0.1.0
 */
public final class ExerisActuatorTelemetryBridge implements MeterBinder, TelemetrySink {

    static final String METRIC_EVENTS_INFO  = "exeris.events.info";
    static final String METRIC_EVENTS_WARN  = "exeris.events.warn";
    static final String METRIC_EVENTS_ERROR = "exeris.events.error";
    static final String METRIC_EVENTS_FATAL = "exeris.events.fatal";

    private final AtomicLong infoCount  = new AtomicLong();
    private final AtomicLong warnCount  = new AtomicLong();
    private final AtomicLong errorCount = new AtomicLong();
    private final AtomicLong fatalCount = new AtomicLong();

    // =========================================================================
    // MeterBinder
    // =========================================================================

    /**
     * Registers Exeris event-level counters as Micrometer Gauge meters.
     *
     * <p><strong>Constraint:</strong> this method MUST NOT read any {@code KernelProviders}
     * ScopedValue. It runs on the Spring startup thread outside any kernel VT scope.
     *
     * @param registry the registry to bind to; must not be {@code null}
     */
    @Override
    public void bindTo(MeterRegistry registry) {
        Objects.requireNonNull(registry, "registry must not be null");
        Gauge.builder(METRIC_EVENTS_INFO, infoCount, AtomicLong::doubleValue)
                .description("Exeris kernel INFO event count since last restart")
                .register(registry);
        Gauge.builder(METRIC_EVENTS_WARN, warnCount, AtomicLong::doubleValue)
                .description("Exeris kernel WARN event count since last restart")
                .register(registry);
        Gauge.builder(METRIC_EVENTS_ERROR, errorCount, AtomicLong::doubleValue)
                .description("Exeris kernel ERROR event count since last restart")
                .register(registry);
        Gauge.builder(METRIC_EVENTS_FATAL, fatalCount, AtomicLong::doubleValue)
                .description("Exeris kernel FATAL event count since last restart")
                .register(registry);
    }

    // =========================================================================
    // TelemetrySink — receives events in non-kernel-scope fallback paths
    // =========================================================================

    @Override
    public void emit(KernelEvent event) {
        Objects.requireNonNull(event, "event must not be null");
        switch (event.level()) {
            case INFO  -> infoCount.incrementAndGet();
            case WARN  -> warnCount.incrementAndGet();
            case ERROR -> errorCount.incrementAndGet();
            case FATAL -> fatalCount.incrementAndGet();
        }
    }

    /**
     * Arbitrary increment calls from kernel subsystems are not currently bridged to
     * Micrometer. Reserved for a future dynamic counter bridge.
     */
    @Override
    public void increment(String name, long delta) {
        // no-op: dynamic counter bridge is out of scope for Seam 6
    }

    /**
     * Arbitrary gauge calls from kernel subsystems are not currently bridged to
     * Micrometer. Reserved for a future dynamic gauge bridge.
     */
    @Override
    public void gauge(String name, long value) {
        // no-op: dynamic gauge bridge is out of scope for Seam 6
    }

    /**
     * Latency samples from kernel subsystems are not currently bridged to
     * Micrometer. Reserved for a future latency histogram bridge.
     */
    @Override
    public void latency(String name, long nanoseconds) {
        // no-op: latency histogram bridge is out of scope for Seam 6
    }

    @Override
    public String sinkName() {
        return "exeris-micrometer-bridge";
    }

    @Override
    public void close() {
        // No resources to release.
    }
}
