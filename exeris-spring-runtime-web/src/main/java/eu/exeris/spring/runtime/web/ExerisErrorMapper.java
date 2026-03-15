/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.web;

import eu.exeris.kernel.spi.http.HttpResponse;
import eu.exeris.kernel.spi.http.HttpStatus;
import eu.exeris.kernel.spi.exceptions.http.HttpException;

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
     * Maps a {@link HttpException} to a minimal error response preserving the status code.
     */
    public HttpResponse map(HttpException ex) {
        // TODO Phase 1: extract status once kernel HttpException exposes structured status metadata.
        return mapStatus(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    /**
     * Maps an unhandled application exception to an HTTP 500 response.
     *
     * <p>Does NOT include exception details in the response body; callers should log
     * through the Exeris telemetry pipeline before invoking this method.
     */
    public HttpResponse mapUnhandled(Exception ex) {
        return mapStatus(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    /**
     * Produces a minimal response for the given status with no body.
     */
    public HttpResponse mapStatus(HttpStatus status) {
        // TODO Phase 1: replace with real HttpResponse.builder() call
        throw new UnsupportedOperationException(
                "ExerisErrorMapper.mapStatus() is not yet implemented. " +
                "Complete Phase 1 implementation against the exeris-kernel HttpResponse builder API.");
    }
}
