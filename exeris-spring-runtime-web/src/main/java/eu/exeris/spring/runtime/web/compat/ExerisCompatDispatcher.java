/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.web.compat;

import eu.exeris.kernel.spi.exceptions.http.HttpException;
import eu.exeris.kernel.spi.http.HttpExchange;
import eu.exeris.kernel.spi.http.HttpHandler;
import eu.exeris.kernel.spi.http.HttpStatus;
import eu.exeris.spring.runtime.web.ExerisErrorMapper;
import eu.exeris.spring.runtime.web.compat.filter.ExerisSecurityContextFilter;
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
public final class ExerisCompatDispatcher implements HttpHandler {

    private final ExerisSpringMvcBridge mvcBridge;
    private final ExerisErrorMapper errorMapper;
    @Nullable
    private final ExerisSecurityContextFilter securityFilter;

    public ExerisCompatDispatcher(ExerisSpringMvcBridge mvcBridge,
                                  ExerisErrorMapper errorMapper) {
        this(mvcBridge, errorMapper, null);
    }

    public ExerisCompatDispatcher(ExerisSpringMvcBridge mvcBridge,
                                  ExerisErrorMapper errorMapper,
                                  @Nullable ExerisSecurityContextFilter securityFilter) {
        this.mvcBridge = mvcBridge;
        this.errorMapper = errorMapper;
        this.securityFilter = securityFilter;
    }

    @Override
    public void handle(HttpExchange exchange) throws HttpException {
        if (securityFilter != null) {
            securityFilter.populateContext(exchange.request());
        }
        try {
            ExerisSpringMvcBridge.DispatchResult result;
            try {
                result = mvcBridge.dispatch(exchange);
            } catch (Exception ex) {
                exchange.respond(errorMapper.mapUnhandled(ex, exchange.request().version()));
                return;
            }

            switch (result) {
                case ExerisSpringMvcBridge.DispatchResult.Handled(var response) ->
                        exchange.respond(response.toKernelResponse(exchange.request().version()));
                case ExerisSpringMvcBridge.DispatchResult.NotHandled() ->
                        exchange.respond(errorMapper.mapStatus(HttpStatus.NOT_FOUND, exchange.request().version()));
            }
        } finally {
            if (securityFilter != null) {
                securityFilter.clearContext();
            }
        }
    }
}

