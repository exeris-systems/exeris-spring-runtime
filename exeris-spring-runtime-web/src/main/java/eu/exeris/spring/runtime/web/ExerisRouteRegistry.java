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
 * <p><b>Routing Semantics:</b> Supports exact-match routes with query string normalisation.</p>
 *
 * @since 0.1
 */
public final class ExerisRouteRegistry {

    private final Map<HttpMethod, Map<String, ExerisRequestHandler>> routes;

    private ExerisRouteRegistry(Map<HttpMethod, Map<String, ExerisRequestHandler>> routes) {
        Map<HttpMethod, Map<String, ExerisRequestHandler>> immutable = new HashMap<>();
        routes.forEach((method, pathMap) -> immutable.put(method, Map.copyOf(pathMap)));
        this.routes = Map.copyOf(immutable);
    }

    /**
     * Resolves the handler for the given method and request target.
     *
     * <p>The kernel delivers {@code HttpRequest.path()} as the raw <em>request target</em>,
     * which includes the query string when present (per the kernel SPI contract:
     * {@code "/api/v1/users?page=1"}). Routes are registered by path only, so the query
     * string is stripped before lookup — otherwise {@code GET /api/v1/user?id=1} would miss
     * the exact-match table and surface as a 404. The compatibility arm performs the
     * equivalent normalisation in
     * {@code eu.exeris.spring.runtime.web.compat.ExerisHandlerMethodRegistry#resolve}; both
     * arms must agree, or the same request shape resolves in one mode and 404s in the other.
     *
     * <p><strong>Cost:</strong> Query-less requests allocate nothing. Requests with query
     * strings perform a substring allocation to isolate the path component for map lookup.
     *
     * @param method        the HTTP method
     * @param requestTarget the raw request target (e.g., {@code "/status"} or
     *                      {@code "/api/v1/user?id=1"}); the query string is stripped
     * @return the registered handler, or {@code null} if no route matches
     */
    public ExerisRequestHandler resolve(HttpMethod method, String requestTarget) {
        Map<String, ExerisRequestHandler> handlersByPath = routes.get(method);
        if (handlersByPath == null) {
            return null;
        }
        return handlersByPath.get(stripQueryString(requestTarget));
    }

    private static String stripQueryString(String requestTarget) {
        if (requestTarget == null) {
            return null;
        }
        int q = requestTarget.indexOf('?');
        return q < 0 ? requestTarget : requestTarget.substring(0, q);
    }

    /**
     * Creates a new fluent {@link Builder} for route registrations.
     *
     * @return a new builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Fluent builder for assembling an immutable {@link ExerisRouteRegistry}.
     *
     * @since 0.1
     */
    public static final class Builder {

        private final Map<HttpMethod, Map<String, ExerisRequestHandler>> routes = new HashMap<>();

        /**
         * Creates a new empty route registry builder.
         */
        public Builder() {
        }

        /**
         * Registers a route handler for the specified HTTP method and path.
         *
         * @param method HTTP method
         * @param path exact request path
         * @param handler request handler
         * @return this builder
         * @throws IllegalStateException if a handler is already registered for this method and path
         */
        public Builder register(HttpMethod method, String path, ExerisRequestHandler handler) {
            Map<String, ExerisRequestHandler> handlersByPath =
                    routes.computeIfAbsent(method, ignored -> new HashMap<>());
            if (handlersByPath.containsKey(path)) {
                throw new IllegalStateException(
                        "Duplicate route registration: " + method + " " + path);
            }
            handlersByPath.put(path, handler);
            return this;
        }

        /**
         * Builds an immutable {@link ExerisRouteRegistry} containing the registered routes.
         *
         * @return immutable route registry
         */
        public ExerisRouteRegistry build() {
            return new ExerisRouteRegistry(routes);
        }
    }
}
