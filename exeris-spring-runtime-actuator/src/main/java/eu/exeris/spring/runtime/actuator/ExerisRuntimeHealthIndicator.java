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
 * <p><b>Ownership:</b> Reads Spring lifecycle state only. No {@code ScopedValue} reads.
 * No kernel-path coupling. Safe to call from any thread at any time after Spring context refresh.
 *
 * @implNote The Spring Boot-facing health indicator shape is produced reflectively by
 *     {@link eu.exeris.spring.runtime.actuator.bridge.SpringBootHealthIndicatorFactory} to accommodate
 *     packaging differences across Spring Boot lines per ADR-028. This class remains framework-free
 *     and serves as the single source of truth for runtime health evaluation.
 * @since 0.1
 */
public final class ExerisRuntimeHealthIndicator {

    private static final String RUNTIME_DETAIL = "runtime";
    private static final String RUNTIME_NAME = "exeris";

    private final ExerisRuntimeLifecycle lifecycle;

    /**
     * Creates an indicator that reads liveness from the given lifecycle.
     *
     * @param lifecycle the runtime lifecycle to inspect
     */
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
