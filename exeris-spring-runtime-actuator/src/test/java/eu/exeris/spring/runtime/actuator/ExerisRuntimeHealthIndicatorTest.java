/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.actuator;

import eu.exeris.spring.boot.autoconfigure.ExerisRuntimeLifecycle;
import eu.exeris.spring.boot.autoconfigure.ExerisRuntimeProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link ExerisRuntimeHealthIndicator}.
 */
class ExerisRuntimeHealthIndicatorTest {

    /**
     * Returns an {@link ExerisRuntimeLifecycle} that is NOT running.
     * The constructor assigns fields without starting the kernel; {@code isRunning()} returns {@code false}.
     */
    private static ExerisRuntimeLifecycle notRunningLifecycle() {
        ExerisRuntimeProperties properties = new ExerisRuntimeProperties();
        return new ExerisRuntimeLifecycle(properties, null, Optional.empty());
    }

    @Test
    void health_whenNotRunning_reportsDown() {
        ExerisRuntimeHealthIndicator indicator =
                new ExerisRuntimeHealthIndicator(notRunningLifecycle());

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsKey("runtime");
        assertThat(health.getDetails()).containsKey("reason");
    }

    @Test
    void health_whenNotRunning_runtimeDetailIsExeris() {
        ExerisRuntimeHealthIndicator indicator =
                new ExerisRuntimeHealthIndicator(notRunningLifecycle());

        assertThat(indicator.health().getDetails().get("runtime")).isEqualTo("exeris");
    }

    @Test
    void constructor_rejectsNullLifecycle() {
        assertThatThrownBy(() -> new ExerisRuntimeHealthIndicator(null))
                .isInstanceOf(NullPointerException.class);
    }
}
