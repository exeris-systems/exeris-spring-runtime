/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.web.autoconfigure;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.SupplierJwtDecoder;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Module-integration tests for {@link ExerisCompatAutoConfiguration.CompatJwtDecoderConfiguration}
 * (ADR-041) — the compatibility re-activation of Spring Boot's servlet-web-gated OAuth2
 * resource-server {@link JwtDecoder} under {@code web-application-type=none}.
 *
 * <p>An {@link AnnotationConfigApplicationContext} is a non-web context, so
 * {@code @ConditionalOnNotWebApplication} matches — reproducing the Exeris-hosted scenario where
 * Spring Boot's own (servlet-gated) decoder auto-config never runs.
 */
class ExerisCompatJwtDecoderConfigurationTest {

    private static final String PREFIX = "spring.security.oauth2.resourceserver.jwt.";

    @Test
    void jwkSetUri_producesNimbusDecoder() {
        try (var context = contextWith(Map.of(PREFIX + "jwk-set-uri",
                "https://issuer.example.com/.well-known/jwks.json"))) {
            JwtDecoder decoder = context.getBean(JwtDecoder.class);
            assertThat(decoder).isInstanceOf(NimbusJwtDecoder.class);
        }
    }

    @Test
    void issuerUri_producesLazySupplierDecoder_noDiscoveryAtStartup() {
        try (var context = contextWith(Map.of(PREFIX + "issuer-uri", "https://issuer.example.com"))) {
            JwtDecoder decoder = context.getBean(JwtDecoder.class);
            // No OIDC discovery at refresh — a bogus issuer would otherwise fail context start.
            assertThat(decoder).isInstanceOf(SupplierJwtDecoder.class);
        }
    }

    @Test
    void publicKeyLocation_producesDecoderFromPemResource() {
        try (var context = contextWith(Map.of(PREFIX + "public-key-location",
                "classpath:test-rsa-public.pem"))) {
            JwtDecoder decoder = context.getBean(JwtDecoder.class);
            assertThat(decoder).isInstanceOf(NimbusJwtDecoder.class);
        }
    }

    @Test
    void noJwtProperties_producesNoDecoder() {
        try (var context = contextWith(Map.of())) {
            assertThat(context.getBeanNamesForType(JwtDecoder.class)).isEmpty();
        }
    }

    @Test
    void userDeclaredJwtDecoder_winsOverCompatDecoder() {
        try (var context = contextWith(
                Map.of(PREFIX + "jwk-set-uri", "https://issuer.example.com/jwks"),
                UserDecoderConfig.class)) {
            assertThat(context.getBeanNamesForType(JwtDecoder.class)).containsExactly("userJwtDecoder");
            assertThat(context.getBeanNamesForType(JwtDecoder.class)).doesNotContain("exerisCompatJwtDecoder");
        }
    }

    private static AnnotationConfigApplicationContext contextWith(Map<String, Object> properties,
                                                                  Class<?>... extraConfigs) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(
                new MapPropertySource("testProps", Map.copyOf(properties)));
        // Register any user config first, then the compat decoder config — mirroring the production
        // order where ExerisCompatAutoConfiguration (an @AutoConfiguration) is processed AFTER user
        // beans, so its @ConditionalOnMissingBean(JwtDecoder) sees an app-declared decoder.
        if (extraConfigs.length > 0) {
            context.register(extraConfigs);
        }
        context.register(ExerisCompatAutoConfiguration.CompatJwtDecoderConfiguration.class);
        context.refresh();
        return context;
    }

    @Configuration
    static class UserDecoderConfig {
        @Bean
        JwtDecoder userJwtDecoder() {
            return token -> {
                throw new UnsupportedOperationException("stub");
            };
        }
    }
}
