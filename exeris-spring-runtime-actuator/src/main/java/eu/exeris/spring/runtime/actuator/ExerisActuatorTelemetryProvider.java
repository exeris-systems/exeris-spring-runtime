/*
 * Copyright (C) 2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.spring.runtime.actuator;

import eu.exeris.kernel.spi.telemetry.TelemetryConfig;
import eu.exeris.kernel.spi.telemetry.TelemetryProvider;
import eu.exeris.kernel.spi.telemetry.TelemetrySink;

import java.util.List;
import java.util.Objects;

/**
 * {@link TelemetryProvider} whose single sink is the {@link ExerisActuatorTelemetryBridge}.
 *
 * <p>This is how the bridge joins the sink list the Pure Mode dispatcher binds to
 * {@code KernelProviders.TELEMETRY_SINKS} for the duration of each request, which collects its
 * sinks from {@code TelemetryProvider} beans. It is a Spring bean only: it is not registered with
 * {@link java.util.ServiceLoader}, so the kernel never discovers it, and the bridge stays a
 * Spring-side object the kernel sees only through the {@link TelemetrySink} interface.
 *
 * <p>The returned list holds the shared bridge instance, so every request's events accumulate in
 * the gauges the bridge registered.
 *
 * @since 0.8
 */
public final class ExerisActuatorTelemetryProvider implements TelemetryProvider {

    private final List<TelemetrySink> sinks;

    /**
     * Creates a provider that contributes {@code bridge} as its only sink.
     *
     * @param bridge the Micrometer bridge the sink list carries; must not be {@code null}
     */
    public ExerisActuatorTelemetryProvider(ExerisActuatorTelemetryBridge bridge) {
        this.sinks = List.of(Objects.requireNonNull(bridge, "bridge must not be null"));
    }

    /**
     * Returns the bridge as the only sink. The configuration selects nothing here: the bridge has no
     * JFR, console or file variant.
     *
     * @param config ignored
     * @return an immutable one-element list holding the bridge
     */
    @Override
    public List<TelemetrySink> createSinks(TelemetryConfig config) {
        return sinks;
    }

    @Override
    public String providerName() {
        return "exeris-spring-actuator";
    }
}
