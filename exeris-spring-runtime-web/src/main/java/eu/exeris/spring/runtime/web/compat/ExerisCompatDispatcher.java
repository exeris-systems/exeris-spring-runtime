/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.web.compat;

import java.util.Objects;

import eu.exeris.kernel.spi.exceptions.http.HttpException;
import eu.exeris.kernel.spi.http.HttpExchange;
import eu.exeris.kernel.spi.http.HttpHandler;
import eu.exeris.kernel.spi.http.HttpStatus;
import eu.exeris.spring.runtime.web.ExerisErrorMapper;
import eu.exeris.spring.runtime.web.compat.filter.ExerisSecurityContextFilter;
import eu.exeris.spring.runtime.web.scope.KernelProviderBinder;
import org.springframework.lang.Nullable;

/**
 * Exeris-owned compatibility dispatcher that routes inbound exchanges through the
 * {@link ExerisSpringMvcBridge}. Returns 404 when no route matches; delegates
 * exception handling to the bridge.
 *
 * <p>If an {@link ExerisSecurityContextFilter} is injected, the security context is
 * populated from the Bearer token before dispatch and cleared deterministically in
 * {@code finally}. If no filter is wired, the dispatcher runs without security context
 * (anonymous — no authentication attempted).
 */
@CompatibilityMode
public final class ExerisCompatDispatcher implements HttpHandler {

    private static final System.Logger LOGGER = System.getLogger(ExerisCompatDispatcher.class.getName());

    private final ExerisSpringMvcBridge mvcBridge;
    private final ExerisErrorMapper errorMapper;
    @Nullable
    private final ExerisSecurityContextFilter securityFilter;
    private final KernelProviderBinder kernelProviderBinder;

    public ExerisCompatDispatcher(ExerisSpringMvcBridge mvcBridge,
                                  ExerisErrorMapper errorMapper,
                                  @Nullable ExerisSecurityContextFilter securityFilter) {
        this(mvcBridge, errorMapper, securityFilter, KernelProviderBinder.noop());
    }

    /**
     * Canonical constructor. Adds the {@link KernelProviderBinder} that re-binds kernel provider
     * {@code ScopedValue} slots (persistence engine, memory allocator) around the handler
     * invocation. The compat path routes Spring {@code @RestController} handlers through
     * JPA/Hibernate → {@code ExerisDataSource}, which reads {@code PERSISTENCE_ENGINE} on the
     * handler thread; that thread (the transport carrier) carries no kernel bootstrap bindings,
     * so without this re-bind the compat datasource is unusable on the request path. With
     * {@link KernelProviderBinder#noop()} (default and test path) it is a zero-cost pass-through.
     */
    public ExerisCompatDispatcher(ExerisSpringMvcBridge mvcBridge,
                                  ExerisErrorMapper errorMapper,
                                  @Nullable ExerisSecurityContextFilter securityFilter,
                                  KernelProviderBinder kernelProviderBinder) {
        this.mvcBridge = mvcBridge;
        this.errorMapper = errorMapper;
        this.securityFilter = securityFilter;
        this.kernelProviderBinder =
                Objects.requireNonNull(kernelProviderBinder, "kernelProviderBinder must not be null");
    }

    @Override
    public void handle(HttpExchange exchange) throws HttpException {
        if (securityFilter != null) {
            securityFilter.populateContext(exchange.request());
        }
        try {
            // Re-bind kernel provider slots (persistence engine, memory allocator) for the
            // duration of the handler invocation so JPA/Hibernate → ExerisDataSource and the
            // response codec see them via ScopedValue. No-op when already bound.
            kernelProviderBinder.bind(() -> dispatchAndRespond(exchange));
        } finally {
            if (securityFilter != null) {
                securityFilter.clearContext();
            }
        }
    }

    private void dispatchAndRespond(HttpExchange exchange) {
        ExerisSpringMvcBridge.DispatchResult result;
        try {
            result = mvcBridge.dispatch(exchange);
        } catch (Exception ex) {
            // The compat exception resolver re-throws when no @ExceptionHandler matches; that
            // exception lands here and maps to a body-less 500. Log the cause or it is lost —
            // nothing else logs it on the compat path.
            LOGGER.log(System.Logger.Level.ERROR,
                    () -> "Unhandled exception during Exeris compat dispatch of "
                            + exchange.request().method() + " " + exchange.request().path()
                            + " — mapped to 500", ex);
            exchange.respond(errorMapper.mapUnhandled(ex, exchange.request().version()));
            return;
        }

        switch (result) {
            case ExerisSpringMvcBridge.DispatchResult.Handled(var response) ->
                    exchange.respond(response.toKernelResponse(exchange.request().version()));
            case ExerisSpringMvcBridge.DispatchResult.NotHandled() ->
                    exchange.respond(errorMapper.mapStatus(HttpStatus.NOT_FOUND, exchange.request().version()));
        }
    }
}

