/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.web;

import eu.exeris.kernel.spi.http.HttpMethod;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Registers a Spring bean as a Pure Mode Exeris HTTP handler.
 *
 * <p>Beans annotated (directly or on their class) with {@code @ExerisRoute} are
 * discovered by {@code ExerisRouteRegistry} at startup and registered as
 * route entries in the Exeris dispatcher.
 *
 * <p>This annotation is the Pure Mode counterpart to {@code @RequestMapping}.
 * It has no relationship to servlet dispatch, Spring MVC's {@code DispatcherServlet},
 * or the Compatibility Mode bridge. It activates only when
 * {@code exeris.runtime.web.mode=pure} (the default).
 *
 * <h2>Usage on the Implementing Class</h2>
 * {@snippet lang="java" :
 * @Component
 * @ExerisRoute(method = HttpMethod.GET, path = "/status")
 * public class StatusHandler implements ExerisRequestHandler {
 *     @Override
 *     public ExerisServerResponse handle(ExerisServerRequest request) {
 *         return ExerisServerResponse.ok().body("UP");
 *     }
 * }
 * }
 *
 * <p>The bean must implement {@link ExerisRequestHandler}. Multiple routes on the same
 * bean are not supported; declare separate beans for separate routes.
 *
 * @since 0.1
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface ExerisRoute {

    /**
     * HTTP method to match.
     *
     * @return matched HTTP method, defaults to GET
     */
    HttpMethod method() default HttpMethod.GET;

    /**
     * URI path pattern to match.
     *
     * @return URI path pattern
     */
    String path();
}
