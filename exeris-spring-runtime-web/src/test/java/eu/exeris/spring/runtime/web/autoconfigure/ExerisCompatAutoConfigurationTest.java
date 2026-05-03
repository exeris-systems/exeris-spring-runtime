/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.web.autoconfigure;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.type.AnnotationMetadata;

import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.spring.boot.autoconfigure.ExerisRuntimeLifecycle;
import eu.exeris.spring.boot.autoconfigure.ExerisRuntimeProperties;
import eu.exeris.spring.runtime.web.ExerisHttpDispatcher;
import eu.exeris.spring.runtime.web.ExerisRequestHandler;
import eu.exeris.spring.runtime.web.ExerisRoute;
import eu.exeris.spring.runtime.web.ExerisRouteRegistry;
import eu.exeris.spring.runtime.web.ExerisServerRequest;
import eu.exeris.spring.runtime.web.ExerisServerResponse;
import eu.exeris.spring.runtime.web.compat.ExerisCompatDispatcher;
import eu.exeris.spring.runtime.web.compat.filter.ExerisSecurityContextFilter;

class ExerisCompatAutoConfigurationTest {

    @Test
    void compatibilityMode_exposesCompatDispatcher_and_disablesPureBeans() {
        try (var context = createContext(Map.of("exeris.runtime.web.mode", "compatibility"))) {
            assertThat(context.getBeanNamesForType(ExerisCompatDispatcher.class)).hasSize(1);
            assertThat(context.getBeanNamesForType(ExerisHttpDispatcher.class)).isEmpty();
            assertThat(context.getBeanNamesForType(ExerisRouteRegistry.class)).isEmpty();
        }
    }

    @Test
    void securityFilterCondition_matchesWhenSpringSecurityFilterChainIsAbsent() {
        try (var context = new AnnotationConfigApplicationContext()) {
            boolean matches = new ExerisCompatAutoConfiguration.SecurityFilterConfiguration.NoSecurityFilterChainCondition()
                    .matches(conditionContext(context), AnnotationMetadata.introspect(ExerisCompatAutoConfiguration.class));

            assertThat(matches).isTrue();
        }
    }

    @Test
    void securityFilterCondition_blocksFallbackFilterWhenSpringSecurityFilterChainBeanExists() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getBeanFactory().registerSingleton("springSecurityFilterChain", new Object());

            boolean matches = new ExerisCompatAutoConfiguration.SecurityFilterConfiguration.NoSecurityFilterChainCondition()
                    .matches(conditionContext(context), AnnotationMetadata.introspect(ExerisCompatAutoConfiguration.class));

            assertThat(matches).isFalse();
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

    private static ConditionContext conditionContext(AnnotationConfigApplicationContext context) {
        return new ConditionContext() {
            @Override
            public BeanDefinitionRegistry getRegistry() {
                return context;
            }

            @Override
            public ConfigurableListableBeanFactory getBeanFactory() {
                return context.getBeanFactory();
            }

            @Override
            public org.springframework.core.env.Environment getEnvironment() {
                return context.getEnvironment();
            }

            @Override
            public ResourceLoader getResourceLoader() {
                return context;
            }

            @Override
            public ClassLoader getClassLoader() {
                return context.getClassLoader();
            }
        };
    }

    @Configuration
    static class TestConfig {

        @Bean
        ExerisRuntimeLifecycle exerisRuntimeLifecycle() {
            ExerisRuntimeProperties properties = new ExerisRuntimeProperties(
                    true,
                    false,
                    new ExerisRuntimeProperties.WebProperties(ExerisRuntimeProperties.Mode.COMPATIBILITY),
                    new ExerisRuntimeProperties.LifecycleProperties(),
                    new ExerisRuntimeProperties.ShutdownProperties());
            ExerisRuntimeLifecycle lifecycle = new ExerisRuntimeLifecycle(properties, null, Optional.empty());
            forceRunning(lifecycle);
            return lifecycle;
        }

        @Bean
        @SuppressWarnings("unused")
        HelloHandler helloHandler() {
            return new HelloHandler();
        }

        private static void forceRunning(ExerisRuntimeLifecycle lifecycle) {
            try {
                Field running = ExerisRuntimeLifecycle.class.getDeclaredField("running");
                running.setAccessible(true);
                running.setBoolean(lifecycle, true);
            } catch (ReflectiveOperationException ex) {
                throw new IllegalStateException("Unable to mark ExerisRuntimeLifecycle as running for test setup", ex);
            }
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
