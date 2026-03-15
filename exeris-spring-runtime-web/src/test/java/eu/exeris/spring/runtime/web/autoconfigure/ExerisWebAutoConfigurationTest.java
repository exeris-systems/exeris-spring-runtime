/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.web.autoconfigure;

import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.spring.runtime.web.ExerisHttpDispatcher;
import eu.exeris.spring.runtime.web.ExerisRequestHandler;
import eu.exeris.spring.runtime.web.ExerisRoute;
import eu.exeris.spring.runtime.web.ExerisRouteRegistry;
import eu.exeris.spring.runtime.web.ExerisServerRequest;
import eu.exeris.spring.runtime.web.ExerisServerResponse;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ExerisWebAutoConfigurationTest {

    @Test
    void pureMode_registersAnnotatedRoutes_andExposesDispatcherBean() {
        try (var context = createContext(Map.of("exeris.runtime.web.mode", "pure"))) {
            ExerisRouteRegistry routeRegistry = context.getBean(ExerisRouteRegistry.class);
            ExerisRequestHandler handler = context.getBean(HelloHandler.class);

            assertThat(routeRegistry.resolve(HttpMethod.GET, "/hello")).isSameAs(handler);
            assertThat(context.getBeanNamesForType(ExerisHttpDispatcher.class)).hasSize(1);
        }
    }

    @Test
    void compatibilityMode_disablesPureModeWebAutoConfigurationBeans() {
        try (var context = createContext(Map.of("exeris.runtime.web.mode", "compatibility"))) {
            assertThat(context.getBeanNamesForType(ExerisRouteRegistry.class)).isEmpty();
            assertThat(context.getBeanNamesForType(ExerisHttpDispatcher.class)).isEmpty();
        }
    }

    private AnnotationConfigApplicationContext createContext(Map<String, Object> properties) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(
                new MapPropertySource("testProps", properties));
        context.register(ExerisWebAutoConfiguration.class, TestConfig.class);
        context.refresh();
        return context;
    }

    @Configuration
    static class TestConfig {

        @Bean
        HelloHandler helloHandler() {
            return new HelloHandler();
        }
    }

    @ExerisRoute(method = HttpMethod.GET, path = "/hello")
    static class HelloHandler implements ExerisRequestHandler {
        @Override
        public ExerisServerResponse handle(ExerisServerRequest request) {
            return ExerisServerResponse.ok().body("hello");
        }
    }
}