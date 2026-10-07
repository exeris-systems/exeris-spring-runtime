/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.web.compat.security;

import eu.exeris.spring.boot.autoconfigure.compat.CompatibilityMode;

import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;

import java.util.List;

/**
 * The {@code spring.security.oauth2.resourceserver.jwt.*} settings this runtime needs, bound from the
 * {@link Environment} rather than from Spring Boot's own properties type.
 * <p><b>Mode:</b> Compatibility Mode only.
 *
 * @param jwkSetUri JWK Set endpoint; first key source tried
 * @param issuerUri issuer location; used for discovery and validation
 * @param publicKeyLocation resource location of an RSA public key,
 *     resolved through a {@code ResourceLoader} rather than bound as a
 *     {@code Resource}, so binding needs no resource-aware conversion service
 * @param audiences accepted {@code aud} claim values; empty means no
 *     audience validation
 * @param jwsAlgorithms accepted signature algorithms; empty means RS256
 * @implSpec ADR-041 built the compatibility decoder on {@code OAuth2ResourceServerProperties}.
 *     Across Spring Boot 3 and 4 lines, property names are stable while package coordinates moved.
 *     Binding property names directly via {@link Binder} ensures cross-line matrix compatibility
 *     without reflection.
 * @implNote Only what {@link ExerisCompatJwtDecoderFactory} consumes is bound. Opaque-token properties
 *     are out of scope.
 * @since 0.7
 * @see "ADR-041: Compatibility JWT Decoder"
 * @see "ADR-028: Multi-line Spring Matrix Strategy"
 */
@CompatibilityMode
public record ExerisResourceServerJwtProperties(
        String jwkSetUri,
        String issuerUri,
        String publicKeyLocation,
        List<String> audiences,
        List<String> jwsAlgorithms) {

    /** The prefix an application configures, identical on both Spring Boot lines. */
    public static final String PREFIX = "spring.security.oauth2.resourceserver.jwt";

    /**
     * Compact constructor validating and defensively copying list fields.
     *
     * @param jwkSetUri         JWK Set endpoint
     * @param issuerUri         issuer location
     * @param publicKeyLocation resource location of public key
     * @param audiences         accepted audiences
     * @param jwsAlgorithms     accepted signature algorithms
     */
    public ExerisResourceServerJwtProperties {
        audiences = audiences == null ? List.of() : List.copyOf(audiences);
        jwsAlgorithms = jwsAlgorithms == null ? List.of() : List.copyOf(jwsAlgorithms);
    }

    /**
     * Binds the settings from the environment.
     *
     * <p>Uses {@link Binder}, not raw {@code Environment.getProperty}, so relaxed binding and list
     * binding behave exactly as they do for an application writing these properties against Spring
     * Boot's own type — {@code jwk-set-uri} / {@code jwkSetUri} / {@code JWK_SET_URI} all bind, and
     * {@code audiences} accepts both the comma-separated and the indexed YAML form.
     *
     * @param environment the application environment; never {@code null}
     * @return the bound settings; absent values are {@code null} / empty, never a default
     */
    public static ExerisResourceServerJwtProperties bind(Environment environment) {
        return Binder.get(environment)
                .bind(PREFIX, Bindable.of(ExerisResourceServerJwtProperties.class))
                .orElseGet(() -> new ExerisResourceServerJwtProperties(null, null, null, null, null));
    }

    /**
     * Returns {@code true} when at least one key source is configured.
     *
     * <p>Mirrors the gate Spring Boot applies before creating its own decoder, so this runtime never
     * builds an unconfigured one.
     *
     * @return {@code true} if any key source is configured, {@code false} otherwise
     */
    public boolean hasKeySource() {
        return notBlank(jwkSetUri) || notBlank(publicKeyLocation) || notBlank(issuerUri);
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
