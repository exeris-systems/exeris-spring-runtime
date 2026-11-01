/*
 * Copyright (C) 2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.spring.runtime.web.compat;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import eu.exeris.kernel.spi.http.HttpMethod;

class ExerisHandlerMethodRegistryTest {

    @Test
    void resolve_prefersMostSpecificPathPatternOverRegistrationOrder() {
        try (var context = new AnnotationConfigApplicationContext(RouteConfig.class)) {
            ExerisHandlerMethodRegistry registry = context.getBean(ExerisHandlerMethodRegistry.class);

            var resolved = registry.resolve(HttpMethod.GET, "/compat-routes/fixed?x=1");

            assertThat(resolved).isPresent();
            assertThat(resolved.orElseThrow().handlerMethod().getMethod().getName()).isEqualTo("fixedRoute");
            assertThat(resolved.orElseThrow().pathVariables()).isEmpty();
        }
    }

    @Test
    void resolve_headFallsBackToGetMapping_forSafeReadRoutes() {
        try (var context = new AnnotationConfigApplicationContext(RouteConfig.class)) {
            ExerisHandlerMethodRegistry registry = context.getBean(ExerisHandlerMethodRegistry.class);

            var resolved = registry.resolve(HttpMethod.HEAD, "/compat-routes/fixed");

            assertThat(resolved).isPresent();
            assertThat(resolved.orElseThrow().handlerMethod().getMethod().getName()).isEqualTo("fixedRoute");
        }
    }

    /**
     * Code that drives the registry outside a container still populates it through the deprecated
     * {@code afterPropertiesSet()} delegate.
     */
    @Test
    @SuppressWarnings("removal")
    void deprecatedAfterPropertiesSet_populatesARegistryDrivenOutsideTheContainer() {
        try (var controllers = new AnnotationConfigApplicationContext(ControllersOnlyConfig.class)) {
            ExerisHandlerMethodRegistry registry = new ExerisHandlerMethodRegistry();
            registry.setApplicationContext(controllers);

            registry.afterPropertiesSet();

            var resolved = registry.resolve(HttpMethod.GET, "/compat-routes/fixed");
            assertThat(resolved).isPresent();
            assertThat(resolved.orElseThrow().handlerMethod().getMethod().getName()).isEqualTo("fixedRoute");
        }
    }

    @Configuration
    static class ControllersOnlyConfig {

        @Bean
        SpecificRouteController specificRouteController() {
            return new SpecificRouteController();
        }
    }

    @Configuration
    static class RouteConfig {

        @Bean
        GenericRouteController genericRouteController() {
            return new GenericRouteController();
        }

        @Bean
        SpecificRouteController specificRouteController() {
            return new SpecificRouteController();
        }

        @Bean
        ExerisHandlerMethodRegistry exerisHandlerMethodRegistry() {
            return new ExerisHandlerMethodRegistry();
        }
    }

    @RestController
    static class GenericRouteController {

        @GetMapping("/compat-routes/{id}")
        String variableRoute() {
            return "variable";
        }
    }

    @RestController
    static class SpecificRouteController {

        @GetMapping("/compat-routes/fixed")
        String fixedRoute() {
            return "fixed";
        }
    }
}
