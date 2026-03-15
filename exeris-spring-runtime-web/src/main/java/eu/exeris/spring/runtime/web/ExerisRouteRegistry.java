/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.web;

import eu.exeris.kernel.spi.http.HttpMethod;

import java.util.HashMap;
import java.util.Map;

/**
 * Immutable route table mapping (method, path) pairs to Pure Mode handler beans.
 *
 * <p>Built once at application startup by scanning all Spring beans annotated with
 * {@link ExerisRoute}. Route resolution is {@code O(1)} via a pre-computed immutable
 * map — no per-request allocation on the lookup path.
 *
 * <h2>Phase 1 Scope</h2>
 * <p>Phase 1 supports exact-match routes only (no path variables, no wildcards).
 * Path variable support ({@code /users/{id}}) is planned for Phase 1.1 or Phase 2.
 *
 * @since 0.1.0
 */
public final class ExerisRouteRegistry {

    private final Map<RouteKey, ExerisRequestHandler> routes;

    private ExerisRouteRegistry(Map<RouteKey, ExerisRequestHandler> routes) {
        this.routes = Map.copyOf(routes);
    }

    /**
     * Resolves the handler for the given method and path.
     *
     * @param method the HTTP method
     * @param path   the request path (e.g., {@code "/status"})
     * @return the registered handler, or {@code null} if no route matches
     */
    public ExerisRequestHandler resolve(HttpMethod method, String path) {
        return routes.get(new RouteKey(method, path));
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {

        private final Map<RouteKey, ExerisRequestHandler> routes = new HashMap<>();

        public Builder register(HttpMethod method, String path, ExerisRequestHandler handler) {
            var key = new RouteKey(method, path);
            if (routes.containsKey(key)) {
                throw new IllegalStateException(
                        "Duplicate route registration: " + method + " " + path);
            }
            routes.put(key, handler);
            return this;
        }

        public ExerisRouteRegistry build() {
            return new ExerisRouteRegistry(routes);
        }
    }

    private record RouteKey(HttpMethod method, String path) {}
}
