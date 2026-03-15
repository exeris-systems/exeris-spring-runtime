/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.web;

import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.http.HttpHeader;
import eu.exeris.kernel.spi.http.HttpResponse;
import eu.exeris.kernel.spi.http.HttpStatus;
import eu.exeris.kernel.spi.http.HttpVersion;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import org.springframework.http.MediaType;

import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.List;

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
     * <p>Called exclusively by {@code ExerisHttpDispatcher}, which passes the
     * protocol version from the inbound request so the response honours the same
     * negotiated version.
     *
     * <h2>Body Allocation</h2>
     * <p>When a body is present, this method:
     * <ol>
     *   <li>Acquires the per-request {@link MemoryAllocator} from
     *       {@code KernelProviders.MEMORY_ALLOCATOR} (always bound by the engine
     *       before invoking the handler virtual thread).</li>
     *   <li>Allocates a network-tier {@link LoanedBuffer} sized to the body.</li>
     *   <li>Copies the staged heap bytes to the off-heap segment (one copy — the
     *       unavoidable cost of a String/byte[] API; zero-copy variants using a
     *       pre-allocated buffer are a Phase 2 addition).</li>
     *   <li>Transfers buffer ownership to the {@code HttpResponse} record; the
     *       engine takes final ownership when the exchange is responded.</li>
     * </ol>
     *
     * @param version the HTTP version negotiated on the inbound connection
     * @return the kernel response; never {@code null}
     */
    public HttpResponse toKernelResponse(HttpVersion version) {
        List<HttpHeader> responseHeaders = new ArrayList<>();
        responseHeaders.add(new HttpHeader("Content-Type", contentType));

        if (body == null || body.length == 0) {
            return HttpResponse.noBody(status, version, List.copyOf(responseHeaders));
        }

        MemoryAllocator allocator = KernelProviders.allocator();
        LoanedBuffer buffer = allocator.allocateNetwork(body.length);
        MemorySegment.copy(
                MemorySegment.ofArray(body), 0L,
                buffer.segment(), 0L,
                body.length
        );
        buffer.setSize(body.length);
        responseHeaders.add(new HttpHeader("Content-Length", String.valueOf(body.length)));

        return new HttpResponse(status, version, List.copyOf(responseHeaders), buffer);
    }

    public HttpStatus status() { return status; }
    public String contentType() { return contentType; }
    public byte[] body() { return body; }
}
