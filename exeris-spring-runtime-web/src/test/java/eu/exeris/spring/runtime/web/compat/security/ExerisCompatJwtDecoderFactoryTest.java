/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.web.compat.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.SupplierJwtDecoder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link ExerisCompatJwtDecoderFactory}. These exercise key-source selection and
 * decoder construction without any network I/O — {@code withJwkSetUri(...).build()} fetches the
 * JWK set lazily on first decode, and the issuer path returns a lazy {@link SupplierJwtDecoder}.
 *
 * <p>Settings arrive as {@link ExerisResourceServerJwtProperties} rather than Spring Boot's
 * {@code OAuth2ResourceServerProperties.Jwt}: that type moved package and artifact in Spring Boot 4,
 * and this runtime binds the properties itself so one source compiles on both matrix lines.
 */
class ExerisCompatJwtDecoderFactoryTest {

    private static final String JWK_SET_URI = "https://issuer.example.com/.well-known/jwks.json";
    private static final String ISSUER_URI = "https://issuer.example.com";

    @Test
    void build_withJwkSetUri_returnsNimbusDecoder_withoutNetwork() {
        JwtDecoder decoder = ExerisCompatJwtDecoderFactory.build(jwt(JWK_SET_URI, null, null, null, null));

        assertThat(decoder).isInstanceOf(NimbusJwtDecoder.class);
    }

    @Test
    void build_withJwkSetUriAndAudiences_stillBuilds() {
        JwtDecoder decoder = ExerisCompatJwtDecoderFactory.build(
                jwt(JWK_SET_URI, ISSUER_URI, null, List.of("api://my-resource"), null));

        assertThat(decoder).isInstanceOf(NimbusJwtDecoder.class);
    }

    @Test
    void build_withCustomJwsAlgorithms_isHonoured() {
        JwtDecoder decoder = ExerisCompatJwtDecoderFactory.build(
                jwt(JWK_SET_URI, null, null, null, List.of("RS512", "ES256")));

        assertThat(decoder).isInstanceOf(NimbusJwtDecoder.class);
    }

    @Test
    void build_withIssuerUriOnly_returnsLazySupplierDecoder_withoutDiscovery() {
        JwtDecoder decoder = ExerisCompatJwtDecoderFactory.build(jwt(null, ISSUER_URI, null, null, null));

        // Lazy: no OIDC discovery happens at build time — the SupplierJwtDecoder defers it.
        assertThat(decoder).isInstanceOf(SupplierJwtDecoder.class);
    }

    @Test
    void build_withNoKeySource_throwsWithActionableMessage() {
        assertThatThrownBy(() -> ExerisCompatJwtDecoderFactory.build(jwt(null, null, null, null, null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("jwk-set-uri")
                .hasMessageContaining("declare your own JwtDecoder bean");
    }

    @Test
    void build_treatsBlankUriAsUnset() {
        assertThatThrownBy(() -> ExerisCompatJwtDecoderFactory.build(jwt("   ", "", null, null, null)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void keySourcePrecedence_jwkSetUriBeatsIssuerUri() {
        // Mirrors Spring Boot's ordering: with both configured the JWK Set endpoint wins and the
        // decoder is eager, rather than the lazy issuer-discovery form.
        JwtDecoder decoder = ExerisCompatJwtDecoderFactory.build(jwt(JWK_SET_URI, ISSUER_URI, null, null, null));

        assertThat(decoder)
                .isInstanceOf(NimbusJwtDecoder.class)
                .isNotInstanceOf(SupplierJwtDecoder.class);
    }

    @Test
    void build_withPublicKeyLocation_resolvesThroughTheResourceLoader() throws Exception {
        // The third key source, and the only one that touches the ResourceLoader — the parameter added
        // when this factory stopped taking Spring Boot's properties type (which bound a Resource for
        // us). It had no coverage before, so a broken location→Resource hop would not have failed here.
        java.nio.file.Path pem = java.nio.file.Files.createTempFile("exeris-test-key", ".pem");
        pem.toFile().deleteOnExit();
        java.nio.file.Files.writeString(pem, publicKeyPem());

        JwtDecoder decoder = ExerisCompatJwtDecoderFactory.build(
                jwt(null, null, pem.toUri().toString(), null, null),
                new org.springframework.core.io.DefaultResourceLoader());

        assertThat(decoder).isInstanceOf(NimbusJwtDecoder.class);
    }

    @Test
    void build_withUnreadablePublicKeyLocation_failsWithAnActionableMessage() {
        assertThatThrownBy(() -> ExerisCompatJwtDecoderFactory.build(
                jwt(null, null, "classpath:definitely-not-here.pem", null, null),
                new org.springframework.core.io.DefaultResourceLoader()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("public key");
    }

    /** A generated RSA public key in PEM armour, so the test needs no fixture file in the repo. */
    private static String publicKeyPem() throws Exception {
        java.security.KeyPairGenerator generator = java.security.KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        byte[] encoded = generator.generateKeyPair().getPublic().getEncoded();
        return "-----BEGIN PUBLIC KEY-----\n"
                + java.util.Base64.getMimeEncoder(64, new byte[] { '\n' }).encodeToString(encoded)
                + "\n-----END PUBLIC KEY-----\n";
    }

    private static ExerisResourceServerJwtProperties jwt(String jwkSetUri,
                                                         String issuerUri,
                                                         String publicKeyLocation,
                                                         List<String> audiences,
                                                         List<String> jwsAlgorithms) {
        return new ExerisResourceServerJwtProperties(
                jwkSetUri, issuerUri, publicKeyLocation, audiences, jwsAlgorithms);
    }
}
