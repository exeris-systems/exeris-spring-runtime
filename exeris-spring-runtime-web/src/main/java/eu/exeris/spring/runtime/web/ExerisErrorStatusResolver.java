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
 * <p><b>Contract:</b>
 * <ul>
 *   <li>Return {@link Optional#empty()} for anything the resolver does not recognise. Never throw
 *       — a resolver that throws is treated as "no opinion", because an error-mapping failure must not
 *       replace the original error.</li>
 *   <li>Resolvers are consulted in registration order; the first non-empty result wins. Declare disjoint
 *       interest rather than relying on order.</li>
 *   <li>Called on the error path only, never on successful dispatch. Runs on the request thread: no blocking I/O.</li>
 * </ul>
 *
 * <p><b>Mode:</b> Mode-neutral. The seam lives on the pure-mode path; individual resolvers declare their own mode.
 *
 * @implNote Keeps {@link ExerisErrorMapper} decoupled from optional framework dependencies
 *     (such as Spring Security). Resolvers are registered conditionally so the pure-mode
 *     mapper stays free of classes not guaranteed to be present on the classpath.
 * @since 0.7
 * @see ExerisErrorMapper#mapUnhandled(Exception, eu.exeris.kernel.spi.http.HttpVersion)
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
