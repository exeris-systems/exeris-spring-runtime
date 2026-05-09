/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.actuator;

import eu.exeris.kernel.spi.telemetry.EventLevel;
import eu.exeris.kernel.spi.telemetry.KernelEvent;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link ExerisActuatorTelemetryBridge}.
 */
class ExerisActuatorTelemetryBridgeTest {

    private ExerisActuatorTelemetryBridge bridge;
    private MeterRegistry registry;

    @BeforeEach
    void setUp() {
        bridge = new ExerisActuatorTelemetryBridge();
        registry = new SimpleMeterRegistry();
        bridge.bindTo(registry);
    }

    // =========================================================================
    // bindTo() — meter registration contract
    // =========================================================================

    @Test
    void bindTo_registersInfoCounter() {
        Gauge gauge = registry.find(ExerisActuatorTelemetryBridge.METRIC_EVENTS_INFO).gauge();
        assertThat(gauge).isNotNull();
        assertThat(gauge.value()).isZero();
    }

    @Test
    void bindTo_registersWarnCounter() {
        assertThat(registry.find(ExerisActuatorTelemetryBridge.METRIC_EVENTS_WARN).gauge()).isNotNull();
    }

    @Test
    void bindTo_registersErrorCounter() {
        assertThat(registry.find(ExerisActuatorTelemetryBridge.METRIC_EVENTS_ERROR).gauge()).isNotNull();
    }

    @Test
    void bindTo_registersFatalCounter() {
        assertThat(registry.find(ExerisActuatorTelemetryBridge.METRIC_EVENTS_FATAL).gauge()).isNotNull();
    }

    @Test
    void bindTo_rejectsNullRegistry() {
        ExerisActuatorTelemetryBridge freshBridge = new ExerisActuatorTelemetryBridge();
        assertThatThrownBy(() -> freshBridge.bindTo(null))
                .isInstanceOf(NullPointerException.class);
    }

    // =========================================================================
    // emit() — counter increment contract
    // =========================================================================

    @Test
    void emit_infoEvent_incrementsInfoCounter() {
        KernelEvent event = KernelEvent.info("EX-TEST-001", "test");
        bridge.emit(event);
        assertThat(registry.find(ExerisActuatorTelemetryBridge.METRIC_EVENTS_INFO).gauge().value())
                .isEqualTo(1.0);
    }

    @Test
    void emit_warnEvent_incrementsWarnCounter() {
        KernelEvent event = new KernelEvent("EX-TEST-002", EventLevel.WARN, Instant.now(), null, "test");
        bridge.emit(event);
        assertThat(registry.find(ExerisActuatorTelemetryBridge.METRIC_EVENTS_WARN).gauge().value())
                .isEqualTo(1.0);
    }

    @Test
    void emit_errorEvent_incrementsErrorCounter() {
        KernelEvent event = new KernelEvent("EX-TEST-003", EventLevel.ERROR, Instant.now(), null, "test");
        bridge.emit(event);
        assertThat(registry.find(ExerisActuatorTelemetryBridge.METRIC_EVENTS_ERROR).gauge().value())
                .isEqualTo(1.0);
    }

    @Test
    void emit_fatalEvent_incrementsFatalCounter() {
        KernelEvent event = new KernelEvent("EX-TEST-004", EventLevel.FATAL, Instant.now(), null, "test");
        bridge.emit(event);
        assertThat(registry.find(ExerisActuatorTelemetryBridge.METRIC_EVENTS_FATAL).gauge().value())
                .isEqualTo(1.0);
    }

    @Test
    void emit_multipleEvents_accumulatesCount() {
        bridge.emit(KernelEvent.info("EX-TEST-001", "test"));
        bridge.emit(KernelEvent.info("EX-TEST-001", "test"));
        bridge.emit(KernelEvent.info("EX-TEST-001", "test"));
        assertThat(registry.find(ExerisActuatorTelemetryBridge.METRIC_EVENTS_INFO).gauge().value())
                .isEqualTo(3.0);
    }

    @Test
    void emit_rejectsNullEvent() {
        assertThatThrownBy(() -> bridge.emit(null))
                .isInstanceOf(NullPointerException.class);
    }

    // =========================================================================
    // TelemetrySink identity contract
    // =========================================================================

    @Test
    void sinkName_returnsExpectedName() {
        assertThat(bridge.sinkName()).isEqualTo("exeris-micrometer-bridge");
    }

    @Test
    void close_doesNotThrow() {
        // close() is a no-op; must not throw
        bridge.close();
    }
}
