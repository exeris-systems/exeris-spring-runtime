/*
 * Copyright (C) 2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.examples.security;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import eu.exeris.kernel.spi.exceptions.SubsystemException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * A partial set of the three edge keys fails the kernel boot at once, and the start failure names the
 * error code, the subsystem, the phase and the missing key.
 */
class PartialEdgeKeysTest {

    @Test
    void aPartialKeySetFailsTheBootAtOnceNamingTheMissingKey() {
        long started = System.nanoTime();
        Throwable failure = catchThrowable(() -> EdgeKeys.boot(EdgeKeys.freePort(),
                "--security.jwks.uri=http://127.0.0.1:1/jwks",
                "--security.jwt.issuer=",
                "--security.jwt.audience=").close());
        Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

        // Well under exeris.runtime.lifecycle.startup-timeout-seconds (30 s by default): the start
        // fails on the kernel's refusal, not on a timeout.
        assertThat(elapsed).isLessThan(Duration.ofSeconds(10));

        assertThat(EdgeKeys.messages(failure)).anySatisfy(message -> assertThat(message).contains(
                "[EX-BOOT-0002] subsystem 'security' failed in INITIALIZE: security.jwt.issuer is not set"));

        SubsystemException refusal = EdgeKeys.find(failure, SubsystemException.class);
        assertThat(refusal.errorCode()).isEqualTo("EX-BOOT-0002");
        assertThat(refusal.subsystemName()).isEqualTo("security");
        assertThat(refusal.detail()).startsWith("security.jwt.issuer is not set");
    }
}
