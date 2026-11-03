/*
 * Copyright (C) 2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.examples.operations;

import org.springframework.stereotype.Component;

import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.telemetry.KernelEvent;
import eu.exeris.kernel.spi.telemetry.TelemetrySink;
import eu.exeris.spring.runtime.web.ExerisRequestHandler;
import eu.exeris.spring.runtime.web.ExerisRoute;
import eu.exeris.spring.runtime.web.ExerisServerRequest;
import eu.exeris.spring.runtime.web.ExerisServerResponse;

// tag::handler[]
/** Answers with a fallback quote, and reports each fallback as a WARN event. */
@Component
@ExerisRoute(method = HttpMethod.GET, path = "/quote")
public class QuoteHandler implements ExerisRequestHandler {

    static final String FALLBACK_SERVED = "QUOTE-FALLBACK-001";

    @Override
    public ExerisServerResponse handle(ExerisServerRequest request) {
        // This example has no quote service, so every answer is the fallback.
        emit(KernelEvent.warn(FALLBACK_SERVED, "quote", null));
        return ExerisServerResponse.ok().body("Simplicity is prerequisite for reliability.");
    }

    /** Emits through the sinks bound for this request. Unbound outside a Pure Mode request. */
    private static void emit(KernelEvent event) {
        if (!KernelProviders.TELEMETRY_SINKS.isBound()) {
            return;
        }
        for (TelemetrySink sink : KernelProviders.TELEMETRY_SINKS.get()) {
            sink.emit(event);
        }
    }
}
// end::handler[]
