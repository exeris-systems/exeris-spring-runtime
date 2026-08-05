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
 * <h2>Why this exists</h2>
 * <p>{@code ExerisRuntimeHealthIndicator} used to return Spring Boot's {@code Health} directly.
 * Spring Boot 4 moved that class — from {@code org.springframework.boot.actuate.health} in
 * {@code spring-boot-actuator} to {@code org.springframework.boot.health.contributor} in a new
 * {@code spring-boot-health} artifact — and ADR-028 obligation 1 requires one source tree to compile
 * under both matrix profiles, so naming either package breaks the other line.
 *
 * <p>What this runtime actually knows is small and framework-free: whether the kernel is running, and
 * two labels explaining it. Holding that here, and converting to Boot's shape only at the seam that
 * needs it ({@code SpringBootHealthIndicatorFactory}), keeps every other class version-neutral —
 * including the compat actuator controller, which now reads this type instead of Boot's.
 *
 * @param up      {@code true} when the Exeris runtime is running
 * @param details diagnostic labels, rendered as the health component's details; never {@code null}
 * @since 0.7.0
 */
public record ExerisRuntimeHealth(boolean up, Map<String, String> details) {

    /** Status code, matching the strings Spring Boot's {@code Status} uses. */
    public static final String UP = "UP";
    public static final String DOWN = "DOWN";

    public ExerisRuntimeHealth {
        details = details == null ? Map.of() : Map.copyOf(details);
    }

    /**
     * Returns the status code, {@value #UP} or {@value #DOWN}.
     */
    public String status() {
        return up ? UP : DOWN;
    }

    /** Running, with the given details. */
    public static ExerisRuntimeHealth up(Map<String, String> details) {
        return new ExerisRuntimeHealth(true, details);
    }

    /** Not running, with the given details. */
    public static ExerisRuntimeHealth down(Map<String, String> details) {
        return new ExerisRuntimeHealth(false, details);
    }

    /**
     * Details as {@code Map<String, Object>}, the shape both Spring Boot's {@code Health} builder and
     * the compat controller's JSON body expect.
     */
    public Map<String, Object> detailsAsObjects() {
        return new LinkedHashMap<>(details);
    }
}
