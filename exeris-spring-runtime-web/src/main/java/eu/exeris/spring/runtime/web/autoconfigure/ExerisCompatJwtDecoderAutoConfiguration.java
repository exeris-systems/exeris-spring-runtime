/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.web.autoconfigure;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnNotWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.security.oauth2.resource.OAuth2ResourceServerProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.type.AnnotatedTypeMetadata;

import eu.exeris.spring.runtime.web.compat.CompatibilityMode;
import eu.exeris.spring.runtime.web.compat.security.ExerisCompatJwtDecoderFactory;

/**
 * Compatibility-mode re-activation of Spring Boot's OAuth2 resource-server {@link
 * org.springframework.security.oauth2.jwt.JwtDecoder JwtDecoder} (ADR-041).
 *
 * <h2>Why a separate auto-configuration ordered {@code before} {@link ExerisCompatAutoConfiguration}</h2>
 * <p>Spring Boot's {@code OAuth2ResourceServerAutoConfiguration} is
 * {@code @ConditionalOnWebApplication(type = SERVLET)}, so under {@code web-application-type=none}
 * (the Exeris-hosted shape) no {@code JwtDecoder} bean is created, and
 * {@code ExerisSecurityContextFilter} — gated {@code @ConditionalOnBean(JwtDecoder)} inside
 * {@link ExerisCompatAutoConfiguration} — never activates. A brownfield JWT resource server thus
 * silently loses authentication.
 *
 * <p>The decoder must therefore exist <b>before</b> the security filter's {@code @ConditionalOnBean}
 * is evaluated. {@code @ConditionalOnBean} only reliably observes beans contributed by
 * auto-configurations processed <em>earlier</em> — not a sibling nested {@code @Configuration} of the
 * same auto-config. So the decoder lives in its own auto-configuration ordered
 * {@code @AutoConfiguration(before = ExerisCompatAutoConfiguration.class)}; this mirrors how Spring
 * Boot itself separates the resource-server decoder from the security-filter-chain configuration.
 *
 * <h2>Construction</h2>
 * <p>The decoder is built by {@link ExerisCompatJwtDecoderFactory} from {@code
 * spring.security.oauth2.resourceserver.jwt.*} using public Spring Security factories only —
 * a faithful mirror of Spring Boot's own validator wiring, never bespoke token validation.
 *
 * <h2>Mode</h2>
 * <p>Compatibility Mode only — active only when {@code exeris.runtime.web.mode=compatibility}.
 *
 * @since 0.5.0
 */
@AutoConfiguration(before = ExerisCompatAutoConfiguration.class)
@ConditionalOnClass(name = "org.springframework.security.oauth2.jwt.JwtDecoder")
@ConditionalOnProperty(prefix = "exeris.runtime.web", name = "mode", havingValue = "compatibility")
@ConditionalOnNotWebApplication
@EnableConfigurationProperties(OAuth2ResourceServerProperties.class)
@CompatibilityMode
public class ExerisCompatJwtDecoderAutoConfiguration {

    /**
     * Re-creates the resource-server decoder absent under {@code web-application-type=none}.
     * {@code @ConditionalOnMissingBean} keeps this inert whenever a decoder already exists
     * (a servlet deployment, or an app-declared {@code JwtDecoder} bean — the app's wins).
     */
    @Bean
    @ConditionalOnMissingBean(type = "org.springframework.security.oauth2.jwt.JwtDecoder")
    @Conditional(OnResourceServerJwtConfiguredCondition.class)
    public org.springframework.security.oauth2.jwt.JwtDecoder exerisCompatJwtDecoder(
            OAuth2ResourceServerProperties properties) {
        return ExerisCompatJwtDecoderFactory.build(properties.getJwt());
    }

    /**
     * Matches when at least one resource-server JWT key source is configured
     * ({@code jwk-set-uri} / {@code public-key-location} / {@code issuer-uri}) — mirrors the
     * property gating Spring Boot applies before creating its own decoder, so we never build an
     * unconfigured decoder.
     */
    static final class OnResourceServerJwtConfiguredCondition implements Condition {

        private static final String PREFIX = "spring.security.oauth2.resourceserver.jwt.";

        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            var environment = context.getEnvironment();
            return environment.containsProperty(PREFIX + "jwk-set-uri")
                    || environment.containsProperty(PREFIX + "public-key-location")
                    || environment.containsProperty(PREFIX + "issuer-uri");
        }
    }
}
