/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.actuator;

import eu.exeris.spring.boot.autoconfigure.ExerisRuntimeLifecycle;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Reports Exeris runtime liveness as {@link ExerisRuntimeHealth}.
 *
 * <p>Reports up when {@link ExerisRuntimeLifecycle#isRunning()} is {@code true}, and down otherwise —
 * the runtime has not yet started, or has been stopped.
 *
 * <h2>Why it no longer implements Spring Boot's {@code HealthIndicator}</h2>
 * <p>It did until the Spring Boot dual matrix landed. Spring Boot 4 moved that interface to a
 * different package in a different artifact, and a class cannot declare {@code implements} against a
 * type whose name differs per matrix line while both lines compile the same source (ADR-028
 * obligation 1). The Boot-facing shape is produced instead by
 * {@link eu.exeris.spring.runtime.actuator.bridge.SpringBootHealthIndicatorFactory}, which builds a
 * proxy against whichever interface is on the classpath and delegates here.
 *
 * <p>This class is therefore the source of truth for the health decision, and stays free of any
 * framework type — which is also what lets the compat actuator controller read it directly.
 *
 * <h2>Ownership</h2>
 * <p>Reads Spring lifecycle state only. No ScopedValue reads. No kernel-path coupling.
 * Safe to call from any thread at any time after Spring context refresh.
 *
 * @since 0.1.0
 */
public final class ExerisRuntimeHealthIndicator {

    private static final String RUNTIME_DETAIL = "runtime";
    private static final String RUNTIME_NAME = "exeris";

    private final ExerisRuntimeLifecycle lifecycle;

    public ExerisRuntimeHealthIndicator(ExerisRuntimeLifecycle lifecycle) {
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle must not be null");
    }

    /**
     * Evaluates runtime liveness.
     *
     * @return the current health; never {@code null}
     */
    public ExerisRuntimeHealth health() {
        if (lifecycle.isRunning()) {
            return ExerisRuntimeHealth.up(Map.of(RUNTIME_DETAIL, RUNTIME_NAME));
        }
        Map<String, String> details = new LinkedHashMap<>();
        details.put(RUNTIME_DETAIL, RUNTIME_NAME);
        details.put("reason", "Exeris runtime not running");
        return ExerisRuntimeHealth.down(details);
    }
}
