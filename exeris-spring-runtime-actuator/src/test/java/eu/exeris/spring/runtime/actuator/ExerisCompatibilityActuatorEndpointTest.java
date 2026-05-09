/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.actuator;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;

import eu.exeris.spring.boot.autoconfigure.ExerisRuntimeLifecycle;
import eu.exeris.spring.boot.autoconfigure.ExerisRuntimeProperties;
import eu.exeris.spring.runtime.actuator.compat.ExerisCompatibilityActuatorController;

class ExerisCompatibilityActuatorEndpointTest {

    @Test
    void compatibilityMode_registersCompatibilityActuatorControllerBean() {
        try (var context = createContext(Map.of("exeris.runtime.web.mode", "compatibility"))) {
            assertThat(context.containsBean("exerisCompatibilityActuatorController")).isTrue();
        }
    }

    @Test
    void pureMode_doesNotRegisterCompatibilityActuatorControllerBean() {
        try (var context = createContext(Map.of("exeris.runtime.web.mode", "pure"))) {
            assertThat(context.containsBean("exerisCompatibilityActuatorController")).isFalse();
        }
    }

    @Test
    void compatibilityController_healthPayload_reportsUpStatus() {
        try (var context = createContext(Map.of("exeris.runtime.web.mode", "compatibility"))) {
            ExerisCompatibilityActuatorController controller =
                    context.getBean(ExerisCompatibilityActuatorController.class);

            Map<String, Object> body = controller.health();

            assertThat(body).containsEntry("status", "UP");
            assertThat(body.toString()).contains("exerisRuntime").contains("runtime=exeris");
        }
    }

    @Test
    void compatibilityController_infoPayload_reportsRuntimeModeDetails() {
        try (var context = createContext(Map.of("exeris.runtime.web.mode", "compatibility"))) {
            ExerisCompatibilityActuatorController controller =
                    context.getBean(ExerisCompatibilityActuatorController.class);

            Map<String, Object> body = controller.info();

            assertThat(body).containsEntry("runtime", "exeris");
            assertThat(body).containsEntry("mode", "compatibility");
        }
    }

    private static AnnotationConfigApplicationContext createContext(Map<String, Object> properties) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        Map<String, Object> safeProperties = Map.copyOf(java.util.Objects.requireNonNull(properties, "properties"));
        context.getEnvironment().getPropertySources().addFirst(
                new MapPropertySource("testProps", safeProperties));
        context.register(ExerisActuatorAutoConfiguration.class, TestConfig.class);
        context.refresh();
        return context;
    }

    @Configuration
    static class TestConfig {

        @Bean
        @SuppressWarnings("unused")
        ExerisRuntimeLifecycle exerisRuntimeLifecycle() {
            ExerisRuntimeProperties properties = new ExerisRuntimeProperties(
                    true,
                    false,
                    new ExerisRuntimeProperties.WebProperties(ExerisRuntimeProperties.Mode.COMPATIBILITY),
                    new ExerisRuntimeProperties.LifecycleProperties(),
                    new ExerisRuntimeProperties.ShutdownProperties());
            ExerisRuntimeLifecycle lifecycle = new ExerisRuntimeLifecycle(properties, null, Optional.empty());
            forceRunning(lifecycle);
            return lifecycle;
        }

        private static void forceRunning(ExerisRuntimeLifecycle lifecycle) {
            try {
                Field running = ExerisRuntimeLifecycle.class.getDeclaredField("running");
                running.setAccessible(true);
                running.setBoolean(lifecycle, true);
            } catch (ReflectiveOperationException ex) {
                throw new IllegalStateException("Unable to mark ExerisRuntimeLifecycle as running for test setup", ex);
            }
        }
    }
}
