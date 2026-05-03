/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.web.compat;

import eu.exeris.kernel.spi.http.HttpHeader;
import eu.exeris.spring.runtime.web.ExerisServerRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.ServerHttpAsyncRequestControl;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Minimal compatibility adapter exposing {@link ExerisServerRequest} as Spring's
 * {@link ServerHttpRequest} without any servlet dependency.
 */
public final class ExerisMvcServerHttpRequest implements ServerHttpRequest {

    private final ExerisServerRequest delegate;
    private final HttpHeaders headers;
    private final URI uri;
    private final Map<String, Object> attributes = new LinkedHashMap<>();

    public ExerisMvcServerHttpRequest(ExerisServerRequest delegate) {
        this.delegate = delegate;
        this.headers = toSpringHeaders(delegate);
        this.uri = toUri(delegate.path());
    }

    @Override
    @NonNull
    public HttpMethod getMethod() {
        String methodName = Objects.requireNonNull(delegate.method().name());
        return Objects.requireNonNull(HttpMethod.valueOf(methodName));
    }

    @Override
    @NonNull
    public URI getURI() {
        return Objects.requireNonNull(uri);
    }

    @Override
    @NonNull
    public HttpHeaders getHeaders() {
        return Objects.requireNonNull(headers);
    }

    @Override
    @NonNull
    public InputStream getBody() {
        return new ByteArrayInputStream(delegate.bodyBytes());
    }

    @Override
    @NonNull
    public Map<String, Object> getAttributes() {
        return Objects.requireNonNull(attributes);
    }

    @Override
    public Principal getPrincipal() {
        return null;
    }

    @Override
    @Nullable
    @SuppressWarnings("null") // widens Spring's @NonNullApi return; matches what
    // servlet / reactor-netty adapters do when the underlying transport doesn't
    // expose the bound socket.
    public InetSocketAddress getLocalAddress() {
        // Kernel HttpRequest does not expose the bound local socket address.
        // The Host header is the requested authority, not the bound interface,
        // so we don't conflate them. Callers that need the requested host can
        // read getHeaders().getHost() explicitly.
        return null;
    }

    @Override
    @Nullable
    @SuppressWarnings("null") // widens Spring's @NonNullApi return; null is the
    // honest answer when no forwarding headers are present and the kernel SPI
    // does not expose raw socket peer.
    public InetSocketAddress getRemoteAddress() {
        // Compatibility mode resolves the client address from standard reverse-proxy
        // forwarding headers (Forwarded / X-Forwarded-For / X-Real-IP). In real
        // deployments the request always arrives through a proxy or load balancer
        // that sets these — raw socket peer would be the LB anyway. For direct
        // unforwarded requests we have no information from the kernel SPI and
        // return null rather than a misleading sentinel.
        return resolveRemoteAddressFromForwardingHeaders();
    }

    private InetSocketAddress resolveRemoteAddressFromForwardingHeaders() {
        String forwarded = headers.getFirst("Forwarded");
        if (forwarded != null) {
            InetSocketAddress fromForwarded = parseForwardedFor(forwarded);
            if (fromForwarded != null) {
                return fromForwarded;
            }
        }
        String xForwardedFor = headers.getFirst("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isBlank()) {
            String firstHop = xForwardedFor.split(",", 2)[0].trim();
            if (!firstHop.isEmpty()) {
                return InetSocketAddress.createUnresolved(firstHop, 0);
            }
        }
        String xRealIp = headers.getFirst("X-Real-IP");
        if (xRealIp != null && !xRealIp.isBlank()) {
            return InetSocketAddress.createUnresolved(xRealIp.trim(), 0);
        }
        return null;
    }

    private static InetSocketAddress parseForwardedFor(String forwardedHeader) {
        // RFC 7239: Forwarded: for=192.0.2.43;proto=https;by=...
        // Multiple comma-separated entries; only the first hop matters for "client".
        String firstEntry = forwardedHeader.split(",", 2)[0];
        for (String pair : firstEntry.split(";")) {
            String trimmed = pair.trim();
            if (trimmed.regionMatches(true, 0, "for=", 0, 4)) {
                String value = trimmed.substring(4).trim();
                if (value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2) {
                    value = value.substring(1, value.length() - 1);
                }
                if (value.isEmpty() || "unknown".equalsIgnoreCase(value)) {
                    return null;
                }
                return parseHostPort(value);
            }
        }
        return null;
    }

    private static InetSocketAddress parseHostPort(String value) {
        // Accepts "1.2.3.4", "1.2.3.4:5678", "[::1]", "[::1]:5678".
        if (value.startsWith("[")) {
            int closing = value.indexOf(']');
            if (closing < 0) {
                return InetSocketAddress.createUnresolved(value, 0);
            }
            String host = value.substring(1, closing);
            int port = 0;
            if (closing + 1 < value.length() && value.charAt(closing + 1) == ':') {
                try {
                    port = Integer.parseInt(value.substring(closing + 2));
                } catch (NumberFormatException ignored) {
                    port = 0;
                }
            }
            return InetSocketAddress.createUnresolved(host, port);
        }
        int colon = value.lastIndexOf(':');
        if (colon > 0 && value.indexOf(':') == colon) {
            try {
                int port = Integer.parseInt(value.substring(colon + 1));
                return InetSocketAddress.createUnresolved(value.substring(0, colon), port);
            } catch (NumberFormatException ignored) {
                // fall through to host-only
            }
        }
        return InetSocketAddress.createUnresolved(value, 0);
    }

    @Override
    @NonNull
    public ServerHttpAsyncRequestControl getAsyncRequestControl(@NonNull ServerHttpResponse response) {
        throw new UnsupportedOperationException("Async request control is not supported in compatibility scaffold.");
    }

    private static HttpHeaders toSpringHeaders(ExerisServerRequest request) {
        HttpHeaders mapped = new HttpHeaders();
        for (HttpHeader header : request.kernelRequest().headers()) {
            String name = header.name();
            String value = header.value();
            if (name != null && value != null) {
                mapped.add(name, value);
            }
        }
        return HttpHeaders.readOnlyHttpHeaders(mapped);
    }

    private static URI toUri(String path) {
        if (path == null || path.isEmpty()) {
            return URI.create("/");
        }
        return path.startsWith("/") ? URI.create(path) : URI.create("/" + path);
    }
}
