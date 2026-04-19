/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.web;

import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.http.MediaType;

import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.http.HttpHeader;
import eu.exeris.kernel.spi.http.HttpResponse;
import eu.exeris.kernel.spi.http.HttpStatus;
import eu.exeris.kernel.spi.http.HttpVersion;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;

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

    private static final System.Logger LOGGER = System.getLogger(ExerisServerResponse.class.getName());
    private static final AtomicBoolean FALLBACK_WARNING_LOGGED = new AtomicBoolean(false);

    private final HttpStatus status;
    private final String contentType;
    private final byte[] body;
    private final List<HttpHeader> extraHeaders;

    private ExerisServerResponse(HttpStatus status, String contentType, byte[] body) {
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.contentType = contentType == null ? MediaType.TEXT_PLAIN_VALUE : contentType;
        this.body = body == null ? new byte[0] : body;
        this.extraHeaders = List.of();
    }

    private ExerisServerResponse(HttpStatus status, String contentType, byte[] body, List<HttpHeader> extraHeaders) {
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.contentType = contentType == null ? MediaType.TEXT_PLAIN_VALUE : contentType;
        this.body = body == null ? new byte[0] : body;
        this.extraHeaders = sanitizeExtraHeaders(extraHeaders);
    }

    public static ExerisServerResponse ok() {
        return new ExerisServerResponse(HttpStatus.OK, MediaType.TEXT_PLAIN_VALUE, new byte[0]);
    }

    public static ExerisServerResponse status(HttpStatus status) {
        return new ExerisServerResponse(status, MediaType.TEXT_PLAIN_VALUE, new byte[0]);
    }

    public ExerisServerResponse contentType(MediaType mediaType) {
        return new ExerisServerResponse(this.status, mediaType == null ? null : mediaType.toString(),
                this.body, this.extraHeaders);
    }

    public ExerisServerResponse body(String text) {
        String safeText = text == null ? "" : text;
        return new ExerisServerResponse(this.status, this.contentType,
                safeText.getBytes(java.nio.charset.StandardCharsets.UTF_8), this.extraHeaders);
    }

    public ExerisServerResponse body(byte[] bytes) {
        return new ExerisServerResponse(this.status, this.contentType,
                bytes == null ? null : Arrays.copyOf(bytes, bytes.length), this.extraHeaders);
    }

    /**
     * Returns a new instance carrying all provided extra headers (compat-mode use only).
     * Pure-mode callers never invoke this method; the default {@code extraHeaders} is an
     * empty immutable list, which costs no extra allocation on the hot path.
     */
    public ExerisServerResponse withHeaders(List<HttpHeader> headers) {
        if (headers == null || headers.isEmpty()) {
            return this;
        }
        return new ExerisServerResponse(this.status, this.contentType, this.body, headers);
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
        addCompatibilityHeaders(responseHeaders);

        if (body.length == 0) {
            responseHeaders.add(new HttpHeader("Content-Length", "0"));
            return HttpResponse.noBody(status, version, List.copyOf(responseHeaders));
        }

        LoanedBuffer buffer;
        if (KernelProviders.MEMORY_ALLOCATOR.isBound()) {
            MemoryAllocator allocator = KernelProviders.allocator();
            buffer = allocator.allocateNetwork(body.length);
            MemorySegment.copy(
                    MemorySegment.ofArray(body), 0L,
                    buffer.segment(), 0L,
                    body.length
            );
            buffer.setSize(body.length);
        } else {
            // Compatibility fallback for non-kernel-scope contexts (e.g., testkit, unit tests).
            // In production, MEMORY_ALLOCATOR is always bound by the kernel memory subsystem.
            if (FALLBACK_WARNING_LOGGED.compareAndSet(false, true)) {
                LOGGER.log(System.Logger.Level.WARNING,
                        "ExerisServerResponse compatibility fallback is active: MEMORY_ALLOCATOR is not bound. "
                                + "Using heap-backed response buffer; this may indicate a production runtime "
                                + "misconfiguration unless running tests/compatibility tooling.");
            }
            buffer = new HeapBodyBuffer(Arrays.copyOf(body, body.length));
        }
        responseHeaders.add(new HttpHeader("Content-Length", String.valueOf(body.length)));

        return new HttpResponse(status, version, List.copyOf(responseHeaders), buffer);
    }

    public HttpStatus status() { return status; }
    public String contentType() { return contentType; }
    public byte[] body() { return Arrays.copyOf(body, body.length); }

    private void addCompatibilityHeaders(List<HttpHeader> responseHeaders) {
        if (extraHeaders.isEmpty()) {
            return;
        }

        for (HttpHeader header : extraHeaders) {
            if (!isValidCompatibilityHeader(header)) {
                continue;
            }
            responseHeaders.add(header);
        }
    }

    private static List<HttpHeader> sanitizeExtraHeaders(List<HttpHeader> headers) {
        if (headers == null || headers.isEmpty()) {
            return List.of();
        }

        List<HttpHeader> sanitized = new ArrayList<>();
        for (HttpHeader header : headers) {
            if (header != null) {
                sanitized.add(header);
            }
        }
        return sanitized.isEmpty() ? List.of() : List.copyOf(sanitized);
    }

    private static boolean isValidCompatibilityHeader(HttpHeader header) {
        if (header == null || header.name() == null || header.value() == null) {
            return false;
        }
        String name = header.name().trim();
        if (name.isEmpty()) {
            return false;
        }
        return !"content-type".equalsIgnoreCase(name) && !"content-length".equalsIgnoreCase(name);
    }

    /**
     * Minimal {@link LoanedBuffer} implementation backed by a heap {@code byte[]}, used as
     * the compatibility fallback in {@link #toKernelResponse} when
     * {@code KernelProviders.MEMORY_ALLOCATOR} is not bound (testkit / unit-test contexts).
     *
     * <p>Lifecycle operations are reference-counted for API consistency, but underlying
     * heap memory is never returned to a pool.
     */
    private static final class HeapBodyBuffer implements LoanedBuffer {

        private final byte[] bytes;
        private final List<Runnable> closeActions = new ArrayList<>();
        private int refCount = 1;
        private boolean alive = true;
        private volatile int size;

        private HeapBodyBuffer(byte[] bytes) {
            this.bytes = bytes == null ? new byte[0] : bytes;
            this.size = this.bytes.length;
        }

        @Override
        public MemorySegment segment() {
            return MemorySegment.ofArray(bytes);
        }

        @Override
        public long size() {
            return size;
        }

        @Override
        public long capacity() {
            return bytes.length;
        }

        @Override
        public LoanedBuffer slice(long offset, long length) {
            int start = checkedIndex(offset, "offset");
            int requestedLength = checkedIndex(length, "length");
            int end = checkedEnd(start, requestedLength, size);
            return new HeapBodyBuffer(Arrays.copyOfRange(bytes, start, end));
        }

        @Override
        public LoanedBuffer view() {
            return this;
        }

        @Override
        public LoanedBuffer peek(long offset, long length) {
            int start = checkedIndex(offset, "offset");
            int requestedLength = checkedIndex(length, "length");
            int end = checkedEnd(start, requestedLength, size);
            return new HeapBodyBuffer(copyRange(start, end));
        }

        @Override
        public synchronized void retain() {
            if (alive) {
                refCount++;
            }
        }

        @Override
        public void close() {
            List<Runnable> actionsToRun = null;
            synchronized (this) {
                if (!alive) {
                    return;
                }
                if (refCount > 0) {
                    refCount--;
                }
                if (refCount == 0) {
                    alive = false;
                    actionsToRun = List.copyOf(closeActions);
                    closeActions.clear();
                }
            }

            if (actionsToRun != null) {
                for (Runnable action : actionsToRun) {
                    action.run();
                }
            }
        }

        @Override
        public synchronized int refCount() {
            return refCount;
        }

        @Override
        public void setSize(long newSize) {
            size = checkedEnd(0, checkedIndex(newSize, "newSize"), bytes.length);
        }

        @Override
        public synchronized boolean isAlive() {
            return alive;
        }

        @Override
        public void addCloseAction(Runnable action) {
            if (action == null) {
                return;
            }

            synchronized (this) {
                if (alive) {
                    closeActions.add(action);
                    return;
                }
            }

            action.run();
        }

        private byte[] copyRange(int start, int end) {
            return Arrays.copyOfRange(bytes, start, end);
        }

        private static int checkedIndex(long value, String label) {
            if (value < 0 || value > Integer.MAX_VALUE) {
                throw new IndexOutOfBoundsException(label + " out of bounds: " + value);
            }
            return Math.toIntExact(value);
        }

        private static int checkedEnd(int offset, int length, int capacity) {
            long end = (long) offset + (long) length;
            if (offset > capacity || end > capacity) {
                throw new IndexOutOfBoundsException(
                        "buffer range out of bounds: offset=" + offset + ", length=" + length + ", capacity=" + capacity);
            }
            return (int) end;
        }
    }
}
