/*
 * Copyright (C) 2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.spring.runtime.web;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpRequest;
import eu.exeris.kernel.spi.http.HttpVersion;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.spring.runtime.web.compat.ExerisMvcServerHttpRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A request body is the first {@code size()} bytes of its buffer, never the whole {@code capacity()}.
 *
 * <p>The kernel lends pooled buffers: the bytes past {@code size()} are whatever an earlier request
 * left there. Exposing them hands one caller's data to another, so every body accessor is checked
 * against a buffer whose tail holds bytes that must not be seen.
 */
class ExerisServerRequestBodyTest {

    private static final byte[] BODY = "b".getBytes(StandardCharsets.UTF_8);
    private static final byte[] STALE = "secret-from-an-earlier-request".getBytes(StandardCharsets.UTF_8);

    @Test
    void bodyBytesHoldOnlyTheValidBytes() {
        assertThat(request().bodyBytes()).isEqualTo(BODY);
    }

    @Test
    void bodySegmentSpansOnlyTheValidBytes() {
        MemorySegment segment = request().bodySegment();

        assertThat(segment.byteSize()).isEqualTo(BODY.length);
        assertThat(segment.toArray(ValueLayout.JAVA_BYTE)).isEqualTo(BODY);
    }

    @Test
    void theCompatibilityModeBodyStreamHoldsOnlyTheValidBytes() throws Exception {
        ExerisMvcServerHttpRequest compat = new ExerisMvcServerHttpRequest(request());

        assertThat(compat.getBody().readAllBytes()).isEqualTo(BODY);
    }

    @Test
    void anEmptyBodyInADirtyBufferIsEmpty() {
        ExerisServerRequest request = ExerisServerRequest.wrap(new HttpRequest(
                HttpMethod.POST, "/echo", HttpVersion.HTTP_1_1, List.of(), new PooledBuffer(new byte[0], STALE)));

        assertThat(request.bodyBytes()).isEmpty();
        assertThat(request.bodySegment().byteSize()).isZero();
    }

    private static ExerisServerRequest request() {
        return ExerisServerRequest.wrap(new HttpRequest(
                HttpMethod.POST, "/echo", HttpVersion.HTTP_1_1, List.of(), new PooledBuffer(BODY, STALE)));
    }

    /** A buffer whose capacity exceeds its size, with the tail already written, as a reused pool slot is. */
    private static final class PooledBuffer implements LoanedBuffer {

        private final byte[] backing;
        private final long size;
        private int refCount = 1;

        PooledBuffer(byte[] valid, byte[] staleTail) {
            this.backing = new byte[valid.length + staleTail.length];
            System.arraycopy(valid, 0, backing, 0, valid.length);
            System.arraycopy(staleTail, 0, backing, valid.length, staleTail.length);
            this.size = valid.length;
        }

        @Override
        public MemorySegment segment() {
            return MemorySegment.ofArray(backing);
        }

        @Override
        public long size() {
            return size;
        }

        @Override
        public long capacity() {
            return backing.length;
        }

        @Override
        public LoanedBuffer slice(long offset, long length) {
            throw new UnsupportedOperationException();
        }

        @Override
        public LoanedBuffer view() {
            return this;
        }

        @Override
        public LoanedBuffer peek(long offset, long length) {
            throw new UnsupportedOperationException();
        }

        @Override
        public synchronized void retain() {
            refCount++;
        }

        @Override
        public synchronized void close() {
            refCount = Math.max(0, refCount - 1);
        }

        @Override
        public synchronized int refCount() {
            return refCount;
        }

        @Override
        public void setSize(long newSize) {
            throw new UnsupportedOperationException();
        }

        @Override
        public synchronized boolean isAlive() {
            return refCount > 0;
        }

        @Override
        public void addCloseAction(Runnable action) {
            // not exercised
        }
    }
}
