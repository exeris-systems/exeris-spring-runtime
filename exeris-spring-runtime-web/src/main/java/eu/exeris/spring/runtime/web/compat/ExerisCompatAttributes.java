/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.web.compat;

import eu.exeris.spring.boot.autoconfigure.compat.CompatibilityMode;

/**
 * Shared request attribute keys for compatibility-mode Spring bridges.
 */
@CompatibilityMode
public final class ExerisCompatAttributes {

    /**
     * Request-attribute key under which the {@link ExerisMvcServerHttpResponse} collecting the current
     * request's response is stored, so return-value handlers can write to it.
     */
    public static final String SPRING_RESPONSE_ATTRIBUTE = "__exerisSpringResponse";

    private ExerisCompatAttributes() {
    }
}
