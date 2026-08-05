/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.web.compat;

import java.util.List;
import java.util.Objects;

import eu.exeris.kernel.spi.exceptions.http.HttpException;
import eu.exeris.kernel.spi.http.HttpExchange;
import eu.exeris.kernel.spi.http.HttpHandler;
import eu.exeris.kernel.spi.http.HttpHeader;
import eu.exeris.kernel.spi.http.HttpStatus;
import eu.exeris.spring.runtime.web.ExerisErrorMapper;
import eu.exeris.spring.runtime.web.compat.filter.ExerisSecurityContextFilter;
import eu.exeris.spring.runtime.web.compat.security.InvalidBearerTokenException;
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

    /** RFC 9110 §11.6.1 makes a challenge mandatory on 401; bearer is the only scheme supported. */
    private static final List<HttpHeader> BEARER_CHALLENGE =
            List.of(new HttpHeader("WWW-Authenticate", "Bearer"));

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
        // Re-bind kernel provider slots (persistence engine, memory allocator) for the duration of
        // the WHOLE request — security-context population AND the handler invocation — so both see
        // them via ScopedValue. No-op when already bound.
        //
        // populateContext MUST run inside this scope: a canonical identity-resolution
        // JwtAuthenticationConverter does per-request DB access (e.g. user lookup) through
        // ExerisDataSource → KernelProviders.PERSISTENCE_ENGINE. Run before bind(), the slot is
        // unbound and getConnection() fails with "PersistenceEngine is not bound in the current
        // scope"; the converter then yields no Authentication, surfacing as a 500 in the handler.
        // The filter must get the kernel pool exactly like the handlers do.
        kernelProviderBinder.bind(() -> {
            try {
                if (securityFilter != null && !populateSecurityContext(exchange)) {
                    // Credential rejected — the 401 has already been written. Dispatching anyway
                    // would run the handler for a caller we just refused.
                    return;
                }
                dispatchAndRespond(exchange);
            } finally {
                if (securityFilter != null) {
                    securityFilter.clearContext();
                }
            }
        });
    }

    /**
     * Populates the security context, answering 401 when the presented Bearer token is invalid.
     *
     * <p>The rejection is translated here rather than being allowed to escape {@link #handle}: an
     * escaping exception reaches the kernel engine, which answers 500 — turning "your token is bad"
     * into "the server is broken". It is also handled separately from {@link #dispatchAndRespond}'s
     * catch-all, because that one logs at ERROR and maps to 500; a rejected credential is neither an
     * error of ours nor a server fault, and at ERROR level a token-scanning client would flood the
     * log.
     *
     * @return {@code true} when dispatch should proceed, {@code false} when the request has already
     *         been answered
     */
    private boolean populateSecurityContext(HttpExchange exchange) {
        try {
            securityFilter.populateContext(exchange.request());
            return true;
        } catch (InvalidBearerTokenException _) {
            exchange.respond(errorMapper.mapStatus(
                    HttpStatus.UNAUTHORIZED,
                    exchange.request().version(),
                    BEARER_CHALLENGE));
            return false;
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

