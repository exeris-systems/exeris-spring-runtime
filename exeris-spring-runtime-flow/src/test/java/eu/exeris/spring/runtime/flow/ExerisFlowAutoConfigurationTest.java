/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.flow;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import eu.exeris.spring.boot.autoconfigure.ExerisRuntimeAutoConfiguration;

/**
 * Skeleton autoconfiguration tests for the flow module (Phase 4B Step 1).
 *
 * <p>Verifies the activation contract:
 * <ul>
 *   <li>The module does NOT activate by default — {@code exeris.runtime.flow.enabled}
 *       must be set explicitly ({@code matchIfMissing = false}).</li>
 *   <li>When activated, it exposes a single {@link FlowEngineSupplier} bean wired to
 *       {@code ExerisRuntimeLifecycle.getFlowEngine()}.</li>
 *   <li>A user-supplied {@code FlowEngineSupplier} bean wins via
 *       {@code @ConditionalOnMissingBean}.</li>
 *   <li>{@link ExerisFlowProperties} default values are all {@code false} —
 *       {@code enabled}, {@code persistenceEnabled}, {@code choreographyEnabled}.</li>
 * </ul>
 *
 * <p>Subsequent Phase 4B steps will extend this test class as additional beans
 * (definition registrar, template, choreography bridge) land.
 */
class ExerisFlowAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withPropertyValues(
                    "exeris.runtime.enabled=true",
                    // Skip kernel boot — autoconfig tests verify wiring, not engine binding.
                    "exeris.runtime.auto-start=false")
            .withConfiguration(AutoConfigurations.of(
                    ExerisRuntimeAutoConfiguration.class,
                    ExerisFlowAutoConfiguration.class));

    @Test
    void doesNotActivateByDefault() {
        contextRunner.run(context -> {
            assertThat(context).doesNotHaveBean(FlowEngineSupplier.class);
            assertThat(context).doesNotHaveBean(ExerisFlowProperties.class);
        });
    }

    @Test
    void activatesWhenExplicitlyEnabled() {
        contextRunner
                .withPropertyValues("exeris.runtime.flow.enabled=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(FlowEngineSupplier.class);
                    assertThat(context).hasSingleBean(ExerisFlowProperties.class);
                });
    }

    @Test
    void defaultPropertiesAreAllFalse() {
        contextRunner
                .withPropertyValues("exeris.runtime.flow.enabled=true")
                .run(context -> {
                    ExerisFlowProperties props = context.getBean(ExerisFlowProperties.class);
                    assertThat(props.enabled()).isTrue();
                    assertThat(props.persistenceEnabled())
                            .as("persistenceEnabled is held back until an Exeris-owned FlowSnapshotStore lands")
                            .isFalse();
                    assertThat(props.choreographyEnabled())
                            .as("choreographyEnabled is opt-in and additionally requires kernel choreographySupport()")
                            .isFalse();
                });
    }

    @Test
    void respectsCustomFlowEngineSupplierBeanOverride() {
        contextRunner
                .withPropertyValues("exeris.runtime.flow.enabled=true")
                .withBean(FlowEngineSupplier.class, () -> java.util.Optional::empty)
                .run(context -> {
                    assertThat(context).hasSingleBean(FlowEngineSupplier.class);
                    assertThat(context.getBean(FlowEngineSupplier.class).tryGet()).isEmpty();
                });
    }
}
