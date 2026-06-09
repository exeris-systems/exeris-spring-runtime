/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.web.compat.security;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.security.oauth2.resource.OAuth2ResourceServerProperties;
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
 */
class ExerisCompatJwtDecoderFactoryTest {

    @Test
    void build_withJwkSetUri_returnsNimbusDecoder_withoutNetwork() {
        OAuth2ResourceServerProperties.Jwt jwt = new OAuth2ResourceServerProperties.Jwt();
        jwt.setJwkSetUri("https://issuer.example.com/.well-known/jwks.json");

        JwtDecoder decoder = ExerisCompatJwtDecoderFactory.build(jwt);

        assertThat(decoder).isInstanceOf(NimbusJwtDecoder.class);
    }

    @Test
    void build_withJwkSetUriAndAudiences_stillBuilds() {
        OAuth2ResourceServerProperties.Jwt jwt = new OAuth2ResourceServerProperties.Jwt();
        jwt.setJwkSetUri("https://issuer.example.com/jwks");
        jwt.setIssuerUri("https://issuer.example.com");
        jwt.setAudiences(List.of("api://my-resource"));

        JwtDecoder decoder = ExerisCompatJwtDecoderFactory.build(jwt);

        assertThat(decoder).isInstanceOf(NimbusJwtDecoder.class);
    }

    @Test
    void build_withCustomJwsAlgorithms_isHonoured() {
        OAuth2ResourceServerProperties.Jwt jwt = new OAuth2ResourceServerProperties.Jwt();
        jwt.setJwkSetUri("https://issuer.example.com/jwks");
        jwt.setJwsAlgorithms(List.of("RS512", "ES256"));

        JwtDecoder decoder = ExerisCompatJwtDecoderFactory.build(jwt);

        assertThat(decoder).isInstanceOf(NimbusJwtDecoder.class);
    }

    @Test
    void build_withIssuerUriOnly_returnsLazySupplierDecoder_withoutDiscovery() {
        OAuth2ResourceServerProperties.Jwt jwt = new OAuth2ResourceServerProperties.Jwt();
        jwt.setIssuerUri("https://issuer.example.com");

        JwtDecoder decoder = ExerisCompatJwtDecoderFactory.build(jwt);

        // Lazy: no OIDC discovery happens at build time — the SupplierJwtDecoder defers it.
        assertThat(decoder).isInstanceOf(SupplierJwtDecoder.class);
    }

    @Test
    void build_withNoKeySource_throwsWithActionableMessage() {
        OAuth2ResourceServerProperties.Jwt jwt = new OAuth2ResourceServerProperties.Jwt();

        assertThatThrownBy(() -> ExerisCompatJwtDecoderFactory.build(jwt))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("jwk-set-uri")
                .hasMessageContaining("declare your own JwtDecoder bean");
    }

    @Test
    void build_treatsBlankUriAsUnset() {
        OAuth2ResourceServerProperties.Jwt jwt = new OAuth2ResourceServerProperties.Jwt();
        jwt.setJwkSetUri("   ");
        jwt.setIssuerUri("");

        assertThatThrownBy(() -> ExerisCompatJwtDecoderFactory.build(jwt))
                .isInstanceOf(IllegalStateException.class);
    }
}
