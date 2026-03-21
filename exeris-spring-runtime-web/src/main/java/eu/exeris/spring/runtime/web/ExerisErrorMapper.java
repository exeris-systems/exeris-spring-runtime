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

import java.util.List;

/**
 * Maps application-layer exceptions to kernel {@link HttpResponse} objects.
 *
 * <p>Phase 1 provides a minimal default mapping. Advanced strategies can be registered
 * as Spring beans and composed into this mapper in later phases.
 *
 * @since 0.1.0
 */
public final class ExerisErrorMapper {

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
     * Maps an unhandled application exception to an HTTP 500 response.
     *
     * <p>Does NOT include exception details in the response body; callers should
     * log through the Exeris telemetry pipeline before invoking this method.
     */
    public HttpResponse mapUnhandled(Exception ex, HttpVersion version) {
        return mapStatus(HttpStatus.INTERNAL_SERVER_ERROR, version);
    }

    /**
     * Produces a no-body response for the given status, honoring the negotiated
     * protocol version.
     */
    public HttpResponse mapStatus(HttpStatus status, HttpVersion version) {
        return HttpResponse.noBody(status, version, List.of(
                new HttpHeader("Content-Length", "0")
        ));
    }
}
