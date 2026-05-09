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
 * <h2>Posture: fail loud when half-configured</h2>
 * <p>{@link #requireEngine()} defaults to {@code true}. When the application has declared
 * {@code @ExerisEventListener} methods but the kernel did not bind an {@code EventEngine}
 * during bootstrap (no {@code EventProvider} on the classpath, kernel events subsystem
 * disabled, etc.), the listener registrar fails the lifecycle start instead of silently
 * leaving listeners unsubscribed. Operators see the misconfiguration immediately rather
 * than discovering it through unfired event handlers in production.
 *
 * <p>Test harnesses that intentionally skip kernel bootstrap (e.g. Spring Boot test slices
 * with {@code exeris.runtime.auto-start=false}) opt out by setting
 * {@code exeris.runtime.events.require-engine=false}.
 *
 * @since 0.1.0
 */
@ConfigurationProperties(prefix = "exeris.runtime.events")
public record ExerisEventProperties(
        @DefaultValue("true") boolean requireEngine
) {

    @ConstructorBinding
    public ExerisEventProperties {
    }
}
