/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.web;

import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.exceptions.http.HttpException;
import eu.exeris.kernel.spi.http.HttpExchange;
import eu.exeris.kernel.spi.http.HttpHandler;
import eu.exeris.kernel.spi.http.HttpStatus;
import eu.exeris.kernel.spi.http.HttpVersion;
import eu.exeris.kernel.spi.telemetry.TelemetrySink;

import java.util.List;

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
 * <h2>Telemetry Fallback</h2>
 * <p>In production, {@code KernelProviders.TELEMETRY_SINKS} is always bound by the kernel
 * bootstrap scope and inherited by every handler virtual thread. In non-kernel-scope contexts
 * (testkit, unit tests) where the acceptor platform thread does not propagate the bootstrap
 * ScopedValue, this dispatcher may receive a pre-built {@code fallbackSinks} list at construction
 * time. When {@code TELEMETRY_SINKS} is not already bound and {@code fallbackSinks} is non-empty,
 * the dispatcher binds {@code fallbackSinks} for the duration of the handler call via
 * {@link ScopedValue#where(ScopedValue, Object) ScopedValue.where(...).run(...)}. This restores
 * the expected telemetry availability for test and compatibility paths without touching the
 * production hot path.
 *
 * @since 0.1.0
 */
public final class ExerisHttpDispatcher implements HttpHandler {

    private final ExerisRouteRegistry routeRegistry;
    private final ExerisErrorMapper errorMapper;
    private final List<TelemetrySink> fallbackSinks;

    public ExerisHttpDispatcher(ExerisRouteRegistry routeRegistry,
                                 ExerisErrorMapper errorMapper) {
        this(routeRegistry, errorMapper, List.of());
    }

    public ExerisHttpDispatcher(ExerisRouteRegistry routeRegistry,
                                 ExerisErrorMapper errorMapper,
                                 List<TelemetrySink> fallbackSinks) {
        this.routeRegistry = routeRegistry;
        this.errorMapper = errorMapper;
        this.fallbackSinks = List.copyOf(fallbackSinks);
    }

    @Override
    public void handle(HttpExchange exchange) throws HttpException {
        if (KernelProviders.TELEMETRY_SINKS.isBound() || fallbackSinks.isEmpty()) {
            dispatch(exchange);
        } else {
            ScopedValue.where(KernelProviders.TELEMETRY_SINKS, fallbackSinks)
                       .run(() -> dispatch(exchange));
        }
    }

    private void dispatch(HttpExchange exchange) {
        var kernelRequest = exchange.request();
        var request = new ExerisServerRequest(kernelRequest);
        HttpVersion version = kernelRequest.version();
        try {
            var handler = routeRegistry.resolve(request.method(), request.path());
            if (handler == null) {
                exchange.respond(errorMapper.mapStatus(HttpStatus.NOT_FOUND, version));
                return;
            }
            var response = handler.handle(request);
            exchange.respond(response.toKernelResponse(version));
        } catch (HttpException ex) {
            exchange.respond(errorMapper.map(ex, version));
        } catch (Exception ex) {
            exchange.respond(errorMapper.mapUnhandled(ex, version));
        }
    }
}
