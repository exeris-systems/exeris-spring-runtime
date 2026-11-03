/*
 * Copyright (C) 2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.examples.security;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * One Exeris runtime runs per JVM. A second one started while the first runs fails at once; once the
 * first stops, the JVM takes the next one.
 */
class OneRuntimePerJvmTest {

    @Test
    void aSecondRuntimeFailsAtOnceWhileTheFirstRuns() {
        try (ConfigurableApplicationContext ignored = EdgeKeys.boot(EdgeKeys.freePort())) {
            long started = System.nanoTime();
            Throwable failure = catchThrowable(() -> EdgeKeys.boot(EdgeKeys.freePort()).close());
            Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

            assertThat(elapsed).isLessThan(Duration.ofSeconds(10));
            assertThat(EdgeKeys.messages(failure)).anySatisfy(message ->
                    assertThat(message).contains("Only one Exeris runtime per JVM is supported"));
        }
    }

    @Test
    void aStoppedRuntimeReleasesTheJvmForTheNextOne() throws Exception {
        EdgeKeys.boot(EdgeKeys.freePort()).close();

        int port = EdgeKeys.freePort();
        try (ConfigurableApplicationContext ignored = EdgeKeys.boot(port)) {
            EdgeKeys.awaitAccepting(port);
        }
    }
}
