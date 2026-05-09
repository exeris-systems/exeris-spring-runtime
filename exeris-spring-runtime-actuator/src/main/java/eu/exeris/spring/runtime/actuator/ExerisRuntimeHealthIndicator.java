/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.actuator;

import eu.exeris.spring.boot.autoconfigure.ExerisRuntimeLifecycle;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

import java.util.Objects;

/**
 * Spring Boot {@link HealthIndicator} reporting Exeris runtime liveness.
 *
 * <p>Reports {@link Health#up()} when {@link ExerisRuntimeLifecycle#isRunning()} is
 * {@code true}. Reports {@link Health#down()} otherwise — the runtime has not yet
 * started, or has been stopped.
 *
 * <h2>Ownership</h2>
 * <p>Reads Spring lifecycle state only. No ScopedValue reads. No kernel-path coupling.
 * Safe to call from any thread at any time after Spring context refresh.
 *
 * @since 0.1.0
 */
public final class ExerisRuntimeHealthIndicator implements HealthIndicator {

    private final ExerisRuntimeLifecycle lifecycle;

    public ExerisRuntimeHealthIndicator(ExerisRuntimeLifecycle lifecycle) {
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle must not be null");
    }

    @Override
    public Health health() {
        if (lifecycle.isRunning()) {
            return Health.up()
                    .withDetail("runtime", "exeris")
                    .build();
        }
        return Health.down()
                .withDetail("runtime", "exeris")
                .withDetail("reason", "Exeris runtime not running")
                .build();
    }
}
