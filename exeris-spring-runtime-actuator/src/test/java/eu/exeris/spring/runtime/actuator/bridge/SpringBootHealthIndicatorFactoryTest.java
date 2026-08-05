/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.actuator.bridge;

import eu.exeris.spring.boot.autoconfigure.ExerisRuntimeLifecycle;
import eu.exeris.spring.boot.autoconfigure.ExerisRuntimeProperties;
import eu.exeris.spring.runtime.actuator.ExerisRuntimeHealthIndicator;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Unit tests for {@link SpringBootHealthIndicatorFactory}.
 *
 * <p>These run against whichever Spring Boot line the active matrix profile supplies, which is the
 * point: the factory exists because {@code HealthIndicator} and {@code Health} sit in different
 * packages on SB3 and SB4, and the assertions below name neither. They go through reflection for the
 * same reason the production code does — a test that named either coordinate would compile on one line
 * and not the other.
 *
 * <p>What is asserted is the contract the bridge owes Spring Boot: an object that <em>is</em> the
 * line's {@code HealthIndicator}, whose {@code health()} returns the line's {@code Health} carrying the
 * status and details the runtime decided.
 */
class SpringBootHealthIndicatorFactoryTest {

    private static final ClassLoader LOADER =
            SpringBootHealthIndicatorFactoryTest.class.getClassLoader();

    @Test
    void resolvesTheHealthIndicatorInterfaceOnThisLine() {
        Optional<Class<?>> resolved = SpringBootHealthIndicatorFactory.healthIndicatorInterface(LOADER);

        assertThat(resolved)
                .as("one of the two known coordinates must resolve; if neither does, the actuator "
                        + "module is missing its health artifact for this matrix line")
                .isPresent();
        assertThat(resolved.get().getName()).endsWith(".HealthIndicator");
    }

    @Test
    void createsAProxyThatIsTheLinesHealthIndicator() {
        Object indicator = createIndicator(notRunningLifecycle());

        Class<?> interfaceType = SpringBootHealthIndicatorFactory
                .healthIndicatorInterface(LOADER).orElseThrow();

        assertThat(interfaceType.isInstance(indicator))
                .as("Spring Boot discovers contributors by type, so being an instance of the "
                        + "interface is the whole contract")
                .isTrue();
    }

    @Test
    void proxyReportsDown_whenTheRuntimeIsNotRunning() throws Exception {
        Object indicator = createIndicator(notRunningLifecycle());

        Object health = invokeHealth(indicator);

        assertThat(statusCodeOf(health)).isEqualTo("DOWN");
        assertThat(detailsOf(health))
                .containsEntry("runtime", "exeris")
                .containsKey("reason");
    }

    @Test
    void proxyReportsUp_whenTheRuntimeIsRunning() throws Exception {
        // Covers the up branch of the reflective builder — the down branch alone would leave
        // Health.up() unexercised, and the two are resolved as separate MethodHandles.
        Object indicator = createIndicator(runningLifecycle());

        Object health = invokeHealth(indicator);

        assertThat(statusCodeOf(health)).isEqualTo("UP");
        assertThat(detailsOf(health)).containsEntry("runtime", "exeris");
    }

    @Test
    void proxyAnswersTheInterfacesDefaultOverload() {
        // HealthIndicator carries a default overload — getHealth(boolean) on SB3, health(boolean) on
        // SB4 — and a JDK proxy routes default methods through the handler instead of running them,
        // so it has to be answered explicitly. Found by reading both interfaces rather than assuming
        // health() was the whole surface.
        Object indicator = createIndicator(notRunningLifecycle());
        Class<?> interfaceType = SpringBootHealthIndicatorFactory
                .healthIndicatorInterface(LOADER).orElseThrow();

        Method overload = java.util.Arrays.stream(interfaceType.getMethods())
                .filter(m -> m.getParameterCount() == 1 && m.getParameterTypes()[0] == boolean.class)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no boolean overload on " + interfaceType));

        assertThatCode(() -> {
            Object health = overload.invoke(indicator, true);
            assertThat(statusCodeOf(health)).isEqualTo("DOWN");
        }).doesNotThrowAnyException();
    }

    @Test
    void proxyIdentitySemanticsAreConsistent() {
        // equals/hashCode must agree. An earlier version answered equals() true for any proxy while
        // hashing on identity, so two proxies over different sources were "equal" with different
        // hash codes.
        Object first = createIndicator(notRunningLifecycle());
        Object second = createIndicator(notRunningLifecycle());

        assertThat(first).isEqualTo(first);
        assertThat(first).isNotEqualTo(second);
        assertThat(first.hashCode()).isEqualTo(first.hashCode());
        assertThat(first.toString()).contains("ExerisRuntimeHealthIndicator");
    }

    @Test
    void standsDownWhenNoHealthTypesAreVisible() {
        // A classloader that resolves nothing stands in for a deployment without the actuator's
        // health artifact. The factory must return empty rather than throw — the caller degrades.
        ClassLoader empty = new ClassLoader(null) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                throw new ClassNotFoundException(name);
            }
        };

        assertThat(SpringBootHealthIndicatorFactory.healthIndicatorInterface(empty)).isEmpty();
        assertThat(SpringBootHealthIndicatorFactory
                .createHealthIndicator(new ExerisRuntimeHealthIndicator(notRunningLifecycle()), empty))
                .isEmpty();
    }

    // =========================================================================

    private static Object createIndicator(ExerisRuntimeLifecycle lifecycle) {
        return SpringBootHealthIndicatorFactory
                .createHealthIndicator(new ExerisRuntimeHealthIndicator(lifecycle), LOADER)
                .orElseThrow(() -> new AssertionError(
                        "no Spring Boot health types on the test classpath for this matrix line"));
    }

    private static Object invokeHealth(Object indicator) throws Exception {
        Method health = indicator.getClass().getMethod("health");
        return health.invoke(indicator);
    }

    private static String statusCodeOf(Object health) throws Exception {
        Object status = health.getClass().getMethod("getStatus").invoke(health);
        return (String) status.getClass().getMethod("getCode").invoke(status);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> detailsOf(Object health) throws Exception {
        return (Map<String, Object>) health.getClass().getMethod("getDetails").invoke(health);
    }

    /** A lifecycle that was never started — {@code isRunning()} is {@code false}. */
    private static ExerisRuntimeLifecycle notRunningLifecycle() {
        return new ExerisRuntimeLifecycle(new ExerisRuntimeProperties(), null, Optional.empty());
    }

    /**
     * A lifecycle reporting running without booting a kernel.
     *
     * <p>{@code ExerisRuntimeLifecycle} is {@code final} — deliberately, it is a lifecycle
     * coordinator and not an extension point — so the flag is set directly. {@code isRunning()} is the
     * only state the health indicator reads, and standing a real kernel up to flip one boolean would
     * turn a unit test into an integration test for no added confidence.
     */
    private static ExerisRuntimeLifecycle runningLifecycle() {
        ExerisRuntimeLifecycle lifecycle = notRunningLifecycle();
        try {
            java.lang.reflect.Field running = ExerisRuntimeLifecycle.class.getDeclaredField("running");
            running.setAccessible(true);
            running.setBoolean(lifecycle, true);
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError(
                    "ExerisRuntimeLifecycle.running is gone or renamed — this fixture needs updating", ex);
        }
        return lifecycle;
    }
}
