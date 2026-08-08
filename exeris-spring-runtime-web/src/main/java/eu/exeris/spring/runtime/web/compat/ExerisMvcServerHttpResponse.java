/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.web.compat;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.lang.NonNull;

import eu.exeris.kernel.spi.http.HttpHeader;
import eu.exeris.kernel.spi.http.HttpStatus;
import eu.exeris.spring.runtime.web.ExerisServerResponse;
import eu.exeris.spring.boot.autoconfigure.compat.CompatibilityMode;

/**
 * Minimal compatibility response collector for Spring server abstractions.
 */
@CompatibilityMode
public final class ExerisMvcServerHttpResponse implements ServerHttpResponse {

    private HttpStatusCode statusCode = HttpStatusCode.valueOf(200);
    private final HttpHeaders headers = new HttpHeaders();
    private final ByteArrayOutputStream body = new ByteArrayOutputStream();

    @Override
    public void setStatusCode(@NonNull HttpStatusCode status) {
        this.statusCode = status;
    }

    @Override
    @NonNull
    public HttpHeaders getHeaders() {
        return Objects.requireNonNull(headers);
    }

    @Override
    @NonNull
    public OutputStream getBody() {
        return Objects.requireNonNull(body);
    }

    @Override
    public void flush() {
        // no-op for scaffold collector
    }

    @Override
    public void close() {
        // no-op for scaffold collector
    }

    public HttpStatusCode capturedStatusCode() {
        return statusCode;
    }

    public byte[] capturedBody() {
        return body.toByteArray();
    }

    /**
     * Converts captured Spring response state to the current Exeris response model.
     * All headers written by the handler are propagated; Content-Type drives the
     * {@link ExerisServerResponse#contentType(MediaType)} field while remaining
     * headers are carried as extra headers via {@link ExerisServerResponse#withHeaders}.
     */
    public ExerisServerResponse toExerisServerResponse() {
        ExerisServerResponse exeris = ExerisServerResponse.status(toKernelStatus(statusCode.value()));
        // HttpHeaders#getContentType collapses to a single MediaType (the first parsable
        // value). If a handler set multiple Content-Type values — unusual but legal —
        // only the first survives. The Content-Type header is intentionally excluded
        // from the extras loop below to avoid double-emitting it.
        MediaType contentType = headers.getContentType();
        if (contentType != null) {
            exeris = exeris.contentType(contentType);
        }

        // Propagate all other headers (excluding Content-Type which is already set above).
        //
        // Iterated with forEach rather than entrySet() because Spring Framework 7 stops
        // HttpHeaders implementing MultiValueMap: entrySet() and keySet() are gone, replaced by
        // headerNames(). forEach carries the same signature on both lines, so this compiles under
        // matrix-sb3 and matrix-sb4 from one source — which is what ADR-028 obligation 1 requires
        // and what a version-specific call could not give.
        List<HttpHeader> extraHeaders = new ArrayList<>();
        headers.forEach((name, values) -> {
            if (HttpHeaders.CONTENT_TYPE.equalsIgnoreCase(name)) {
                return;
            }
            for (String value : values) {
                extraHeaders.add(new HttpHeader(name, value));
            }
        });
        if (!extraHeaders.isEmpty()) {
            exeris = exeris.withHeaders(extraHeaders);
        }

        return exeris.body(capturedBody());
    }

    private static HttpStatus toKernelStatus(int code) {
        org.springframework.http.HttpStatus springStatus = org.springframework.http.HttpStatus.resolve(code);
        String reasonPhrase = springStatus != null ? springStatus.getReasonPhrase() : "HTTP " + code;
        return new HttpStatus(code, reasonPhrase);
    }
}
