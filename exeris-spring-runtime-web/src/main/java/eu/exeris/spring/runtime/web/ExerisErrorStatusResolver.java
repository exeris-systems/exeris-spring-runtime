/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.web;

import java.util.Optional;

/**
 * Strategy for turning an unhandled exception into a specific HTTP status before
 * {@link ExerisErrorMapper} falls back to 500.
 *
 * <h2>Why this seam exists</h2>
 * <p>{@link ExerisErrorMapper} is created unconditionally on the pure-mode path and therefore
 * cannot reference optional dependencies. Spring Security is the concrete case: its
 * {@code AuthenticationException} must become a 401 and {@code AccessDeniedException} a 403, but
 * {@code spring-security-core} is an optional dependency of this module. A direct {@code instanceof}
 * in the mapper would make the class unloadable wherever Spring Security is absent — which is the
 * default. Resolvers are registered conditionally instead, so the mapper stays free of types it
 * cannot guarantee.
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>Return {@link Optional#empty()} for anything the resolver does not recognise. Never throw
 *       — a resolver that throws is swallowed and treated as "no opinion", because an error-mapping
 *       failure must not replace the original error.</li>
 *   <li>Resolvers are consulted in registration order; the first non-empty result wins. Order
 *       between two resolvers claiming the same exception is unspecified, so do not rely on it —
 *       declare disjoint interest instead.</li>
 *   <li>Called on the error path only, never on a successful dispatch. It is not a hot path, but it
 *       runs on the request thread: no blocking I/O.</li>
 * </ul>
 *
 * <h2>Mode</h2>
 * <p>Mode-neutral. The seam lives on the pure-mode path; individual resolvers declare their own
 * mode.
 *
 * @see ExerisErrorMapper#mapUnhandled(Exception, eu.exeris.kernel.spi.http.HttpVersion)
 * @since 0.7.0
 */
@FunctionalInterface
public interface ExerisErrorStatusResolver {

    /**
     * Resolves the status for an unhandled exception.
     *
     * @param exception the exception that escaped dispatch; never {@code null}
     * @return the status to respond with, or {@link Optional#empty()} to defer to the next
     *         resolver (and ultimately to the 500 fallback)
     */
    Optional<ExerisErrorStatus> resolve(Throwable exception);
}
