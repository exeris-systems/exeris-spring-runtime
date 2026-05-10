/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.flow;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration properties for the Exeris Flow / Saga bridge module (Phase 4B).
 *
 * <p>All optional flags default to {@code false}. The flow module is opt-in and never
 * activates implicitly.
 *
 * <ul>
 *   <li>{@link #enabled()} — master switch for the flow module; gates
 *       {@code ExerisFlowAutoConfiguration} via {@code @ConditionalOnProperty}.</li>
 *   <li>{@link #persistenceEnabled()} — gates durable snapshot persistence. Held back
 *       at {@code false} in {@code 0.5.0-preview}: kernel 0.7.0 ships a Community
 *       {@code JdbcFlowSnapshotStore}, but the Spring-side wiring that binds it through
 *       {@code KernelProviders.FLOW_SNAPSHOT_STORE} is sequenced for Phase 4B Step 4
 *       closure (see Phase 4B plan).</li>
 *   <li>{@link #choreographyEnabled()} — gates event-driven flow triggers via the
 *       choreography bridge (Step 3); additionally requires
 *       {@code FlowEngineCapabilities.choreographySupport()} on the kernel side.</li>
 *   <li>{@link #requireEngine()} — defaults to {@code true}. When {@code ExerisFlowDefinition}
 *       beans are declared but the kernel did not bind a {@code FlowEngine} during
 *       bootstrap, the registrar fails the lifecycle start instead of silently dropping
 *       compiled plans. Test harnesses that intentionally skip kernel boot opt out by
 *       setting this to {@code false} (mirrors {@code exeris.runtime.events.require-engine}).</li>
 * </ul>
 *
 * @param enabled              master switch for the flow module — default {@code false}
 * @param persistenceEnabled   gates durable snapshot persistence; default {@code false}
 * @param choreographyEnabled  gates event-driven flow triggers; default {@code false}
 * @param requireEngine        fail-loud posture when definitions are declared without an
 *                             engine; default {@code true}
 * @since 0.1.0
 */
@ConfigurationProperties(prefix = "exeris.runtime.flow")
public record ExerisFlowProperties(

        @DefaultValue("false") boolean enabled,
        @DefaultValue("false") boolean persistenceEnabled,
        @DefaultValue("false") boolean choreographyEnabled,
        @DefaultValue("true") boolean requireEngine

) {

    @ConstructorBinding
    public ExerisFlowProperties {
    }
}
