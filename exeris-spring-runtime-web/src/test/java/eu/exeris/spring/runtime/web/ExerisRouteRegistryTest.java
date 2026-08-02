/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.web;

import eu.exeris.kernel.spi.http.HttpMethod;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExerisRouteRegistryTest {

    @Test
    void resolve_returnsRegisteredHandler_forExistingRoute() {
        ExerisRequestHandler handler = request -> ExerisServerResponse.ok().body("hello");

        ExerisRouteRegistry registry = ExerisRouteRegistry.builder()
                .register(HttpMethod.GET, "/hello", handler)
                .build();

        assertThat(registry.resolve(HttpMethod.GET, "/hello")).isSameAs(handler);
    }

    @Test
    void resolve_returnsNull_forMissingRoute() {
        ExerisRouteRegistry registry = ExerisRouteRegistry.builder().build();

        assertThat(registry.resolve(HttpMethod.GET, "/missing")).isNull();
    }

    @Test
    void resolve_stripsQueryString_beforeRouteLookup() {
        ExerisRequestHandler handler = request -> ExerisServerResponse.ok().body("user");

        ExerisRouteRegistry registry = ExerisRouteRegistry.builder()
                .register(HttpMethod.GET, "/api/v1/user", handler)
                .build();

        // The kernel delivers HttpRequest.path() as the raw request target, query string
        // included. Without stripping, this exact-match lookup misses and the request 404s.
        assertThat(registry.resolve(HttpMethod.GET, "/api/v1/user?id=1")).isSameAs(handler);
    }

    @Test
    void resolve_stripsQueryString_forMultipleParametersAndEmptyQuery() {
        ExerisRequestHandler handler = request -> ExerisServerResponse.ok().body("user");

        ExerisRouteRegistry registry = ExerisRouteRegistry.builder()
                .register(HttpMethod.GET, "/api/v1/user", handler)
                .build();

        assertThat(registry.resolve(HttpMethod.GET, "/api/v1/user?id=1&verbose=true")).isSameAs(handler);
        assertThat(registry.resolve(HttpMethod.GET, "/api/v1/user?")).isSameAs(handler);
    }

    @Test
    void resolve_doesNotMatchDifferentPath_whenQueryStringPresent() {
        ExerisRequestHandler handler = request -> ExerisServerResponse.ok().body("user");

        ExerisRouteRegistry registry = ExerisRouteRegistry.builder()
                .register(HttpMethod.GET, "/api/v1/user", handler)
                .build();

        // Stripping must not widen matching: a different path with a query still misses.
        assertThat(registry.resolve(HttpMethod.GET, "/api/v1/other?id=1")).isNull();
    }

    @Test
    void register_throwsIllegalStateException_forDuplicateRoute() {
        ExerisRequestHandler first = request -> ExerisServerResponse.ok().body("first");
        ExerisRequestHandler second = request -> ExerisServerResponse.ok().body("second");

        ExerisRouteRegistry.Builder builder = ExerisRouteRegistry.builder()
                .register(HttpMethod.GET, "/hello", first);

        assertThatThrownBy(() -> builder.register(HttpMethod.GET, "/hello", second))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate route registration");
    }
}