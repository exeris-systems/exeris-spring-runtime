/*
 * Copyright (C) 2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.spring.runtime.web.compat;

import eu.exeris.spring.boot.autoconfigure.compat.CompatibilityMode;

/**
 * Shared request attribute keys for compatibility-mode Spring bridges.
 */
@CompatibilityMode
public final class ExerisCompatAttributes {

    public static final String SPRING_RESPONSE_ATTRIBUTE = "__exerisSpringResponse";

    private ExerisCompatAttributes() {
    }
}
