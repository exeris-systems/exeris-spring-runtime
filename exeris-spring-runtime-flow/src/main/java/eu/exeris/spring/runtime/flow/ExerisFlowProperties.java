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
 * <p>All flags default to {@code false}. The flow module is opt-in and never activates
 * implicitly. {@link #enabled()} gates the entire module; {@link #persistenceEnabled()}
 * gates durable snapshot persistence (deliberately held back in {@code 0.5.0-preview} —
 * see Phase 4B plan); {@link #choreographyEnabled()} gates event-driven flow triggers
 * (which additionally require {@code FlowEngineCapabilities.choreographySupport()} on
 * the kernel side).
 *
 * @param enabled              master switch for the flow module — default {@code false}
 * @param persistenceEnabled   gates durable snapshot persistence; default {@code false}
 *                             until an Exeris-owned {@code FlowSnapshotStore} ships in
 *                             tx or data module
 * @param choreographyEnabled  gates event-driven flow triggers via
 *                             {@code ExerisFlowChoreographyBridge}; default {@code false};
 *                             additionally requires the kernel to advertise
 *                             {@code choreographySupport()=true}
 * @since 0.1.0
 */
@ConfigurationProperties(prefix = "exeris.runtime.flow")
public record ExerisFlowProperties(

        @DefaultValue("false") boolean enabled,
        @DefaultValue("false") boolean persistenceEnabled,
        @DefaultValue("false") boolean choreographyEnabled

) {

    /**
     * Canonical constructor anchor for Spring Boot's {@code @ConfigurationProperties}
     * binder. Required because a convenience no-arg constructor is also present.
     */
    @ConstructorBinding
    public ExerisFlowProperties {
    }

    /**
     * Convenience constructor for direct instantiation outside a Spring context.
     * Mirrors the all-default state.
     */
    public ExerisFlowProperties() {
        this(false, false, false);
    }
}
