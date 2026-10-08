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
 * <p><b>Failure Mode:</b> Resolution failure returns {@link Optional#empty()} rather than throwing.
 * Spring Boot's health endpoint is operational visibility, not a data path: an actuator that cannot register
 * its indicator must not prevent the application from serving traffic.
 *
 * @implNote Builds a JDK dynamic proxy implementing {@code HealthIndicator} at runtime
 *     to bridge differences in Spring Boot packaging across Boot 3 and Boot 4 per ADR-028.
 *     Method handles for the {@code Health.Builder} API are cached during construction to avoid
 *     per-invocation reflection overhead on health checks.
 * @since 0.7
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
                (proxyInstance, method, args) -> switch (method.getName()) {
                    // health() on both lines, plus the interface's default overload — SB3 names it
                    // getHealth(boolean), SB4 names it health(boolean). A JDK proxy routes default
                    // methods through the handler rather than running them, so both land here. The
                    // includeDetails argument is ignored: this indicator's two details are a fixed
                    // label and a stopped-reason, neither of which is sensitive enough to redact, and
                    // suppressing them would leave a bare status that says nothing.
                    case "health", "getHealth" -> converter.toBootHealth(source.health());
                    case "toString" -> "ExerisRuntimeHealthIndicator(proxy)";
                    // Identity equality, preserving consistency with the identity hashCode beside it.
                    case "hashCode" -> System.identityHashCode(proxyInstance);
                    case "equals" -> args != null && args.length == 1 && proxyInstance == args[0];
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
