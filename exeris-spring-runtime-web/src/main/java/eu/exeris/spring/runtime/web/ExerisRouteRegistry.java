/*
 * Copyright (C) 2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.spring.runtime.web;

import eu.exeris.kernel.spi.http.HttpMethod;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Route table mapping (method, path) pairs to Pure Mode handler beans, written once and immutable
 * from then on.
 *
 * <p>Either built complete through {@link #builder()}, or created {@linkplain #deferred() deferred}
 * and {@linkplain #populate(Builder) populated} exactly once later. A deferred registry refuses
 * {@link #resolve} until it is populated, so an empty table is never mistaken for a missing route.
 * The runtime uses the deferred form: the dispatcher holds the registry before the handler beans
 * exist, so a handler may depend on beans that themselves depend on the dispatcher, and the table is
 * filled from every {@link ExerisRoute} bean once the container has instantiated them all — before
 * the kernel accepts a request. Once written, route resolution is {@code O(1)} via an immutable map —
 * no per-request allocation on the lookup path.
 *
 * <p><b>Routing Semantics:</b> Supports exact-match routes with query string normalisation.</p>
 *
 * @since 0.1
 */
public final class ExerisRouteRegistry {

    private static final String NOT_POPULATED =
            "The route registry was consulted before it was populated; a deferred registry must be "
                    + "populated before the first request is dispatched";

    // Null only while a deferred registry awaits population; written once with an immutable map,
    // and never replaced.
    private volatile Map<HttpMethod, Map<String, ExerisRequestHandler>> routes;

    private ExerisRouteRegistry(Map<HttpMethod, Map<String, ExerisRequestHandler>> routes) {
        this.routes = immutableCopy(routes);
    }

    private ExerisRouteRegistry() {
        this.routes = null;
    }

    private static Map<HttpMethod, Map<String, ExerisRequestHandler>> immutableCopy(
            Map<HttpMethod, Map<String, ExerisRequestHandler>> routes) {
        Map<HttpMethod, Map<String, ExerisRequestHandler>> immutable = new HashMap<>();
        routes.forEach((method, pathMap) -> immutable.put(method, Map.copyOf(pathMap)));
        return Map.copyOf(immutable);
    }

    /**
     * Creates an empty registry whose routes are supplied later, once, by {@link #populate(Builder)}.
     * Until then {@link #resolve} refuses rather than answer as if no route existed.
     *
     * @return a registry awaiting population
     * @since 0.8
     */
    public static ExerisRouteRegistry deferred() {
        return new ExerisRouteRegistry();
    }

    /**
     * Fills a {@linkplain #deferred() deferred} registry with the routes registered on {@code builder}.
     *
     * @param builder the complete set of routes
     * @throws IllegalStateException if this registry already holds its routes
     * @since 0.8
     */
    public synchronized void populate(Builder builder) {
        Objects.requireNonNull(builder, "builder must not be null");
        if (routes != null) {
            throw new IllegalStateException("The route registry is already populated");
        }
        routes = immutableCopy(builder.routes);
    }

    /**
     * Reports whether this registry holds its routes: always for a built registry, and for a
     * {@linkplain #deferred() deferred} one once {@link #populate(Builder)} has run.
     *
     * @return {@code true} when routes can be resolved
     * @since 0.8
     */
    public boolean isPopulated() {
        return routes != null;
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
     * @throws IllegalStateException if this is a {@linkplain #deferred() deferred} registry that has
     *         not been populated yet
     */
    public ExerisRequestHandler resolve(HttpMethod method, String requestTarget) {
        Map<HttpMethod, Map<String, ExerisRequestHandler>> table = routes;
        if (table == null) {
            throw new IllegalStateException(NOT_POPULATED);
        }
        Map<String, ExerisRequestHandler> handlersByPath = table.get(method);
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
