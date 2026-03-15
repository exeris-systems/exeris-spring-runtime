/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.web;

/**
 * Pure Mode application handler contract.
 *
 * <p>This is the Spring-facing extension point for application handlers in Exeris
 * Pure Mode. It is NOT a kernel SPI type — it is this integration layer's own
 * programming model interface. The kernel SPI extension point is
 * {@code eu.exeris.kernel.spi.http.HttpHandler}, which is implemented by
 * {@code ExerisHttpDispatcher}, not by application code.
 *
 * <h2>Threading Model</h2>
 * <p>Each invocation runs on a dedicated virtual thread (1 VT per HTTP request).
 * Implementations MUST NOT use {@code ThreadLocal} for request-scoped state.
 * Use {@link ExerisServerRequest} to access request metadata and
 * {@code ScopedValue} for any request-scoped context propagation.
 *
 * <h2>Response Contract</h2>
 * <p>The method MUST return a non-null {@link ExerisServerResponse}. It must not
 * call any kernel {@code HttpExchange} methods directly. The exchange lifecycle
 * is owned by {@code ExerisHttpDispatcher}.
 *
 * <h2>Registration</h2>
 * <p>Implementors must be Spring beans and must be annotated with
 * {@link ExerisRoute} to be registered in the route registry.
 *
 * @since 0.1.0
 */
@FunctionalInterface
public interface ExerisRequestHandler {

    ExerisServerResponse handle(ExerisServerRequest request) throws Exception;
}
