/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.web.autoconfigure;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;

import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.spring.runtime.web.ExerisHttpDispatcher;
import eu.exeris.spring.runtime.web.ExerisRequestHandler;
import eu.exeris.spring.runtime.web.ExerisRoute;
import eu.exeris.spring.runtime.web.ExerisRouteRegistry;
import eu.exeris.spring.runtime.web.ExerisServerRequest;
import eu.exeris.spring.runtime.web.ExerisServerResponse;
import eu.exeris.spring.runtime.web.compat.ExerisCompatDispatcher;

class ExerisCompatAutoConfigurationTest {

    @Test
    void compatibilityMode_exposesCompatDispatcher_and_disablesPureBeans() {
        try (var context = createContext(Map.of("exeris.runtime.web.mode", "compatibility"))) {
            assertThat(context.getBeanNamesForType(ExerisCompatDispatcher.class)).hasSize(1);
            assertThat(context.getBeanNamesForType(ExerisHttpDispatcher.class)).isEmpty();
            assertThat(context.getBeanNamesForType(ExerisRouteRegistry.class)).isEmpty();
        }
    }

    @SuppressWarnings("null")
    private AnnotationConfigApplicationContext createContext(Map<String, Object> properties, Class<?>... extraConfigs) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        Map<String, Object> safeProperties = Map.copyOf(java.util.Objects.requireNonNull(properties, "properties"));
        context.getEnvironment().getPropertySources().addFirst(
                new MapPropertySource("testProps", safeProperties));
        context.register(ExerisWebAutoConfiguration.class, ExerisCompatAutoConfiguration.class, TestConfig.class);
        if (extraConfigs != null && extraConfigs.length > 0) {
            context.register(extraConfigs);
        }
        context.refresh();
        return context;
    }

    @Configuration
    static class TestConfig {

        @Bean
        @SuppressWarnings("unused")
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
