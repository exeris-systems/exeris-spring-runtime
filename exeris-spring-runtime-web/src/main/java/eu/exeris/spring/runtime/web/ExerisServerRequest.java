/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.web;

import eu.exeris.kernel.spi.http.HttpRequest;
import eu.exeris.kernel.spi.http.HttpMethod;

import java.util.Objects;

/**
 * Thin, non-copying view over a kernel {@link HttpRequest}.
 *
 * <p>This class presents the application-facing request model without performing
 * any buffer copies. The body is accessible as a {@code MemorySegment} (off-heap)
 * via {@link #bodySegment()} for direct codec consumption.
 *
 * <p>Copying the body to a {@code byte[]} via {@link #bodyBytes()} is intentionally
 * exposed as a separate method that documents and acknowledges the heap allocation.
 *
 * <h2>Ownership</h2>
 * <p>The underlying {@link HttpRequest} body buffer ({@code LoanedBuffer}) is owned
 * by the Exeris engine, not by this request view. The buffer is valid for the
 * lifetime of the handler invocation. It must NOT be retained or referenced after
 * {@code ExerisRequestHandler.handle(request)} returns.
 *
 * @since 0.1.0
 */
public final class ExerisServerRequest {

    private final HttpRequest delegate;

    ExerisServerRequest(HttpRequest delegate) {
        this.delegate = Objects.requireNonNull(delegate, "request must not be null");
    }

    /**
     * Creates a view over a kernel {@link HttpRequest}. Intended for use by
     * compatibility-mode classes in other packages that cannot access the
     * package-private constructor.
     */
    public static ExerisServerRequest wrap(HttpRequest request) {
        return new ExerisServerRequest(Objects.requireNonNull(request, "request must not be null"));
    }

    public HttpMethod method() {
        return delegate.method();
    }

    public String path() {
        return delegate.path();
    }

    public String header(String name) {
        return delegate.firstHeader(name).orElse(null);
    }

    /**
     * Returns the request body as an off-heap {@code MemorySegment}.
     *
     * <p>Zero allocation. The segment is valid only during handler execution.
     * Codecs that support {@code MemorySegment}-based reads should use this method.
     *
     * @return the body segment; may be empty if no body was sent
     */
    public java.lang.foreign.MemorySegment bodySegment() {
        var body = delegate.body();
        return body != null ? body.segment() : java.lang.foreign.MemorySegment.NULL;
    }

    /**
     * Returns the request body as a heap {@code byte[]}.
     *
     * <p><strong>Heap allocation:</strong> this method copies from the off-heap buffer.
     * Use only when required by a framework that cannot consume {@code MemorySegment}
     * directly (e.g., Jackson in compatibility mode). In pure mode, prefer
     * {@link #bodySegment()}.
     *
     * @return the body bytes; empty array if no body was sent
     */
    public byte[] bodyBytes() {
        var segment = bodySegment();
        if (segment == java.lang.foreign.MemorySegment.NULL || segment.byteSize() == 0) {
            return new byte[0];
        }
        return segment.toArray(java.lang.foreign.ValueLayout.JAVA_BYTE);
    }

    public HttpRequest kernelRequest() {
        return delegate;
    }
}
