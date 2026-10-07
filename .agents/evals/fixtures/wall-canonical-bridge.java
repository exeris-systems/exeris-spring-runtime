// Proposed: eu/exeris/spring/runtime/web/ExerisHttpDispatcher.java
//
// Canonical bridge implementing kernel SPI in runtime module without leaking Spring into kernel.
package eu.exeris.spring.runtime.web;

import eu.exeris.kernel.spi.http.HttpExchange;
import eu.exeris.kernel.spi.http.HttpHandler;

public final class ExerisHttpDispatcher implements HttpHandler {
    @Override
    public void handle(HttpExchange exchange) {
        exchange.respond(exchange.response().status(200).build());
    }
}
