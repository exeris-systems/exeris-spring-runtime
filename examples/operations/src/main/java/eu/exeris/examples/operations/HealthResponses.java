/*
 * Copyright (C) 2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.examples.operations;

import org.springframework.boot.actuate.health.HealthComponent;
import org.springframework.boot.actuate.health.Status;
import org.springframework.http.MediaType;

import eu.exeris.kernel.spi.http.HttpStatus;
import eu.exeris.spring.runtime.web.ExerisServerResponse;

// tag::render[]
/** Renders an actuator health result the way a probe reads it: the status code decides. */
final class HealthResponses {

    private HealthResponses() {
    }

    static ExerisServerResponse render(HealthComponent health) {
        Status status = health == null ? Status.UNKNOWN : health.getStatus();
        HttpStatus code = Status.UP.equals(status) ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE;
        return ExerisServerResponse.status(code)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"status\":\"" + status.getCode() + "\"}");
    }
}
// end::render[]
