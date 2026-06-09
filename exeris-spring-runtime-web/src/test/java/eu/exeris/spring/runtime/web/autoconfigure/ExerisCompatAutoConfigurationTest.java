/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.web.autoconfigure;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.type.AnnotationMetadata;

import eu.exeris.kernel.spi.http.HttpHeader;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpRequest;
import eu.exeris.kernel.spi.http.HttpVersion;
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
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.oauth2.jwt.JwtDecoder;

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
    void compatJwtDecoder_activatesSecurityFilter_endToEnd_underNone() {
        // The full ADR-041 path in one shot: a non-web (web-application-type=none) context with a
        // resource-server jwk-set-uri configured and no app-declared JwtDecoder. The compat decoder
        // must be created AND ExerisSecurityContextFilter (gated @ConditionalOnBean(JwtDecoder)) must
        // activate off it. Driven through ApplicationContextRunner + AutoConfigurations so the
        // @ConditionalOnBean ordering is evaluated with real auto-configuration semantics (it is
        // unreliable under manual @Configuration registration) — this proves the decoder→filter
        // wiring on the actual code path, not an artefact of registration order.
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        ExerisWebAutoConfiguration.class,
                        ExerisCompatJwtDecoderAutoConfiguration.class,
                        ExerisCompatAutoConfiguration.class))
                .withUserConfiguration(TestConfig.class)
                .withPropertyValues(
                        "exeris.runtime.web.mode=compatibility",
                        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri="
                                + "https://issuer.example.com/.well-known/jwks.json")
                .run(context -> {
                    assertThat(context).hasBean("exerisCompatJwtDecoder");
                    assertThat(context).hasSingleBean(JwtDecoder.class);
                    assertThat(context).hasSingleBean(ExerisSecurityContextFilter.class);
                });
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

    @Test
    void securityFilter_honoursUserSuppliedJwtConverter_andIgnoresUnrelatedConverters() {
        try (var context = new AnnotationConfigApplicationContext()) {
            // Register the user beans first so @ConditionalOnBean(JwtDecoder) sees the decoder,
            // then the security wiring. Isolated to SecurityFilterConfiguration to avoid pulling
            // in the full compat dispatcher graph.
            context.register(SecurityWiringConfig.class,
                    ExerisCompatAutoConfiguration.SecurityFilterConfiguration.class);
            context.refresh();

            ExerisSecurityContextFilter filter = context.getBean(ExerisSecurityContextFilter.class);
            try {
                // Drive the wired filter rather than reflecting its private field: the custom
                // Converter<Jwt, ? extends AbstractAuthenticationToken> bean maps to ROLE_CUSTOM,
                // whereas the scope-only default would yield SCOPE_read and the unrelated
                // Converter<String, Integer> bean must not be mistaken for the JWT converter.
                filter.populateContext(bearerRequest("any-token"));

                Authentication auth = SecurityContextHolder.getContext().getAuthentication();
                assertThat(auth).isNotNull();
                assertThat(auth.getAuthorities())
                        .extracting(Object::toString)
                        .containsExactly("ROLE_CUSTOM");
            } finally {
                SecurityContextHolder.clearContext();
            }
        }
    }

    private static HttpRequest bearerRequest(String token) {
        return HttpRequest.noBody(HttpMethod.GET, "/test", HttpVersion.HTTP_1_1,
                List.of(new HttpHeader("Authorization", "Bearer " + token)));
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

    /**
     * Mirrors a brownfield app on the compat path: a {@code JwtDecoder} bean (Spring's own
     * auto-config is web-application-gated and absent under {@code web-application-type=none}),
     * a custom {@code Converter<Jwt, ? extends AbstractAuthenticationToken>} for authority mapping,
     * and an unrelated {@code Converter<String, Integer>} that must not be mistaken for the former.
     */
    @Configuration
    static class SecurityWiringConfig {

        @Bean
        org.springframework.security.oauth2.jwt.JwtDecoder jwtDecoder() {
            return token -> org.springframework.security.oauth2.jwt.Jwt.withTokenValue(token)
                    .header("alg", "none")
                    .subject("user-1")
                    .claim("scope", "read")
                    .build();
        }

        @Bean
        org.springframework.core.convert.converter.Converter<
                org.springframework.security.oauth2.jwt.Jwt,
                ? extends org.springframework.security.authentication.AbstractAuthenticationToken> customJwtConverter() {
            return jwt -> new org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken(
                    jwt,
                    java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_CUSTOM")));
        }

        @Bean
        org.springframework.core.convert.converter.Converter<String, Integer> unrelatedConverter() {
            return value -> {
                try {
                    return Integer.valueOf(value);
                } catch (NumberFormatException ex) {
                    throw new IllegalArgumentException("Not an integer: " + value, ex);
                }
            };
        }
    }
}
