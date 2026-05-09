/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.events;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import eu.exeris.spring.boot.autoconfigure.ExerisRuntimeAutoConfiguration;

class ExerisEventAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withPropertyValues(
                    "exeris.runtime.enabled=true",
                    "exeris.runtime.auto-start=false",
                    // The autoconfig tests intentionally skip kernel boot, so the
                    // EventEngine reference is never captured. require-engine=false
                    // opts the listener registrar into tolerant mode so Spring's
                    // SmartLifecycle auto-start can still complete when no listener
                    // beans are wired into the test context.
                    "exeris.runtime.events.require-engine=false")
            .withConfiguration(AutoConfigurations.of(
                    ExerisRuntimeAutoConfiguration.class,
                    ExerisEventAutoConfiguration.class));

    @Test
    void doesNotActivateByDefault() {
        contextRunner.run(context -> {
            assertThat(context).doesNotHaveBean(ExerisEventPublisher.class);
            assertThat(context).doesNotHaveBean(ExerisEventTypeRegistry.class);
            assertThat(context).doesNotHaveBean(ExerisEventListenerRegistrar.class);
        });
    }

    @Test
    void activatesWhenExplicitlyEnabled() {
        contextRunner
                .withPropertyValues("exeris.runtime.events.enabled=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(EventEngineSupplier.class);
                    assertThat(context).hasSingleBean(ExerisEventTypeRegistry.class);
                    assertThat(context).hasSingleBean(ExerisEventPublisher.class);
                    assertThat(context).hasSingleBean(ExerisEventListenerRegistrar.class);
                    assertThat(context).hasSingleBean(ExerisEventProperties.class);
                });
    }

    @Test
    void respectsCustomBeanOverride() {
        contextRunner
                .withPropertyValues("exeris.runtime.events.enabled=true")
                .withBean(EventEngineSupplier.class, () -> java.util.Optional::empty)
                .run(context -> {
                    assertThat(context).hasSingleBean(EventEngineSupplier.class);
                    assertThat(context.getBean(EventEngineSupplier.class).tryGet()).isEmpty();
                });
    }

    @Test
    void requireEngineDefaultIsTrue() {
        // When users do not override the property, the registrar runs in strict mode
        // — the production posture that surfaces misconfigurations loudly.
        new ApplicationContextRunner()
                .withPropertyValues(
                        "exeris.runtime.enabled=true",
                        "exeris.runtime.auto-start=false",
                        "exeris.runtime.events.enabled=true")
                .withConfiguration(AutoConfigurations.of(
                        ExerisRuntimeAutoConfiguration.class,
                        ExerisEventAutoConfiguration.class))
                .run(context -> {
                    // No @ExerisEventListener beans in this context, so the registrar
                    // tolerates the missing engine even in strict mode (no listeners
                    // to wire). This proves the default property value without forcing
                    // a refresh failure.
                    assertThat(context).hasSingleBean(ExerisEventProperties.class);
                    assertThat(context.getBean(ExerisEventProperties.class).requireEngine()).isTrue();
                });
    }
}
