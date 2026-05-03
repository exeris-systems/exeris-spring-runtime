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
    void localAddressIsNull_kernelDoesNotExposeBoundSocket() {
        ExerisMvcServerHttpRequest request = newRequest(List.of());
        assertThat(request.getLocalAddress()).isNull();
    }

    @Test
    void remoteAddressNullWhenNoForwardingHeaders() {
        ExerisMvcServerHttpRequest request = newRequest(List.of());
        assertThat(request.getRemoteAddress()).isNull();
    }

    @Test
    void remoteAddressFromXForwardedFor_takesFirstHop() {
        ExerisMvcServerHttpRequest request = newRequest(List.of(
                new HttpHeader("X-Forwarded-For", "203.0.113.7, 198.51.100.1, 10.0.0.1")));
        java.net.InetSocketAddress addr = request.getRemoteAddress();
        assertThat(addr).isNotNull();
        assertThat(addr.getHostString()).isEqualTo("203.0.113.7");
        assertThat(addr.getPort()).isEqualTo(0);
    }

    @Test
    void remoteAddressFromXRealIp_whenForwardedAbsent() {
        ExerisMvcServerHttpRequest request = newRequest(List.of(
                new HttpHeader("X-Real-IP", "203.0.113.42")));
        java.net.InetSocketAddress addr = request.getRemoteAddress();
        assertThat(addr).isNotNull();
        assertThat(addr.getHostString()).isEqualTo("203.0.113.42");
    }

    @Test
    void remoteAddressFromForwardedHeader_parsesIpv4WithPort() {
        ExerisMvcServerHttpRequest request = newRequest(List.of(
                new HttpHeader("Forwarded", "for=192.0.2.43:4711;proto=https;by=10.0.0.1")));
        java.net.InetSocketAddress addr = request.getRemoteAddress();
        assertThat(addr).isNotNull();
        assertThat(addr.getHostString()).isEqualTo("192.0.2.43");
        assertThat(addr.getPort()).isEqualTo(4711);
    }

    @Test
    void remoteAddressFromForwardedHeader_parsesQuotedIpv6() {
        ExerisMvcServerHttpRequest request = newRequest(List.of(
                new HttpHeader("Forwarded", "for=\"[2001:db8::1]:8080\"")));
        java.net.InetSocketAddress addr = request.getRemoteAddress();
        assertThat(addr).isNotNull();
        assertThat(addr.getHostString()).isEqualTo("2001:db8::1");
        assertThat(addr.getPort()).isEqualTo(8080);
    }

    @Test
    void remoteAddressForwardedHeader_takesFirstEntry() {
        ExerisMvcServerHttpRequest request = newRequest(List.of(
                new HttpHeader("Forwarded", "for=203.0.113.7, for=198.51.100.1")));
        java.net.InetSocketAddress addr = request.getRemoteAddress();
        assertThat(addr).isNotNull();
        assertThat(addr.getHostString()).isEqualTo("203.0.113.7");
    }

    @Test
    void remoteAddressForwardedHeader_returnsNullWhenForIsUnknown() {
        ExerisMvcServerHttpRequest request = newRequest(List.of(
                new HttpHeader("Forwarded", "for=unknown")));
        assertThat(request.getRemoteAddress()).isNull();
    }

    private static ExerisMvcServerHttpRequest newRequest(List<HttpHeader> headers) {
        return new ExerisMvcServerHttpRequest(ExerisServerRequest.wrap(
                HttpRequest.noBody(HttpMethod.GET, "/compat", HttpVersion.HTTP_1_1, headers)));
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
