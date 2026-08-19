/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.web.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RoutePathPatternTest {

    @ParameterizedTest(name = "{0} matches {1} -> {2}")
    @CsvSource({
            // exact
            "/api/orders,      /api/orders,           true",
            "/api/orders,      /api/order,            false",
            "/api/orders,      /api/orders/1,         false",
            "/api/orders,      /api/orders/,          false",
            "/,                /,                     true",
            "/,                /health,               false",

            // single-segment wildcard
            "/api/*/orders,    /api/v1/orders,        true",
            "/api/*/orders,    /api/orders,           false",
            "/api/*/orders,    /api/v1/v2/orders,     false",

            // trailing multi-segment wildcard, including the zero-segment case
            "/api/**,          /api,                  true",
            "/api/**,          /api/orders,           true",
            "/api/**,          /api/orders/1/items,   true",
            "/api/**,          /apiary,               false",
            "/**,              /anything/at/all,      true",

            // case sensitivity is deliberate: paths are case-sensitive per RFC 3986
            "/api/Orders,      /api/orders,           false",
    })
    void matchesPathsAsDeclared(String pattern, String target, boolean expected) {
        assertThat(RoutePathPattern.compile(pattern).matches(target)).isEqualTo(expected);
    }

    /**
     * The regression this class exists for.
     *
     * <p>{@code HttpRequest.path()} is the raw request target, so it carries the query string. A rule
     * that stopped matching once a caller appended {@code ?page=2} would hand the request to the
     * unmatched answer — a refusal of a legitimate request when that answer is fail-closed, and an
     * authorization bypass reachable by appending a query string when it is not.
     */
    @ParameterizedTest(name = "query string ignored: {0} vs {1}")
    @CsvSource({
            "/api/orders,   /api/orders?page=2",
            "/api/orders,   /api/orders?",
            "/api/**,       /api/orders/1?expand=items&x=1",
            "/api/*/orders, /api/v1/orders?sort=asc",
    })
    void queryStringIsNotPartOfTheMatch(String pattern, String target) {
        assertThat(RoutePathPattern.compile(pattern).matches(target)).isTrue();
    }

    @Test
    void queryStringCannotSmuggleExtraSegments() {
        // '?' ends the path, so what follows must not be able to satisfy a segment.
        assertThat(RoutePathPattern.compile("/api/*/orders").matches("/api?/v1/orders")).isFalse();
    }

    @Test
    void nonPathTargetsDoNotMatch() {
        RoutePathPattern pattern = RoutePathPattern.compile("/api/**");
        assertThat(pattern.matches(null)).isFalse();
        assertThat(pattern.matches("")).isFalse();
        assertThat(pattern.matches("api/orders")).isFalse();
    }

    @Test
    void malformedPatternsAreRejectedAtCompileTime() {
        assertThatThrownBy(() -> RoutePathPattern.compile("api/orders"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must start with '/'");

        assertThatThrownBy(() -> RoutePathPattern.compile("/api//orders"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("empty segment");

        assertThatThrownBy(() -> RoutePathPattern.compile("/api/**/orders"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("only allowed as the final segment");

        // A pattern with a query string would never match anything, so it fails loudly instead.
        assertThatThrownBy(() -> RoutePathPattern.compile("/api/orders?page=2"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not contain a query string");
    }
}
