/*
 * Copyright (C) 2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.spring.runtime.web.autoconfigure;

import java.util.Objects;

import org.springframework.context.SmartLifecycle;

import eu.exeris.spring.runtime.web.ExerisRouteRegistry;

/**
 * Refuses to let the context start with a route registry that was never populated.
 *
 * <p>Starts one phase ahead of {@code ExerisRuntimeLifecycle}, so the kernel never binds ingress over
 * an empty table: without this, an unpopulated deferred registry would surface only per request, as a
 * 500 on every dispatch.
 */
final class RouteRegistryPopulatedGuard implements SmartLifecycle {

    /** One below {@code ExerisRuntimeLifecycle}'s phase: started before it, stopped after it. */
    static final int PHASE = Integer.MAX_VALUE - 101;

    private final ExerisRouteRegistry routeRegistry;
    private volatile boolean running;

    RouteRegistryPopulatedGuard(ExerisRouteRegistry routeRegistry) {
        this.routeRegistry = Objects.requireNonNull(routeRegistry, "routeRegistry must not be null");
    }

    @Override
    public void start() {
        if (!routeRegistry.isPopulated()) {
            throw new IllegalStateException("The Exeris route registry was never populated, so the kernel "
                    + "would accept requests with no routes; a deferred ExerisRouteRegistry must be "
                    + "populated before the context starts");
        }
        running = true;
    }

    @Override
    public void stop() {
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return PHASE;
    }
}
