/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.flow;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.flow.FlowDefinitionBuilder;
import eu.exeris.kernel.spi.flow.FlowEngine;
import eu.exeris.kernel.spi.flow.FlowExecutionPlanFactory;
import eu.exeris.kernel.spi.flow.model.FlowContext;
import eu.exeris.kernel.spi.flow.model.FlowDefinition;
import eu.exeris.kernel.spi.flow.model.FlowExecutionPlan;
import eu.exeris.kernel.spi.flow.model.FlowOutcome;
import eu.exeris.kernel.spi.flow.model.FlowStepAction;
import eu.exeris.kernel.spi.flow.model.FlowStepDescriptor;
import eu.exeris.kernel.spi.persistence.PersistenceEngine;
import eu.exeris.spring.boot.autoconfigure.KernelProviderScope;

/**
 * Unit tests for {@link ExerisFlowDefinitionRegistrar} — verifies bean discovery,
 * plan compilation, and tolerant/strict engine-availability posture against a mocked
 * {@link FlowEngine}.
 */
class ExerisFlowDefinitionRegistrarTest {

    @Test
    void scansAndCompilesAllDefinitionBeansOnStart() {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.register(TwoFlowsConfig.class);
            ctx.refresh();

            ExerisFlowTemplate template = template();
            EngineMocks engine = mockEngine();
            ExerisFlowDefinitionRegistrar registrar = new ExerisFlowDefinitionRegistrar(
                    ctx, () -> Optional.of(engine.engine), template, strict(), KernelProviderScope.noop());

            registrar.afterSingletonsInstantiated();
            assertThat(registrar.boundDefinitionCount()).isEqualTo(2);

            registrar.start();

            verify(engine.plans, times(1)).newDefinition(eq("alpha"));
            verify(engine.plans, times(1)).newDefinition(eq("beta"));
            verify(engine.plans, times(2)).compile(any(FlowDefinition.class));
            assertThat(template.registeredFlowNames()).containsExactlyInAnyOrder("alpha", "beta");
            assertThat(registrar.isRunning()).isTrue();
        }
    }

    @Test
    void duplicateNameAcrossBeansFailsAtCollection() {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.register(DuplicateNameConfig.class);
            ctx.refresh();

            ExerisFlowDefinitionRegistrar registrar = new ExerisFlowDefinitionRegistrar(
                    ctx, () -> Optional.of(mockEngine().engine), template(), strict(), KernelProviderScope.noop());

            assertThatThrownBy(registrar::afterSingletonsInstantiated)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Duplicate ExerisFlowDefinition.name()='alpha'");
        }
    }

    @Test
    void blankNameFailsAtCollection() {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.register(BlankNameConfig.class);
            ctx.refresh();

            ExerisFlowDefinitionRegistrar registrar = new ExerisFlowDefinitionRegistrar(
                    ctx, () -> Optional.of(mockEngine().engine), template(), strict(), KernelProviderScope.noop());

            assertThatThrownBy(registrar::afterSingletonsInstantiated)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("must be non-empty");
        }
    }

    @Test
    void definitionNameMismatchAgainstBeanNameFailsAtStart() {
        // Bean reports name()="alpha" but builds a FlowDefinition with a different name —
        // means the bean ignored the supplied builder's name slot, which would silently
        // mis-route schedule()/wake() lookups later. Fail loud at boot.
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.register(NameMismatchConfig.class);
            ctx.refresh();

            ExerisFlowTemplate template = template();
            EngineMocks engine = mockEngine();
            // Override the build() return for the mismatch flow to return a different name.
            FlowDefinitionBuilder builder = mock(FlowDefinitionBuilder.class);
            when(engine.plans.newDefinition(eq("alpha"))).thenReturn(builder);
            when(builder.build()).thenReturn(stubDefinition("forged-name"));

            ExerisFlowDefinitionRegistrar registrar = new ExerisFlowDefinitionRegistrar(
                    ctx, () -> Optional.of(engine.engine), template, strict(), KernelProviderScope.noop());
            registrar.afterSingletonsInstantiated();

            assertThatThrownBy(registrar::start)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("must match")
                    .hasMessageContaining("forged-name");
        }
    }

    @Test
    void nullDefinitionFromBeanFailsAtStart() {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.register(NullReturnConfig.class);
            ctx.refresh();

            ExerisFlowDefinitionRegistrar registrar = new ExerisFlowDefinitionRegistrar(
                    ctx, () -> Optional.of(mockEngine().engine), template(), strict(), KernelProviderScope.noop());
            registrar.afterSingletonsInstantiated();

            assertThatThrownBy(registrar::start)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("returned null from define");
        }
    }

    @Test
    void engineUnavailableInTolerantModeSkipsCompileButTransitionsToRunning() {
        // Test/dev posture (require-engine=false): definitions are declared but no
        // kernel FlowEngine bound. Lifecycle must still transition to running so
        // SmartLifecycle stop() ordering is consistent — plans simply remain unregistered.
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.register(TwoFlowsConfig.class);
            ctx.refresh();

            ExerisFlowTemplate template = template();
            ExerisFlowDefinitionRegistrar registrar = new ExerisFlowDefinitionRegistrar(
                    ctx, Optional::empty, template, tolerant(), KernelProviderScope.noop());
            registrar.afterSingletonsInstantiated();
            registrar.start();

            assertThat(registrar.isRunning()).isTrue();
            assertThat(registrar.boundDefinitionCount()).isEqualTo(2);
            assertThat(template.registeredFlowNames()).isEmpty();
        }
    }

    @Test
    void engineUnavailableInStrictModeFailsLoudWhenDefinitionsDeclared() {
        // Production posture (default require-engine=true): definitions declared but no
        // FlowEngine bound is a real misconfiguration. Fail at lifecycle start so the
        // operator sees it immediately rather than discovering it through silent
        // schedule()/wake() failures later.
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.register(TwoFlowsConfig.class);
            ctx.refresh();

            ExerisFlowDefinitionRegistrar registrar = new ExerisFlowDefinitionRegistrar(
                    ctx, Optional::empty, template(), strict(), KernelProviderScope.noop());
            registrar.afterSingletonsInstantiated();

            assertThatThrownBy(registrar::start)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("no kernel FlowEngine is available")
                    .hasMessageContaining("require-engine=false");
            assertThat(registrar.isRunning()).isFalse();
        }
    }

    @Test
    void engineUnavailableWithoutDefinitionsIsAlwaysTolerated() {
        // No ExerisFlowDefinition beans declared: the registrar has nothing to compile,
        // so a missing engine is irrelevant for this bean even in strict mode. Hot-path
        // template calls (schedule/wake) still fail loud at first invocation.
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.refresh();

            ExerisFlowDefinitionRegistrar registrar = new ExerisFlowDefinitionRegistrar(
                    ctx, Optional::empty, template(), strict(), KernelProviderScope.noop());
            registrar.afterSingletonsInstantiated();
            registrar.start();

            assertThat(registrar.isRunning()).isTrue();
            assertThat(registrar.boundDefinitionCount()).isZero();
        }
    }

    @Test
    void stopClearsPlanRegistryAndTransitionsOutOfRunning() {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.register(TwoFlowsConfig.class);
            ctx.refresh();

            ExerisFlowTemplate template = template();
            EngineMocks engine = mockEngine();
            ExerisFlowDefinitionRegistrar registrar = new ExerisFlowDefinitionRegistrar(
                    ctx, () -> Optional.of(engine.engine), template, strict(), KernelProviderScope.noop());
            registrar.afterSingletonsInstantiated();
            registrar.start();
            assertThat(template.registeredFlowNames()).hasSize(2);

            registrar.stop();

            assertThat(template.registeredFlowNames()).isEmpty();
            assertThat(registrar.isRunning()).isFalse();
        }
    }

    @Test
    void wrapsStepActionsInKernelProviderScopeBeforeHandingThemToTheKernelBuilder() {
        // The registrar must decorate the kernel builder so application step bodies execute
        // with KernelProviders slots re-bound — flow scheduler worker virtual threads do not
        // inherit the bootstrap ScopedValue scope, and compat-DataSource consumers inside saga
        // steps read KernelProviders.PERSISTENCE_ENGINE directly.
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            AtomicReference<PersistenceEngine> seenInStep = new AtomicReference<>();
            ExerisFlowDefinition stepped = new ExerisFlowDefinition() {
                @Override public String name() { return "stepped"; }
                @Override public FlowDefinition define(FlowDefinitionBuilder b) {
                    return b.step("touch-db", stepContext -> {
                        seenInStep.set(KernelProviders.PERSISTENCE_ENGINE.get());
                        return FlowOutcome.COMPLETE;
                    }, null).build();
                }
            };
            ctx.registerBean(ExerisFlowDefinition.class, () -> stepped);
            ctx.refresh();

            FlowEngine flowEngine = mock(FlowEngine.class);
            FlowExecutionPlanFactory plans = mock(FlowExecutionPlanFactory.class);
            FlowDefinitionBuilder kernelBuilder = mock(FlowDefinitionBuilder.class);
            // Pre-build stubs before any when(...) — see mockEngine() for the Mockito
            // UnfinishedStubbing pitfall this avoids.
            FlowDefinition steppedDefinition = stubDefinition("stepped");
            FlowExecutionPlan steppedPlan = stubPlan("stepped");
            when(flowEngine.plans()).thenReturn(plans);
            when(kernelBuilder.build()).thenReturn(steppedDefinition);
            when(plans.newDefinition(eq("stepped"))).thenReturn(kernelBuilder);
            when(plans.compile(any(FlowDefinition.class))).thenReturn(steppedPlan);

            PersistenceEngine capturedEngine = mock(PersistenceEngine.class);
            ExerisFlowDefinitionRegistrar registrar = new ExerisFlowDefinitionRegistrar(
                    ctx, () -> Optional.of(flowEngine), template(), strict(),
                    KernelProviderScope.capturing(() -> Optional.of(capturedEngine), Optional::empty));
            registrar.afterSingletonsInstantiated();
            registrar.start();

            ArgumentCaptor<FlowStepAction> registered = ArgumentCaptor.forClass(FlowStepAction.class);
            verify(kernelBuilder).step(eq("touch-db"), registered.capture(), isNull());

            // Execute the registered action on this test thread (no bootstrap bindings) —
            // exactly what the kernel scheduler does on a worker virtual thread.
            assertThat(registered.getValue().execute(mock(FlowContext.class)))
                    .isEqualTo(FlowOutcome.COMPLETE);
            assertThat(seenInStep.get())
                    .as("step body must observe PERSISTENCE_ENGINE re-bound from the captured reference")
                    .isSameAs(capturedEngine);
            assertThat(KernelProviders.PERSISTENCE_ENGINE.isBound())
                    .as("re-bind must be scoped to the step body")
                    .isFalse();
        }
    }

    // =========================================================================
    // helpers + fixtures
    // =========================================================================

    private static ExerisFlowProperties strict() {
        return new ExerisFlowProperties(true, false, false, true);
    }

    private static ExerisFlowProperties tolerant() {
        return new ExerisFlowProperties(true, false, false, false);
    }

    private static ExerisFlowTemplate template() {
        // Template's own engine supplier is irrelevant for the registrar tests — registrar
        // uses its own supplier; the template here only owns the plan map.
        return new ExerisFlowTemplate(Optional::empty);
    }

    private static FlowDefinition stubDefinition(String name) {
        // FlowDefinition validates that at least one step is present, so seed a no-op
        // step that always reports COMPLETE. The action lambda is never executed by
        // the registrar tests — only build() and compile() are exercised.
        FlowStepAction noop = ctx -> FlowOutcome.COMPLETE;
        FlowStepDescriptor step = new FlowStepDescriptor(0, "noop", noop, null);
        return new FlowDefinition(name, List.of(step), 1_000_000_000L, 0);
    }

    private static FlowExecutionPlan stubPlan(String name) {
        FlowExecutionPlan plan = mock(FlowExecutionPlan.class);
        when(plan.definitionName()).thenReturn(name);
        when(plan.timeoutDurationNanos()).thenReturn(1_000_000_000L);
        return plan;
    }

    /**
     * Builds a fully-stubbed engine where every {@code newDefinition(name)} returns a
     * pre-built builder that will build a {@code FlowDefinition} matching that name, and
     * {@code compile(def)} returns a pre-built plan keyed by the definition's name.
     *
     * <p>Stubs are computed eagerly so we never trigger nested {@code when().thenReturn()}
     * inside a {@code thenAnswer} lambda — Mockito flags that as
     * {@code UnfinishedStubbingException} when the nested mock is realised at test time.
     */
    private static EngineMocks mockEngine() {
        FlowEngine engine = mock(FlowEngine.class);
        FlowExecutionPlanFactory plans = mock(FlowExecutionPlanFactory.class);
        when(engine.plans()).thenReturn(plans);

        for (String name : List.of("alpha", "beta", "null-flow")) {
            FlowDefinitionBuilder builder = mock(FlowDefinitionBuilder.class);
            FlowDefinition def = stubDefinition(name);
            FlowExecutionPlan plan = stubPlan(name);
            // Order matters: each when(...).thenReturn(...) must be a *single* atomic
            // unit with no intervening mock invocations. Building `def` and `plan`
            // before any `when(plans.*)` keeps Mockito's pending-stubbing state clean.
            when(builder.build()).thenReturn(def);
            when(plans.newDefinition(eq(name))).thenReturn(builder);
            when(plans.compile(def)).thenReturn(plan);
        }
        return new EngineMocks(engine, plans);
    }

    private record EngineMocks(FlowEngine engine, FlowExecutionPlanFactory plans) {
    }

    // ----- bean fixtures (use static @Configuration so register() can pick them up) -----

    @Configuration
    static class TwoFlowsConfig {
        @Bean public ExerisFlowDefinition alphaFlow() { return new NamedFlow("alpha"); }
        @Bean public ExerisFlowDefinition betaFlow()  { return new NamedFlow("beta"); }
    }

    @Configuration
    static class DuplicateNameConfig {
        @Bean public ExerisFlowDefinition alphaA() { return new NamedFlow("alpha"); }
        @Bean public ExerisFlowDefinition alphaB() { return new NamedFlow("alpha"); }
    }

    @Configuration
    static class BlankNameConfig {
        @Bean public ExerisFlowDefinition blank() { return new NamedFlow(""); }
    }

    @Configuration
    static class NameMismatchConfig {
        @Bean public ExerisFlowDefinition mismatch() { return new NamedFlow("alpha"); }
    }

    @Configuration
    static class NullReturnConfig {
        @Bean public ExerisFlowDefinition nullReturning() {
            return new ExerisFlowDefinition() {
                @Override public String name() { return "null-flow"; }
                @Override public FlowDefinition define(FlowDefinitionBuilder builder) { return null; }
            };
        }
    }

    static class NamedFlow implements ExerisFlowDefinition {
        private final String name;
        NamedFlow(String name) { this.name = name; }
        @Override public String name() { return name; }
        @Override public FlowDefinition define(FlowDefinitionBuilder builder) {
            return builder.build();
        }
    }
}
