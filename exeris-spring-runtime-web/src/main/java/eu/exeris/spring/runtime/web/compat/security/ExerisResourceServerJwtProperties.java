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
 *
 * <h2>Why we bind these ourselves</h2>
 * <p>ADR-041 built the compatibility decoder on
 * {@code OAuth2ResourceServerProperties}, deliberately choosing Boot's <em>public</em> API over its
 * package-private internals. Spring Boot 4 then moved that class: from
 * {@code org.springframework.boot.autoconfigure.security.oauth2.resource} in
 * {@code spring-boot-autoconfigure} to
 * {@code org.springframework.boot.security.oauth2.server.resource.autoconfigure} in a new
 * {@code spring-boot-security-oauth2-resource-server} artifact. Same class, same nested types, new
 * coordinates.
 *
 * <p>ADR-028 obligation 1 requires one source tree to compile under both matrix profiles, so naming
 * either package breaks the other line. The available answers were a reflective bridge over the
 * relocated type, or this: notice that the thing which actually did <b>not</b> move is the
 * <em>property names</em>. They are the contract an application writes against, they are identical on
 * both lines, and {@code OnResourceServerJwtConfiguredCondition} in this same feature was already
 * reading them as literals. Binding them directly removes the version-specific type from the compile
 * path entirely — no reflection, no {@code @SbCompat} bridge, nothing to delete when the SB3 line is
 * dropped.
 *
 * <p>The cost is that this record restates five property names Spring Boot also declares. That is a
 * smaller and more visible surface than a reflective shim over a class whose package differs per line,
 * and property names are the more stable half of the pair — Boot moved the class twice while these
 * names stayed put.
 *
 * <h2>What is deliberately not bound</h2>
 * <p>Boot's type carries more than this ({@code authority-prefix}, {@code principal-claim-name},
 * {@code authorities-claim-name}, the whole {@code opaquetoken} branch). Only what
 * {@link ExerisCompatJwtDecoderFactory} consumes is bound. Binding fields nothing reads would imply
 * support this runtime does not provide — opaque-token resource servers are out of scope per ADR-041,
 * and the authority-mapping fields belong to a converter the application supplies.
 *
 * <h2>Mode</h2>
 * <p>Compatibility Mode only.
 *
 * @param jwkSetUri         {@code jwk-set-uri} — JWK Set endpoint; first key source tried
 * @param issuerUri         {@code issuer-uri} — issuer location; used for discovery and validation
 * @param publicKeyLocation {@code public-key-location} — resource location of an RSA public key,
 *                          resolved through a {@code ResourceLoader} rather than bound as a
 *                          {@code Resource}, so binding needs no resource-aware conversion service
 * @param audiences         {@code audiences} — accepted {@code aud} claim values; empty means no
 *                          audience validation
 * @param jwsAlgorithms     {@code jws-algorithms} — accepted signature algorithms; empty means RS256
 * @since 0.7.0
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
     */
    public boolean hasKeySource() {
        return notBlank(jwkSetUri) || notBlank(publicKeyLocation) || notBlank(issuerUri);
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
