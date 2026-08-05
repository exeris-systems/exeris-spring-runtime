/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.web.compat.security;

import eu.exeris.spring.runtime.web.compat.CompatibilityMode;

/**
 * Raised when a request presents a Bearer token that cannot be decoded or validated.
 *
 * <p>Carries no token material and no decoder message text in {@link #getMessage()} beyond the
 * failure class name: the message reaches logs, and a validation message can echo token contents.
 * The underlying exception is retained as the cause for local diagnosis and is never rendered into
 * a response body.
 *
 * <h2>Mode</h2>
 * <p>Compatibility Mode only. Thrown by {@code ExerisSecurityContextFilter} and translated to a
 * 401 by {@code ExerisCompatDispatcher}.
 *
 * @since 0.7.0
 */
@CompatibilityMode
public final class InvalidBearerTokenException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public InvalidBearerTokenException(Throwable cause) {
        super("Bearer token rejected: " + cause.getClass().getSimpleName(), cause);
    }
}
