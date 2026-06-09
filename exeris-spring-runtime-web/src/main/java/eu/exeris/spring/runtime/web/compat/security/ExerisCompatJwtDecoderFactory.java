/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.web.compat.security;

import eu.exeris.spring.runtime.web.compat.CompatibilityMode;
import org.springframework.boot.autoconfigure.security.oauth2.resource.OAuth2ResourceServerProperties;
import org.springframework.core.io.Resource;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.SupplierJwtDecoder;

import java.io.IOException;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Compatibility-mode factory that re-creates the OAuth2 resource-server {@link JwtDecoder}
 * Spring Boot would normally auto-configure — but which is silently absent under
 * {@code spring.main.web-application-type=none}.
 *
 * <h2>Why this exists (ADR-041)</h2>
 * <p>Spring Boot's {@code OAuth2ResourceServerAutoConfiguration} is
 * {@code @ConditionalOnWebApplication(type = SERVLET)}. When an app is hosted on the Exeris
 * runtime, Spring sees {@code web-application-type=none} (Exeris owns the transport, not a
 * servlet container), so no {@link JwtDecoder} bean is created — and
 * {@code ExerisSecurityContextFilter} (gated on a {@link JwtDecoder} bean) never activates.
 * A brownfield JWT resource server therefore loses authentication purely as a side effect of
 * the migration. This factory closes that gap on the Compatibility path.
 *
 * <h2>Faithful, not hand-rolled</h2>
 * <p>The decoder and its validator chain are built with <b>public Spring Security factories</b>
 * ({@link NimbusJwtDecoder}, {@link JwtDecoders#fromIssuerLocation}, {@link JwtValidators}) and
 * mirror exactly what Spring Boot's {@code OAuth2ResourceServerJwtConfiguration.JwtDecoderConfiguration}
 * does — same key sources (jwk-set-uri / issuer-uri / public-key-location), same default validators
 * ({@link JwtValidators#createDefaultWithIssuer} / {@link JwtValidators#createDefault}), same audience
 * validation. No bespoke token validation is invented here: getting JWT validation subtly wrong is a
 * security defect, so this stays a thin re-wiring of Spring's own building blocks.
 *
 * <h2>Mode</h2>
 * <p>Compatibility Mode only. Lives in {@code *.compat.security.*}; never on a pure-mode path.
 *
 * @since 0.5.0
 */
@CompatibilityMode
public final class ExerisCompatJwtDecoderFactory {

    private ExerisCompatJwtDecoderFactory() {
    }

    /**
     * Builds a {@link JwtDecoder} from the bound {@link OAuth2ResourceServerProperties} JWT settings,
     * choosing the key source the same way Spring Boot does: jwk-set-uri, then public-key-location,
     * then issuer-uri (lazy, via {@link SupplierJwtDecoder} so issuer discovery happens on first use).
     *
     * @param jwt the {@code spring.security.oauth2.resourceserver.jwt.*} properties (never null)
     * @return a fully validated decoder, mirroring Spring Boot's resource-server decoder
     * @throws IllegalStateException if none of jwk-set-uri / public-key-location / issuer-uri is set
     */
    public static JwtDecoder build(OAuth2ResourceServerProperties.Jwt jwt) {
        String jwkSetUri = trimToNull(jwt.getJwkSetUri());
        String issuerUri = trimToNull(jwt.getIssuerUri());
        Resource publicKeyLocation = jwt.getPublicKeyLocation();
        List<String> audiences = jwt.getAudiences();

        if (jwkSetUri != null) {
            NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri)
                    .jwsAlgorithms(algorithms -> applyJwsAlgorithms(jwt.getJwsAlgorithms(), algorithms))
                    .build();
            decoder.setJwtValidator(validators(issuerUri, audiences));
            return decoder;
        }

        if (publicKeyLocation != null) {
            NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(readPublicKey(publicKeyLocation))
                    .signatureAlgorithm(firstSignatureAlgorithm(jwt.getJwsAlgorithms()))
                    .build();
            decoder.setJwtValidator(validators(issuerUri, audiences));
            return decoder;
        }

        if (issuerUri != null) {
            // Lazy, exactly like Spring Boot: issuer-location discovery (a network call) is deferred
            // to first decode rather than run at context refresh.
            final String issuer = issuerUri;
            return new SupplierJwtDecoder(() -> {
                NimbusJwtDecoder decoder = (NimbusJwtDecoder) JwtDecoders.fromIssuerLocation(issuer);
                if (audiences != null && !audiences.isEmpty()) {
                    // fromIssuerLocation already wires createDefaultWithIssuer; add audience on top.
                    decoder.setJwtValidator(validators(issuer, audiences));
                }
                return decoder;
            });
        }

        throw new IllegalStateException(
                "Cannot build a compatibility JwtDecoder: none of "
                + "spring.security.oauth2.resourceserver.jwt.{jwk-set-uri,public-key-location,issuer-uri} is set. "
                + "Either configure one of them, or declare your own JwtDecoder bean.");
    }

    /**
     * Mirrors Spring Boot's validator wiring: the default timestamp validator (plus issuer validator
     * when an issuer is configured), optionally delegating to an audience-claim validator when
     * {@code spring.security.oauth2.resourceserver.jwt.audiences} is set.
     */
    private static OAuth2TokenValidator<Jwt> validators(String issuerUri, List<String> audiences) {
        OAuth2TokenValidator<Jwt> base = (issuerUri != null)
                ? JwtValidators.createDefaultWithIssuer(issuerUri)
                : JwtValidators.createDefault();
        if (audiences == null || audiences.isEmpty()) {
            return base;
        }
        JwtClaimValidator<List<String>> audienceValidator = new JwtClaimValidator<>(
                "aud", tokenAudiences -> tokenAudiences != null && !Collections.disjoint(audiences, tokenAudiences));
        return new DelegatingOAuth2TokenValidator<>(List.of(base, audienceValidator));
    }

    private static void applyJwsAlgorithms(List<String> configured, Set<SignatureAlgorithm> target) {
        if (configured == null || configured.isEmpty()) {
            target.add(SignatureAlgorithm.RS256);
            return;
        }
        configured.forEach(algorithm -> target.add(SignatureAlgorithm.from(algorithm)));
    }

    private static SignatureAlgorithm firstSignatureAlgorithm(List<String> configured) {
        if (configured == null || configured.isEmpty()) {
            return SignatureAlgorithm.RS256;
        }
        return SignatureAlgorithm.from(configured.get(0));
    }

    private static RSAPublicKey readPublicKey(Resource location) {
        try {
            byte[] bytes;
            try (var inputStream = location.getInputStream()) {
                bytes = inputStream.readAllBytes();
            }
            // Strip any PEM armour (-----BEGIN ...----- / -----END ...-----), like Spring Boot does,
            // so a wrong-but-still-PEM key fails on the DER spec rather than on stray Base64 chars.
            String pem = new String(bytes, java.nio.charset.StandardCharsets.UTF_8)
                    .replaceAll("-----BEGIN [^-]+-----", "")
                    .replaceAll("-----END [^-]+-----", "")
                    .replaceAll("\\s", "");
            byte[] decoded = Base64.getDecoder().decode(pem);
            return (RSAPublicKey) KeyFactory.getInstance("RSA")
                    .generatePublic(new X509EncodedKeySpec(decoded));
        } catch (IOException ex) {
            throw new IllegalStateException(
                    "Cannot read public key from " + location + " for the compatibility JwtDecoder", ex);
        } catch (Exception ex) {
            throw new IllegalStateException(
                    "Invalid RSA public key at " + location + " for the compatibility JwtDecoder", ex);
        }
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
