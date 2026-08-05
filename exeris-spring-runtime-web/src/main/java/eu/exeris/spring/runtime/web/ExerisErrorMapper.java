/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.web;

import eu.exeris.kernel.spi.exceptions.http.HttpException;
import eu.exeris.kernel.spi.http.HttpHeader;
import eu.exeris.kernel.spi.http.HttpResponse;
import eu.exeris.kernel.spi.http.HttpStatus;
import eu.exeris.kernel.spi.http.HttpVersion;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Maps application-layer exceptions to kernel {@link HttpResponse} objects.
 *
 * <p>Phase 1 provides a minimal default mapping. Advanced strategies can be registered
 * as Spring beans and composed into this mapper in later phases.
 *
 * <h2>Resolvers</h2>
 * <p>{@link #mapUnhandled} consults the registered {@link ExerisErrorStatusResolver}s before
 * falling back to 500. Without a resolver, every escaping exception is a 500 — including
 * Spring Security's {@code AuthenticationException} and {@code AccessDeniedException}, which
 * turned an authorization outcome into an apparent server fault. The mapper itself stays free of
 * Spring Security types because it is created unconditionally and that dependency is optional;
 * see {@link ExerisErrorStatusResolver} for the full reasoning.
 *
 * @since 0.1.0
 */
public final class ExerisErrorMapper {

    private static final System.Logger LOGGER =
            System.getLogger(ExerisErrorMapper.class.getName());

    private final List<ExerisErrorStatusResolver> statusResolvers;

    /**
     * Creates a mapper with no resolvers — every unhandled exception maps to 500.
     */
    public ExerisErrorMapper() {
        this(List.of());
    }

    /**
     * Creates a mapper that consults the given resolvers, in order, before the 500 fallback.
     *
     * @param statusResolvers resolvers to consult; never {@code null}, may be empty
     */
    public ExerisErrorMapper(List<ExerisErrorStatusResolver> statusResolvers) {
        this.statusResolvers = List.copyOf(
                Objects.requireNonNull(statusResolvers, "statusResolvers must not be null"));
    }

    /**
     * Maps a {@link HttpException} to an HTTP 500 response.
     *
     * <p>Phase 1: returns a generic 500. A future phase will expose structured
     * status metadata on {@code HttpException} to allow specific status mapping.
     */
    public HttpResponse map(HttpException ex, HttpVersion version) {
        return mapStatus(HttpStatus.INTERNAL_SERVER_ERROR, version);
    }

    /**
     * Maps an unhandled application exception to a resolved status, or to HTTP 500 when no
     * resolver claims it.
     *
     * <p>Does NOT include exception details in the response body; callers should
     * log through the Exeris telemetry pipeline before invoking this method.
     */
    public HttpResponse mapUnhandled(Exception ex, HttpVersion version) {
        return resolve(ex)
                .map(resolved -> mapStatus(resolved.status(), version, resolved.headers()))
                .orElseGet(() -> mapStatus(HttpStatus.INTERNAL_SERVER_ERROR, version));
    }

    /**
     * Runs the resolver chain. A resolver that throws is treated as having no opinion: a failure
     * inside error mapping must not replace the error being mapped, which would lose the original
     * cause and produce a second, unrelated 500.
     */
    private Optional<ExerisErrorStatus> resolve(Exception ex) {
        for (ExerisErrorStatusResolver resolver : statusResolvers) {
            try {
                Optional<ExerisErrorStatus> resolved = resolver.resolve(ex);
                if (resolved.isPresent()) {
                    return resolved;
                }
            } catch (RuntimeException resolverFailure) {
                LOGGER.log(System.Logger.Level.WARNING,
                        () -> "Exeris error-status resolver " + resolver.getClass().getName()
                                + " threw while mapping " + ex.getClass().getName()
                                + "; ignoring it and continuing the chain", resolverFailure);
            }
        }
        return Optional.empty();
    }

    /**
     * Produces a no-body response for the given status, honoring the negotiated
     * protocol version.
     */
    public HttpResponse mapStatus(HttpStatus status, HttpVersion version) {
        return mapStatus(status, version, List.of());
    }

    /**
     * Produces a no-body response for the given status with additional status-mandated headers
     * (e.g. {@code WWW-Authenticate} on a 401).
     */
    public HttpResponse mapStatus(HttpStatus status, HttpVersion version, List<HttpHeader> headers) {
        List<HttpHeader> all = new ArrayList<>(headers.size() + 1);
        all.addAll(headers);
        all.add(new HttpHeader("Content-Length", "0"));
        return HttpResponse.noBody(status, version, List.copyOf(all));
    }
}
