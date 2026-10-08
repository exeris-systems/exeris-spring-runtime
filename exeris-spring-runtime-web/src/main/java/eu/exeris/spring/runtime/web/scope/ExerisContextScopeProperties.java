/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.web.scope;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration properties for request context scope binding.
 *
 * <p>The namespace prefix is {@code exeris.runtime.context.scope} (not
 * {@code exeris.runtime.web.scope}) because related context propagation features
 * (W3C {@code traceparent} propagation and telemetry sinks) bind under
 * {@code exeris.runtime.context.*}. The package owner of the implementation classes
 * is {@code web}; the property reflects the cross-cutting scope of the feature family,
 * not the module ownership.
 *
 * @param enabled when {@code true}, the dispatcher binds {@code ScopedValue<RequestScope>}
 *               around each {@code HttpHandler.handle} invocation, allowing
 *               {@link ExerisRequestScope#current()} and the typed accessors to return values.
 *               Default {@code false} — the disabled path is zero-cost.
 * @since 0.6
 * @see "ADR-029: Kernel-Independent Request Scope and Structured Concurrency Helpers"
 */
@ConfigurationProperties(prefix = "exeris.runtime.context.scope")
public record ExerisContextScopeProperties(@DefaultValue("false") boolean enabled) {
}
