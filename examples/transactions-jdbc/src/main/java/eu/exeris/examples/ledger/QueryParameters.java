/*
 * Copyright (C) 2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.examples.ledger;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

import eu.exeris.spring.runtime.web.ExerisServerRequest;

/** Reads one parameter from the query string of the request target. */
final class QueryParameters {

    private QueryParameters() {
    }

    static String required(ExerisServerRequest request, String name) {
        String target = request.path();
        int query = target.indexOf('?');
        if (query >= 0) {
            for (String pair : target.substring(query + 1).split("&")) {
                int eq = pair.indexOf('=');
                if (eq > 0 && pair.substring(0, eq).equals(name)) {
                    return URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
                }
            }
        }
        throw new IllegalArgumentException("missing query parameter " + name);
    }
}
