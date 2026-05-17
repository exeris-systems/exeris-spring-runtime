/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.web.scope;

import eu.exeris.spring.runtime.web.ExerisServerRequest;

/**
 * Extension point for applications to build a {@link RequestScope} from an incoming request.
 *
 * <p>An application that opts into request scope (`exeris.runtime.context.scope.enabled=true`)
 * registers a single {@code @Bean RequestScopeResolver} that knows how to extract
 * tenant ID, correlation ID, and any other request-scoped attributes from the request
 * (typically from headers, claims, or query parameters).
 *
 * <p>The dispatcher calls {@link #resolve(ExerisServerRequest)} exactly once per request and
 * binds the returned {@code RequestScope} via {@code ScopedValue.where(...).run(...)} around
 * the handler invocation. The implementation is on the request hot path and should be
 * allocation-light.
 *
 * <p>If no bean of this type is registered while the property is enabled, the dispatcher
 * falls back to {@link RequestScope#empty()} — the scope is bound but carries no identity.
 * This is the no-op opt-in: the structured-concurrency wrapper still propagates the (empty)
 * scope across forks, but the typed accessors return {@link java.util.Optional#empty()}.
 *
 * @since 0.6.0
 */
@FunctionalInterface
public interface RequestScopeResolver {

    /**
     * Build a {@link RequestScope} for the given request. Must not return {@code null};
     * use {@link RequestScope#empty()} for the "no identity" case.
     */
    RequestScope resolve(ExerisServerRequest request);
}
