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
 * Phase 3B-α context-scope properties (ADR-029).
 *
 * <p>The namespace prefix is {@code exeris.runtime.context.scope} (not
 * {@code exeris.runtime.web.scope}) because it is reserved for the full Phase 3B family —
 * 3B-α (this ADR), 3B-β (W3C {@code traceparent} propagation, ADR-031), and 3B-γ (OTel sink,
 * ADR-031) all bind under {@code exeris.runtime.context.*}. The package owner of the
 * implementation classes is {@code web}; the property reflects the cross-cutting scope of the
 * feature family, not the module ownership.
 *
 * @param enabled when {@code true}, the dispatcher binds {@code ScopedValue<RequestScope>}
 *               around each {@code HttpHandler.handle} invocation, allowing
 *               {@link ExerisRequestScope#current()} and the typed accessors to return values.
 *               Default {@code false} — the disabled path is zero-cost.
 * @since 0.6.0
 */
@ConfigurationProperties(prefix = "exeris.runtime.context.scope")
public record ExerisContextScopeProperties(@DefaultValue("false") boolean enabled) {
}
