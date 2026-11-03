/*
 * Copyright (C) 2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.examples.operations;

import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.stereotype.Component;

import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.spring.runtime.web.ExerisRequestHandler;
import eu.exeris.spring.runtime.web.ExerisRoute;
import eu.exeris.spring.runtime.web.ExerisServerRequest;
import eu.exeris.spring.runtime.web.ExerisServerResponse;

// tag::handler[]
/** Serves the aggregate actuator health on the kernel listener. */
@Component
@ExerisRoute(method = HttpMethod.GET, path = "/actuator/health")
public class HealthHandler implements ExerisRequestHandler {

    private final HealthEndpoint health;

    public HealthHandler(HealthEndpoint health) {
        this.health = health;
    }

    @Override
    public ExerisServerResponse handle(ExerisServerRequest request) {
        return HealthResponses.render(health.health());
    }
}
// end::handler[]
