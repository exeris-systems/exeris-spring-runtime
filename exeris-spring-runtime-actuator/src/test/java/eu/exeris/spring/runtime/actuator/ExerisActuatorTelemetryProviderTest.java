/*
 * Copyright (C) 2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.spring.runtime.actuator;

import eu.exeris.kernel.spi.telemetry.TelemetryConfig;
import eu.exeris.kernel.spi.telemetry.TelemetryProvider;
import org.junit.jupiter.api.Test;

import java.util.ServiceLoader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExerisActuatorTelemetryProviderTest {

    @Test
    void createSinks_returnsTheBridgeAsItsOnlySink() {
        ExerisActuatorTelemetryBridge bridge = new ExerisActuatorTelemetryBridge();
        ExerisActuatorTelemetryProvider provider = new ExerisActuatorTelemetryProvider(bridge);

        assertThat(provider.createSinks(TelemetryConfig.defaults())).containsExactly(bridge);
        assertThat(provider.providerName()).isNotBlank();
    }

    @Test
    void rejectsNullBridge() {
        assertThatThrownBy(() -> new ExerisActuatorTelemetryProvider(null))
                .isInstanceOf(NullPointerException.class);
    }

    /** The provider is a Spring bean only; the kernel's ServiceLoader discovery must not find it. */
    @Test
    void isNotDiscoverableByTheKernel() {
        assertThat(ServiceLoader.load(TelemetryProvider.class).stream()
                .map(ServiceLoader.Provider::type))
                .doesNotContain(ExerisActuatorTelemetryProvider.class);
    }
}
