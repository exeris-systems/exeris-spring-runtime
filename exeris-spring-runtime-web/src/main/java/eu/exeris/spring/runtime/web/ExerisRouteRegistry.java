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

    private final Map<HttpMethod, Map<String, ExerisRequestHandler>> routes;

    private ExerisRouteRegistry(Map<HttpMethod, Map<String, ExerisRequestHandler>> routes) {
        Map<HttpMethod, Map<String, ExerisRequestHandler>> immutable = new HashMap<>();
        routes.forEach((method, pathMap) -> immutable.put(method, Map.copyOf(pathMap)));
        this.routes = Map.copyOf(immutable);
    }

    /**
     * Resolves the handler for the given method and path.
     *
     * @param method the HTTP method
     * @param path   the request path (e.g., {@code "/status"})
     * @return the registered handler, or {@code null} if no route matches
     */
    public ExerisRequestHandler resolve(HttpMethod method, String path) {
        Map<String, ExerisRequestHandler> handlersByPath = routes.get(method);
        if (handlersByPath == null) {
            return null;
        }
        return handlersByPath.get(path);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {

        private final Map<HttpMethod, Map<String, ExerisRequestHandler>> routes = new HashMap<>();

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

        public ExerisRouteRegistry build() {
            return new ExerisRouteRegistry(routes);
        }
    }
}
