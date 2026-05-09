/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.actuator;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import eu.exeris.spring.boot.autoconfigure.ExerisRuntimeAutoConfiguration;
import eu.exeris.spring.boot.autoconfigure.ExerisRuntimeLifecycle;
import eu.exeris.spring.boot.autoconfigure.ExerisRuntimeProperties;
import eu.exeris.spring.runtime.actuator.compat.ExerisCompatibilityActuatorController;

/**
 * Autoconfiguration for Exeris Spring Runtime actuator integration.
 *
 * <p>Registers:
 * <ul>
 *   <li>{@link ExerisRuntimeHealthIndicator} — lifecycle liveness indicator (always,
 *       when Spring Boot Actuator is on the classpath)
 *   <li>{@link ExerisCompatibilityActuatorController} — compatibility-mode HTTP diagnostics
 *       bridge for [PBM]-style health checks without requiring servlet or reactive ownership
 *   <li>{@link ExerisActuatorTelemetryBridge} — Micrometer event-level counter bridge
 *       (only when {@code io.micrometer.core.instrument.MeterRegistry} is present)
 * </ul>
 *
 * <h2>What This Does NOT Do</h2>
 * <p>This class does not own any runtime execution path, transport logic, request
 * processing, or transaction management. It is limited to operational visibility:
 * health, info, and metrics exposure.
 *
 * @since 0.1.0
 */
@AutoConfiguration(after = ExerisRuntimeAutoConfiguration.class)
@ConditionalOnClass(HealthIndicator.class)
@ConditionalOnProperty(prefix = "exeris.runtime", name = "enabled", matchIfMissing = true)
public class ExerisActuatorAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ExerisRuntimeHealthIndicator exerisRuntimeHealthIndicator(
            ExerisRuntimeLifecycle lifecycle) {
        return new ExerisRuntimeHealthIndicator(lifecycle);
    }

    /**
     * Compatibility-mode HTTP diagnostics bridge.
     *
     * <p>Exposes management-style JSON responses through the Exeris compatibility dispatcher
     * when no servlet or reactive web server path exists.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.web.bind.annotation.RestController")
    @ConditionalOnProperty(prefix = "exeris.runtime.web", name = "mode", havingValue = "compatibility")
    static class CompatibilityEndpointsConfiguration {

        @Bean
        @ConditionalOnMissingBean
        public ExerisCompatibilityActuatorController exerisCompatibilityActuatorController(
                ExerisRuntimeHealthIndicator healthIndicator,
                ObjectProvider<ExerisRuntimeProperties> runtimeProperties,
                ObjectProvider<InfoContributor> infoContributors) {
            return new ExerisCompatibilityActuatorController(healthIndicator, runtimeProperties, infoContributors);
        }
    }

    /**
     * Micrometer bridge — only activated when {@code MeterRegistry} is on the classpath.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "io.micrometer.core.instrument.MeterRegistry")
    static class MicrometerBridgeConfiguration {

        @Bean
        @ConditionalOnMissingBean
        public ExerisActuatorTelemetryBridge exerisActuatorTelemetryBridge() {
            return new ExerisActuatorTelemetryBridge();
        }
    }
}
