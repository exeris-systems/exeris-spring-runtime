/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.events;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration for the events bridge.
 *
 * <p>Activation of the bridge itself is gated by {@code exeris.runtime.events.enabled}
 * via {@code @ConditionalOnProperty} on {@link ExerisEventAutoConfiguration} — that
 * property is consumed directly from the {@code Environment} and does not appear as a
 * field on this record because the autoconfig already runs before bean construction.
 *
 * <h2>Engine Requirement</h2>
 * <p>{@link #requireEngine()} defaults to {@code true}. When enabled, lifecycle start fails
 * if {@code @ExerisEventListener} methods are declared but no kernel {@code EventEngine}
 * is bound. Setting {@code exeris.runtime.events.require-engine=false} disables this check.
 *
 * @param requireEngine whether to require a kernel event engine when listeners are present
 * @since 0.1
 */
@ConfigurationProperties(prefix = "exeris.runtime.events")
public record ExerisEventProperties(
        @DefaultValue("true") boolean requireEngine
) {

    /**
     * Creates a new instance.
     *
     * @param requireEngine whether to require a kernel event engine
     */
    @ConstructorBinding
    public ExerisEventProperties {
    }
}
