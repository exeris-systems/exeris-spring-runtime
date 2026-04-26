/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.web.compat;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import eu.exeris.kernel.spi.http.HttpHeader;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpRequest;
import eu.exeris.kernel.spi.http.HttpVersion;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.spring.runtime.web.ExerisServerRequest;

class ExerisMvcServerHttpRequestTest {

    @Test
    void exposesMethodPathAndHeaders() {
        HttpRequest kernelRequest = HttpRequest.noBody(
                HttpMethod.POST,
                "/compat/path?x=1",
                HttpVersion.HTTP_1_1,
                List.of(new HttpHeader(HttpHeaders.ACCEPT, "application/json"))
        );

        ExerisMvcServerHttpRequest request = new ExerisMvcServerHttpRequest(ExerisServerRequest.wrap(kernelRequest));

        assertThat(request.getMethod().name()).isEqualTo("POST");
        assertThat(request.getURI().getPath()).isEqualTo("/compat/path");
        assertThat(request.getURI().getQuery()).isEqualTo("x=1");
        assertThat(request.getHeaders().getFirst(HttpHeaders.ACCEPT)).isEqualTo("application/json");
    }

    @Test
    void exposesBodyAsDeterministicInputStream() throws IOException {
        byte[] payload = "compat-body".getBytes(StandardCharsets.UTF_8);
        HttpRequest kernelRequest = new HttpRequest(
                HttpMethod.PUT,
                "/compat/body",
                HttpVersion.HTTP_1_1,
                List.of(),
                new TestLoanedBuffer(payload)
        );

        ExerisMvcServerHttpRequest request = new ExerisMvcServerHttpRequest(ExerisServerRequest.wrap(kernelRequest));

        assertThat(request.getBody().readAllBytes()).isEqualTo(payload);
    }

    private static final class TestLoanedBuffer implements LoanedBuffer {

        private final byte[] bytes;
        private final List<Runnable> closeActions = new ArrayList<>();
        private boolean alive = true;
        private int refCount = 1;

        private TestLoanedBuffer(byte[] bytes) {
            this.bytes = bytes;
        }

        @Override
        public MemorySegment segment() {
            return MemorySegment.ofArray(bytes);
        }

        @Override
        public long size() {
            return bytes.length;
        }

        @Override
        public long capacity() {
            return bytes.length;
        }

        @Override
        public LoanedBuffer slice(long offset, long length) {
            int start = (int) offset;
            int end = (int) (offset + length);
            return new TestLoanedBuffer(java.util.Arrays.copyOfRange(bytes, start, end));
        }

        @Override
        public LoanedBuffer view() {
            return this;
        }

        @Override
        public LoanedBuffer peek(long offset, long length) {
            return slice(offset, length);
        }

        @Override
        public synchronized void retain() {
            if (alive) {
                refCount++;
            }
        }

        @Override
        public void close() {
            List<Runnable> actions = null;
            synchronized (this) {
                if (!alive) {
                    return;
                }
                refCount = Math.max(0, refCount - 1);
                if (refCount == 0) {
                    alive = false;
                    actions = List.copyOf(closeActions);
                    closeActions.clear();
                }
            }
            if (actions != null) {
                for (Runnable action : actions) {
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
            // fixed-size backing array for test scaffold
        }

        @Override
        public synchronized boolean isAlive() {
            return alive;
        }

        @Override
        public synchronized void addCloseAction(Runnable action) {
            if (action == null) {
                return;
            }
            if (alive) {
                closeActions.add(action);
            } else {
                action.run();
            }
        }
    }
}
