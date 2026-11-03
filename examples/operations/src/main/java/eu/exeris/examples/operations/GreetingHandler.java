/*
 * Copyright (C) 2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.examples.operations;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.spring.runtime.web.ExerisRequestHandler;
import eu.exeris.spring.runtime.web.ExerisRoute;
import eu.exeris.spring.runtime.web.ExerisServerRequest;
import eu.exeris.spring.runtime.web.ExerisServerResponse;

// tag::handler[]
/** A business route that records its own Micrometer counter. */
@Component
@ExerisRoute(method = HttpMethod.GET, path = "/greeting")
public class GreetingHandler implements ExerisRequestHandler {

    private final Counter greetings;

    public GreetingHandler(MeterRegistry registry) {
        this.greetings = Counter.builder("greetings.served")
                .description("Greetings answered")
                .register(registry);
    }

    @Override
    public ExerisServerResponse handle(ExerisServerRequest request) {
        greetings.increment();
        return ExerisServerResponse.ok().body("Hello from Exeris");
    }
}
// end::handler[]
