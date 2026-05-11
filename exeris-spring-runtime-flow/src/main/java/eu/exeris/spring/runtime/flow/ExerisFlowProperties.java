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
 * <p>The flow module is opt-in via {@link #enabled()}; once enabled, the remaining
 * flags steer behaviour for durable persistence and event-driven choreography.
 *
 * <ul>
 *   <li>{@link #enabled()} — master switch for the flow module; gates
 *       {@code ExerisFlowAutoConfiguration} via {@code @ConditionalOnProperty}.
 *       Default {@code false}.</li>
 *   <li>{@link #persistenceEnabled()} — gates durable snapshot persistence via the
 *       kernel's {@code JdbcFlowSnapshotStore}. Default {@code true} from
 *       {@code 0.5.0-preview} (kernel 0.8.0 + ADR-022). Parked flows survive a JVM
 *       restart when a JDBC {@code PersistenceEngine} is bound; the kernel falls back
 *       to the in-memory {@code CommunityFlowSnapshotStore} when no JDBC engine is
 *       available, so setting this to {@code true} is safe even without persistence
 *       wired. Applications that explicitly do NOT want any snapshot writes (pure
 *       fire-and-forget flows) can disable by setting this to {@code false}.</li>
 *   <li>{@link #choreographyEnabled()} — gates event-driven flow triggers via the
 *       choreography bridge (Step 3); additionally requires
 *       {@code FlowEngineCapabilities.choreographySupport()} on the kernel side.
 *       Default {@code false}.</li>
 *   <li>{@link #requireEngine()} — defaults to {@code true}. When {@code ExerisFlowDefinition}
 *       beans are declared but the kernel did not bind a {@code FlowEngine} during
 *       bootstrap, the registrar fails the lifecycle start instead of silently dropping
 *       compiled plans. Test harnesses that intentionally skip kernel boot opt out by
 *       setting this to {@code false} (mirrors {@code exeris.runtime.events.require-engine}).</li>
 * </ul>
 *
 * <h2>Kernel propagation</h2>
 * <p>{@code persistenceEnabled} reaches the kernel through
 * {@code ExerisSpringConfigProvider.flowKernelKeyAlias} which bridges
 * {@code exeris.runtime.flow.persistence-enabled} → {@code flow.persistenceEnabled}
 * (the key the kernel's {@code CommunityFlowSubsystem.buildFlowConfig()} reads).
 *
 * @param enabled              master switch for the flow module — default {@code false}
 * @param persistenceEnabled   gates durable snapshot persistence; default {@code true}
 *                             (kernel falls back to in-memory when no JDBC engine bound)
 * @param choreographyEnabled  gates event-driven flow triggers; default {@code false}
 * @param requireEngine        fail-loud posture when definitions are declared without an
 *                             engine; default {@code true}
 * @since 0.1.0
 */
@ConfigurationProperties(prefix = "exeris.runtime.flow")
public record ExerisFlowProperties(

        @DefaultValue("false") boolean enabled,
        @DefaultValue("true") boolean persistenceEnabled,
        @DefaultValue("false") boolean choreographyEnabled,
        @DefaultValue("true") boolean requireEngine

) {

    @ConstructorBinding
    public ExerisFlowProperties {
    }
}
