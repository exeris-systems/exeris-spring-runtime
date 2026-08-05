/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.web;

import eu.exeris.kernel.spi.http.HttpHeader;
import eu.exeris.kernel.spi.http.HttpStatus;

import java.util.List;
import java.util.Objects;

/**
 * A resolved error outcome: the HTTP status to send, plus any headers the status requires.
 *
 * <p>The headers are the ones that are part of the status's protocol contract, not general
 * response decoration — {@code WWW-Authenticate} on a 401 is the motivating case (RFC 9110 §11.6.1
 * makes it mandatory on that status, and a client cannot tell "which scheme?" without it).
 * {@code Content-Length} is added by {@link ExerisErrorMapper}; a resolver must not set it.
 *
 * @param status  the status to respond with; never {@code null}
 * @param headers status-mandated headers, possibly empty; never {@code null}
 * @see ExerisErrorStatusResolver
 * @since 0.7.0
 */
public record ExerisErrorStatus(HttpStatus status, List<HttpHeader> headers) {

    public ExerisErrorStatus {
        Objects.requireNonNull(status, "status must not be null");
        headers = List.copyOf(Objects.requireNonNull(headers, "headers must not be null"));
    }

    /**
     * Creates an outcome carrying only a status.
     */
    public static ExerisErrorStatus of(HttpStatus status) {
        return new ExerisErrorStatus(status, List.of());
    }
}
