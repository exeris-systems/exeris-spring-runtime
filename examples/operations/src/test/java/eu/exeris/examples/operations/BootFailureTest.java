/*
 * Copyright (C) 2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.examples.operations;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A start that cannot succeed fails at once, and says why. */
class BootFailureTest {

    @Test
    void aKernelSubsystemRefusalNamesItsErrorCodeSubsystemAndDetail() {
        int port = Sockets.freePort();
        long started = System.nanoTime();

        // Two of the three edge-key properties: the kernel's security subsystem refuses to boot.
        assertThatThrownBy(() -> new SpringApplicationBuilder(OperationsApplication.class).run(
                "--exeris.runtime.network.port=" + port,
                "--exeris.runtime.subsystems=memory,transport,http,security",
                "--security.jwt.issuer=https://issuer.example.test",
                "--security.jwt.audience=operations"))
                .hasStackTraceContaining("Exeris runtime startup failed: [EX-BOOT-0002] subsystem 'security'"
                        + " failed in INITIALIZE: security.jwks.uri is not set");

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(10));
        assertThat(Sockets.accepting(port)).isFalse();
    }

    @Test
    void aSecondRuntimeInOneJvmFailsFast() {
        ConfigurableApplicationContext first = new SpringApplicationBuilder(OperationsApplication.class)
                .run("--exeris.runtime.network.port=" + Sockets.freePort());
        try {
            long started = System.nanoTime();

            assertThatThrownBy(() -> new SpringApplicationBuilder(OperationsApplication.class)
                    .run("--exeris.runtime.network.port=" + Sockets.freePort()))
                    .hasStackTraceContaining("Only one Exeris runtime per JVM is supported");

            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(10));
            assertThat(first.isRunning()).isTrue();
        } finally {
            first.close();
        }
    }
}
