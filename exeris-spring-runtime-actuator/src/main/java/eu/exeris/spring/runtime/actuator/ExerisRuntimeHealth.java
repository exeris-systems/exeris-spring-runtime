/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.actuator;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Exeris runtime liveness, expressed without naming a Spring Boot type.
 *
 * @implSpec Encapsulates runtime liveness and diagnostic labels in a version-neutral model,
 *     decoupling health representation from framework-specific types across Spring Boot versions
 *     per ADR-028. Conversions to Spring Boot health representations are deferred to
 *     {@code SpringBootHealthIndicatorFactory}.
 *
 * @param up      {@code true} when the Exeris runtime is running
 * @param details diagnostic labels, rendered as the health component's details; never {@code null}
 * @since 0.7
 */
public record ExerisRuntimeHealth(boolean up, Map<String, String> details) {

    /** Status code, matching the strings Spring Boot's {@code Status} uses. */
    public static final String UP = "UP";
    public static final String DOWN = "DOWN";

    /**
     * Compact constructor creating an immutable copy of details.
     *
     * @param up {@code true} when the Exeris runtime is running
     * @param details diagnostic labels
     */
    public ExerisRuntimeHealth {
        details = details == null ? Map.of() : Map.copyOf(details);
    }

    /**
     * Returns the status code, {@value #UP} or {@value #DOWN}.
     *
     * @return status string
     */
    public String status() {
        return up ? UP : DOWN;
    }

    /**
     * Returns a running health status with the given details.
     *
     * @param details diagnostic labels
     * @return health instance indicating UP status
     */
    public static ExerisRuntimeHealth up(Map<String, String> details) {
        return new ExerisRuntimeHealth(true, details);
    }

    /**
     * Returns a non-running health status with the given details.
     *
     * @param details diagnostic labels
     * @return health instance indicating DOWN status
     */
    public static ExerisRuntimeHealth down(Map<String, String> details) {
        return new ExerisRuntimeHealth(false, details);
    }

    /**
     * Details as {@code Map<String, Object>}, the shape both Spring Boot's {@code Health} builder and
     * the compat controller's JSON body expect.
     *
     * @return copy of details map with Object values
     */
    public Map<String, Object> detailsAsObjects() {
        return new LinkedHashMap<>(details);
    }
}
