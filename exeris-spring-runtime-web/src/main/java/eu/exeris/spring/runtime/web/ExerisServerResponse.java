/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.web;

import eu.exeris.kernel.spi.http.HttpResponse;
import eu.exeris.kernel.spi.http.HttpStatus;
import org.springframework.http.MediaType;

/**
 * Application-facing HTTP response builder for Pure Mode handlers.
 *
 * <p>Handlers create an {@code ExerisServerResponse} via the static factory methods,
 * then return it to {@code ExerisHttpDispatcher}. The dispatcher converts it to a
 * kernel {@link HttpResponse} and writes it to the wire. Body buffer ownership is
 * transferred to the engine on write.
 *
 * <h2>Body Ownership</h2>
 * <p>When a body is set via {@link #body(String)} or similar methods, the string/bytes
 * are staged in heap for simplicity in Phase 1. Phase 1 targets string/text responses
 * primarily. For zero-copy binary responses (Phase 1+), a {@code LoanedBuffer} overload
 * will be added — the buffer must not be released by the caller after passing it here.
 *
 * @since 0.1.0
 */
public final class ExerisServerResponse {

    private final HttpStatus status;
    private final String contentType;
    private final byte[] body;

    private ExerisServerResponse(HttpStatus status, String contentType, byte[] body) {
        this.status = status;
        this.contentType = contentType;
        this.body = body;
    }

    public static ExerisServerResponse ok() {
        return new ExerisServerResponse(HttpStatus.OK, MediaType.TEXT_PLAIN_VALUE, new byte[0]);
    }

    public static ExerisServerResponse status(HttpStatus status) {
        return new ExerisServerResponse(status, MediaType.TEXT_PLAIN_VALUE, new byte[0]);
    }

    public ExerisServerResponse contentType(MediaType mediaType) {
        return new ExerisServerResponse(this.status, mediaType.toString(), this.body);
    }

    public ExerisServerResponse body(String text) {
        return new ExerisServerResponse(this.status, this.contentType,
                text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    public ExerisServerResponse body(byte[] bytes) {
        return new ExerisServerResponse(this.status, this.contentType, bytes);
    }

    /**
     * Converts to the kernel {@link HttpResponse}.
     *
     * <p>Called exclusively by {@code ExerisHttpDispatcher}. The returned
     * {@code HttpResponse} will have its body buffer owned by the engine after
     * {@code HttpExchange.respond()} is called.
     *
     * <p>Phase 1 note: body bytes are copied into a {@code LoanedBuffer} here.
     * Phase 2+ will allow pre-allocated {@code LoanedBuffer} pass-through.
     *
     * @return the kernel response; never {@code null}
     */
    public HttpResponse toKernelResponse() {
        /*
         * Full implementation in Phase 1 once KernelBootstrap + HttpResponse
         * builder API is confirmed against exeris-kernel 0.5.0-SNAPSHOT.
         * The builder will allocate a LoanedBuffer via MemoryAllocator and
         * copy the body bytes off-heap.
         */
        throw new UnsupportedOperationException(
                "ExerisServerResponse.toKernelResponse() is not yet implemented. " +
                "Complete Phase 1 implementation against exeris-kernel HttpResponse builder API.");
    }

    public HttpStatus status() { return status; }
    public String contentType() { return contentType; }
    public byte[] body() { return body; }
}
