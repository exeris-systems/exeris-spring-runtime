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
 * Autoconfiguration tests for the flow module (Phase 4B).
 *
 * <p>Verifies the activation contract:
 * <ul>
 *   <li>The module does NOT activate by default — {@code exeris.runtime.flow.enabled}
 *       must be set explicitly ({@code matchIfMissing = false}).</li>
 *   <li>When activated, it exposes the {@link FlowEngineSupplier},
 *       {@link ExerisFlowTemplate}, and {@link ExerisFlowDefinitionRegistrar} beans wired
 *       around {@code ExerisRuntimeLifecycle.getFlowEngine()}.</li>
 *   <li>User-supplied beans win via {@code @ConditionalOnMissingBean}.</li>
 *   <li>{@link ExerisFlowProperties} optional flags ({@code persistenceEnabled},
 *       {@code choreographyEnabled}) default to {@code false} even when the module
 *       is enabled. {@code requireEngine} defaults to {@code true} (fail-loud posture).</li>
 * </ul>
 *
 * <p>Step 3 will extend this class once {@code ExerisFlowChoreographyBridge} lands.
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
            assertThat(context).doesNotHaveBean(ExerisFlowTemplate.class);
            assertThat(context).doesNotHaveBean(ExerisFlowDefinitionRegistrar.class);
        });
    }

    @Test
    void activatesWhenExplicitlyEnabled() {
        contextRunner
                .withPropertyValues(
                        "exeris.runtime.flow.enabled=true",
                        // Tolerate missing engine in autoconfig tests — the lifecycle is
                        // not started, so KernelProviders.FLOW_ENGINE is unbound by design.
                        "exeris.runtime.flow.require-engine=false")
                .run(context -> {
                    assertThat(context).hasSingleBean(FlowEngineSupplier.class);
                    assertThat(context).hasSingleBean(ExerisFlowProperties.class);
                    assertThat(context).hasSingleBean(ExerisFlowTemplate.class);
                    assertThat(context).hasSingleBean(ExerisFlowDefinitionRegistrar.class);
                });
    }

    @Test
    void requireEngineDefaultsTrue() {
        contextRunner
                .withPropertyValues("exeris.runtime.flow.enabled=true")
                .run(context -> {
                    ExerisFlowProperties props = context.getBean(ExerisFlowProperties.class);
                    assertThat(props.requireEngine())
                            .as("Default fail-loud posture: declare-without-engine should fail at start")
                            .isTrue();
                });
    }

    @Test
    void respectsCustomTemplateBeanOverride() {
        ExerisFlowTemplate custom = new ExerisFlowTemplate(java.util.Optional::empty);
        contextRunner
                .withPropertyValues(
                        "exeris.runtime.flow.enabled=true",
                        "exeris.runtime.flow.require-engine=false")
                .withBean(ExerisFlowTemplate.class, () -> custom)
                .run(context -> {
                    assertThat(context).hasSingleBean(ExerisFlowTemplate.class);
                    assertThat(context.getBean(ExerisFlowTemplate.class)).isSameAs(custom);
                });
    }

    @Test
    void optionalFlagsDefaultFalseEvenWhenEnabled() {
        // The master switch (enabled=true) is required to materialise ExerisFlowProperties
        // as a bean — that's the autoconfig's gate. Once active, the optional sub-feature
        // flags must still default to false: persistence is held back for 0.5.0-preview,
        // and choreography requires both this flag AND kernel choreographySupport().
        contextRunner
                .withPropertyValues("exeris.runtime.flow.enabled=true")
                .run(context -> {
                    ExerisFlowProperties props = context.getBean(ExerisFlowProperties.class);
                    assertThat(props.enabled()).isTrue();
                    assertThat(props.persistenceEnabled())
                            .as("persistenceEnabled stays false in 0.5.0-preview; bridge wiring sequenced for Phase 4B Step 4 closure")
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
