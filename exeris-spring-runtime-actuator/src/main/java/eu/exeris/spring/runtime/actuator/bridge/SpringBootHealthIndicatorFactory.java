/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.actuator.bridge;

import eu.exeris.spring.runtime.actuator.ExerisRuntimeHealth;
import eu.exeris.spring.runtime.actuator.ExerisRuntimeHealthIndicator;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.Optional;

/**
 * Produces a Spring Boot {@code HealthIndicator} without naming it at compile time.
 *
 * <h2>Why this is the one place reflection was unavoidable</h2>
 * <p>Three other Spring Boot 4 relocations were closed in this train by dropping the dependency on the
 * moved type rather than bridging it — the property names behind
 * {@code OAuth2ResourceServerProperties} had not moved, {@code HibernatePropertiesCustomizer} was only
 * a delivery mechanism for two settings, and {@code HttpHeaders} still offered a method common to both
 * lines. That reasoning runs out here.
 *
 * <p>{@code HealthIndicator} is not a carrier of data available elsewhere: it is an interface Spring
 * Boot discovers <em>by type</em>, and something must implement it. A class cannot declare
 * {@code implements} against a type whose package differs per matrix line while both lines compile the
 * same source (ADR-028 obligation 1), so the implementation is created at runtime instead — a JDK
 * proxy against whichever interface is present:
 *
 * <table>
 *   <caption>Interface coordinates by line</caption>
 *   <tr><th>Line</th><th>Interface</th><th>Artifact</th></tr>
 *   <tr><td>SB3</td><td>{@code org.springframework.boot.actuate.health.HealthIndicator}</td>
 *       <td>{@code spring-boot-actuator}</td></tr>
 *   <tr><td>SB4</td><td>{@code org.springframework.boot.health.contributor.HealthIndicator}</td>
 *       <td>{@code spring-boot-health}</td></tr>
 * </table>
 *
 * <p>The {@code Health} builder API is identical in shape on both lines ({@code Health.up()} /
 * {@code Health.down()} returning a {@code Builder} with {@code withDetail(String, Object)} and
 * {@code build()}), which is what makes one reflective path sufficient rather than two.
 *
 * <h2>Note on ADR-028's bridge taxonomy</h2>
 * <p>ADR-028 obligation 4 names an actuator health-indicator relocation as the canonical case for a
 * {@code bridge.sb4.*} sub-package. A sub-package cannot solve it: the problem is that the type is
 * unnameable at compile time under one line, and moving the class to another package does not change
 * that. This bridge is therefore version-neutral rather than SB4-specific, and lives in
 * {@code actuator.bridge} — it is equally in play on both lines, so an {@code sb4} label would be
 * misleading about when it runs.
 *
 * <h2>Failure mode</h2>
 * <p>Resolution failure returns {@link Optional#empty()} rather than throwing. Spring Boot's health
 * endpoint is operational visibility, not a data path: an actuator that cannot register its indicator
 * must not prevent the application from serving traffic. The caller logs the stand-down, so the
 * absence is visible rather than silent.
 *
 * @since 0.7.0
 */
public final class SpringBootHealthIndicatorFactory {

    private static final String[] HEALTH_INDICATOR_TYPES = {
        "org.springframework.boot.health.contributor.HealthIndicator",   // Spring Boot 4
        "org.springframework.boot.actuate.health.HealthIndicator",       // Spring Boot 3
    };

    private static final String[] HEALTH_TYPES = {
        "org.springframework.boot.health.contributor.Health",            // Spring Boot 4
        "org.springframework.boot.actuate.health.Health",                // Spring Boot 3
    };

    private SpringBootHealthIndicatorFactory() {
    }

    /**
     * Resolves the {@code HealthIndicator} interface present on the classpath.
     *
     * @param classLoader loader to resolve against; never {@code null}
     * @return the interface, or empty when neither line's actuator is present
     */
    public static Optional<Class<?>> healthIndicatorInterface(ClassLoader classLoader) {
        return firstPresent(HEALTH_INDICATOR_TYPES, classLoader);
    }

    /**
     * Creates a proxy implementing the resolved {@code HealthIndicator}, delegating to {@code source}.
     *
     * @param source      the runtime health decision; never {@code null}
     * @param classLoader loader to resolve and define the proxy against; never {@code null}
     * @return the proxy, or empty when the health types cannot be resolved
     */
    public static Optional<Object> createHealthIndicator(ExerisRuntimeHealthIndicator source,
                                                         ClassLoader classLoader) {
        Optional<Class<?>> indicatorType = healthIndicatorInterface(classLoader);
        Optional<Class<?>> healthType = firstPresent(HEALTH_TYPES, classLoader);
        if (indicatorType.isEmpty() || healthType.isEmpty()) {
            return Optional.empty();
        }

        HealthConverter converter;
        try {
            converter = new HealthConverter(healthType.get());
        } catch (ReflectiveOperationException _) {
            // The Health builder API is not the shape both known lines expose. Rather than guess at a
            // third shape, stand down — see the class Javadoc on the failure mode.
            return Optional.empty();
        }

        Object proxy = Proxy.newProxyInstance(
                classLoader,
                new Class<?>[] { indicatorType.get() },
                (_, method, args) -> switch (method.getName()) {
                    case "health" -> converter.toBootHealth(source.health());
                    case "getHealth" -> converter.toBootHealth(source.health());
                    case "toString" -> "ExerisRuntimeHealthIndicator(proxy)";
                    case "hashCode" -> System.identityHashCode(source);
                    case "equals" -> args != null && args.length == 1 && args[0] != null
                            && Proxy.isProxyClass(args[0].getClass());
                    default -> throw new UnsupportedOperationException(
                            "Unexpected HealthIndicator method: " + method);
                });
        return Optional.of(proxy);
    }

    private static Optional<Class<?>> firstPresent(String[] candidates, ClassLoader classLoader) {
        for (String candidate : candidates) {
            try {
                return Optional.of(Class.forName(candidate, false, classLoader));
            } catch (ClassNotFoundException _) {
                // Try the next line's coordinate.
            }
        }
        return Optional.empty();
    }

    /**
     * Converts {@link ExerisRuntimeHealth} into the line's {@code Health} type.
     *
     * <p>Handles are resolved once and reused: this runs on the health endpoint, which a liveness
     * probe may call every few seconds, and per-call reflective lookup would be pure waste.
     */
    private static final class HealthConverter {

        private final MethodHandle up;
        private final MethodHandle down;
        private final MethodHandle withDetail;
        private final MethodHandle build;

        HealthConverter(Class<?> healthType) throws ReflectiveOperationException {
            Class<?> builderType = Class.forName(
                    healthType.getName() + "$Builder", false, healthType.getClassLoader());
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            this.up = lookup.findStatic(healthType, "up", MethodType.methodType(builderType));
            this.down = lookup.findStatic(healthType, "down", MethodType.methodType(builderType));
            this.withDetail = lookup.findVirtual(builderType, "withDetail",
                    MethodType.methodType(builderType, String.class, Object.class));
            this.build = lookup.findVirtual(builderType, "build", MethodType.methodType(healthType));
        }

        Object toBootHealth(ExerisRuntimeHealth health) throws Throwable {
            Object builder = health.up() ? up.invoke() : down.invoke();
            for (Map.Entry<String, Object> detail : health.detailsAsObjects().entrySet()) {
                builder = withDetail.invoke(builder, detail.getKey(), detail.getValue());
            }
            return build.invoke(builder);
        }
    }
}
