/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.web;

import eu.exeris.kernel.spi.http.HttpExchange;
import eu.exeris.kernel.spi.http.HttpHandler;
import eu.exeris.kernel.spi.exceptions.http.HttpException;
import eu.exeris.kernel.spi.http.HttpStatus;

/**
 * The primary bridge between the Exeris HTTP runtime and Spring application handlers.
 *
 * <p>This class implements the kernel {@link HttpHandler} SPI and is registered with
 * the kernel's {@code HttpServerEngine}. On each request, it:
 * <ol>
 *   <li>Wraps the kernel {@code HttpRequest} in an {@link ExerisServerRequest} (no copy).</li>
 *   <li>Resolves the matching Spring-managed handler bean via {@link ExerisRouteRegistry}.</li>
 *   <li>Invokes the handler.</li>
 *   <li>Converts the {@link ExerisServerResponse} to a kernel {@code HttpResponse}.</li>
 *   <li>Calls {@code exchange.respond(response)} — transferring body buffer ownership to engine.</li>
 * </ol>
 *
 * <h2>Threading Model</h2>
 * <p>Invocations run one-per-virtual-thread. No {@code synchronized} blocks, no
 * {@code ThreadLocal}, no carrier thread pinning.
 *
 * <h2>No DispatcherServlet</h2>
 * <p>This class does NOT delegate to Spring's {@code DispatcherServlet}. It uses
 * {@link ExerisRouteRegistry} for routing. This is Pure Mode. Compatibility mode
 * ({@code @RestController} / {@code @RequestMapping} dispatch) is implemented in
 * {@code eu.exeris.spring.runtime.web.compat.ExerisCompatDispatcher}.
 *
 * @since 0.1.0
 */
public final class ExerisHttpDispatcher implements HttpHandler {

    private final ExerisRouteRegistry routeRegistry;
    private final ExerisErrorMapper errorMapper;

    public ExerisHttpDispatcher(ExerisRouteRegistry routeRegistry,
                                 ExerisErrorMapper errorMapper) {
        this.routeRegistry = routeRegistry;
        this.errorMapper = errorMapper;
    }

    @Override
    public void handle(HttpExchange exchange) throws HttpException {
        var request = new ExerisServerRequest(exchange.request());
        try {
            var handler = routeRegistry.resolve(request.method(), request.path());
            if (handler == null) {
                exchange.respond(errorMapper.mapStatus(HttpStatus.NOT_FOUND));
                return;
            }
            var response = handler.handle(request);
            exchange.respond(response.toKernelResponse());
        } catch (HttpException ex) {
            exchange.respond(errorMapper.map(ex));
            throw ex;
        } catch (Exception ex) {
            exchange.respond(errorMapper.mapUnhandled(ex));
        }
    }
}
