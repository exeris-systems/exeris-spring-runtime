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
    @NonNull
    public InetSocketAddress getLocalAddress() {
        return Objects.requireNonNull(InetSocketAddress.createUnresolved("0.0.0.0", 0));
    }

    @Override
    @NonNull
    public InetSocketAddress getRemoteAddress() {
        return Objects.requireNonNull(InetSocketAddress.createUnresolved("0.0.0.0", 0));
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
