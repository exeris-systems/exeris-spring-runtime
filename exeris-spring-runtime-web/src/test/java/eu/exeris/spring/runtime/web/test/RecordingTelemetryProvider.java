/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.web.test;

import eu.exeris.kernel.spi.telemetry.KernelEvent;
import eu.exeris.kernel.spi.telemetry.TelemetryConfig;
import eu.exeris.kernel.spi.telemetry.TelemetryProvider;
import eu.exeris.kernel.spi.telemetry.TelemetrySink;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public final class RecordingTelemetryProvider implements TelemetryProvider {

    private static final CopyOnWriteArrayList<KernelEvent> EVENTS = new CopyOnWriteArrayList<>();

    @Override
    public List<TelemetrySink> createSinks(TelemetryConfig config) {
        return List.of(new RecordingTelemetrySink());
    }

    @Override
    public String providerName() {
        return "recording-test-provider";
    }

    @Override
    public int priority() {
        return 50;
    }

    public static void clearEvents() {
        EVENTS.clear();
    }

    public static List<KernelEvent> recordedEvents() {
        return List.copyOf(EVENTS);
    }

    private static final class RecordingTelemetrySink implements TelemetrySink {

        @Override
        public void emit(KernelEvent event) {
            EVENTS.add(event);
        }

        @Override
        public void increment(String metric, long value) {
            // no-op for test capture
        }

        @Override
        public void gauge(String metric, long value) {
            // no-op for test capture
        }

        @Override
        public void latency(String metric, long value) {
            // no-op for test capture
        }

        @Override
        public String sinkName() {
            return "recording-test-sink";
        }

        @Override
        public void close() {
            // no-op for test capture
        }
    }
}