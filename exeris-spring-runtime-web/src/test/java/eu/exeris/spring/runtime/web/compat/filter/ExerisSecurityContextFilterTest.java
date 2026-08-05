/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.web.compat.filter;

import eu.exeris.kernel.spi.http.HttpHeader;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpRequest;
import eu.exeris.kernel.spi.http.HttpVersion;
import eu.exeris.spring.runtime.web.compat.security.InvalidBearerTokenException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.InstanceOfAssertFactories.STRING;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ExerisSecurityContextFilter}.
 */
class ExerisSecurityContextFilterTest {

    private final JwtDecoder jwtDecoder = mock(JwtDecoder.class);
    private final ExerisSecurityContextFilter filter = new ExerisSecurityContextFilter(jwtDecoder);

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    // =========================================================================
    // populateContext — no Authorization header
    // =========================================================================

    @Test
    void populateContext_noAuthorizationHeader_leavesContextEmpty() {
        HttpRequest request = stubRequest(Map.of());
        filter.populateContext(request);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verifyNoInteractions(jwtDecoder);
    }

    @Test
    void populateContext_nonBearerHeader_leavesContextEmpty() {
        HttpRequest request = stubRequest(Map.of("Authorization", List.of("Basic dXNlcjpwYXNz")));
        filter.populateContext(request);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verifyNoInteractions(jwtDecoder);
    }

    @Test
    void populateContext_emptyBearerToken_leavesContextEmpty() {
        HttpRequest request = stubRequest(Map.of("Authorization", List.of("Bearer ")));
        filter.populateContext(request);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verifyNoInteractions(jwtDecoder);
    }

    // =========================================================================
    // populateContext — valid token
    // =========================================================================

    @Test
    void populateContext_validBearerToken_setsAuthentication() {
        Jwt jwt = buildStubJwt();
        when(jwtDecoder.decode("valid-token")).thenReturn(jwt);

        HttpRequest request = stubRequest(Map.of("Authorization", List.of("Bearer valid-token")));
        filter.populateContext(request);

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(auth.isAuthenticated()).isTrue();
    }

    // =========================================================================
    // populateContext — invalid token
    // =========================================================================

    @Test
    void populateContext_invalidToken_isRejected() {
        // Behaviour change in 0.7.0. This previously asserted that an invalid token left the
        // context empty and the request continued as anonymous — a presented credential that
        // failed validation was treated identically to no credential at all, silently. That is
        // fail-open: the caller was not refused, and nothing recorded that a token had been
        // rejected. A presented-and-invalid credential must fail the request.
        when(jwtDecoder.decode("bad-token")).thenThrow(new JwtException("expired"));

        HttpRequest request = stubRequest(Map.of("Authorization", List.of("Bearer bad-token")));

        assertThatThrownBy(() -> filter.populateContext(request))
                .isInstanceOf(InvalidBearerTokenException.class)
                .hasCauseInstanceOf(JwtException.class);

        assertThat(SecurityContextHolder.getContext().getAuthentication())
                .as("a rejected token must never leave a partially populated context")
                .isNull();
    }

    @Test
    void populateContext_invalidToken_messageDoesNotLeakTokenOrValidatorText() {
        // The message reaches logs. A validator message can echo claim values, and the token
        // itself is a credential — neither belongs in a log line.
        when(jwtDecoder.decode("secret-token-value"))
                .thenThrow(new JwtException("aud claim was [internal-audience-name]"));

        HttpRequest request = stubRequest(Map.of("Authorization", List.of("Bearer secret-token-value")));

        assertThatThrownBy(() -> filter.populateContext(request))
                .isInstanceOf(InvalidBearerTokenException.class)
                .extracting(Throwable::getMessage, as(STRING))
                .doesNotContain("secret-token-value")
                .doesNotContain("internal-audience-name");
    }

    @Test
    void populateContext_invalidToken_continuesAnonymously_whenRejectionDisabled() {
        // The documented escape hatch for a migration that provably depends on the old behaviour.
        // exeris.runtime.web.compat.security.reject-invalid-token=false
        when(jwtDecoder.decode("bad-token")).thenThrow(new JwtException("expired"));

        ExerisSecurityContextFilter permissive = new ExerisSecurityContextFilter(
                jwtDecoder,
                new org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter(),
                false);

        HttpRequest request = stubRequest(Map.of("Authorization", List.of("Bearer bad-token")));

        assertThatCode(() -> permissive.populateContext(request)).doesNotThrowAnyException();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void populateContext_absentToken_isNotARejection() {
        // Only a *presented* credential can be rejected. Public endpoints must keep working, so
        // "no Authorization header" stays anonymous rather than becoming a 401 — otherwise the
        // fail-open fix would break every unauthenticated route.
        HttpRequest request = stubRequest(Map.of());

        assertThatCode(() -> filter.populateContext(request)).doesNotThrowAnyException();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    // =========================================================================
    // populateContext — custom converter is honoured
    // =========================================================================

    @Test
    void populateContext_customConverter_isUsedForAuthorityMapping() {
        Jwt jwt = buildStubJwt();
        when(jwtDecoder.decode("valid-token")).thenReturn(jwt);

        // An application-supplied converter (e.g. mapping realm_access.roles → ROLE_*).
        org.springframework.core.convert.converter.Converter<
                Jwt, ? extends org.springframework.security.authentication.AbstractAuthenticationToken> customConverter =
                decoded -> new org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken(
                        decoded,
                        List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_CUSTOM")));

        ExerisSecurityContextFilter customFilter = new ExerisSecurityContextFilter(jwtDecoder, customConverter);

        HttpRequest request = stubRequest(Map.of("Authorization", List.of("Bearer valid-token")));
        customFilter.populateContext(request);

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(auth.getAuthorities())
                .extracting(Object::toString)
                .containsExactly("ROLE_CUSTOM");
    }

    // =========================================================================
    // clearContext
    // =========================================================================

    @Test
    void clearContext_removesAuthentication() {
        Jwt jwt = buildStubJwt();
        when(jwtDecoder.decode("tok")).thenReturn(jwt);

        HttpRequest request = stubRequest(Map.of("Authorization", List.of("Bearer tok")));
        filter.populateContext(request);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();

        filter.clearContext();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static HttpRequest stubRequest(Map<String, List<String>> headers) {
        List<HttpHeader> httpHeaders = headers.entrySet().stream()
                .flatMap(e -> e.getValue().stream().map(v -> new HttpHeader(e.getKey(), v)))
                .collect(Collectors.toList());
        return HttpRequest.noBody(HttpMethod.GET, "/test", HttpVersion.HTTP_1_1, httpHeaders);
    }

    private static Jwt buildStubJwt() {
        return Jwt.withTokenValue("stub-token")
                .header("alg", "RS256")
                .subject("user-1")
                .issuedAt(Instant.now().minusSeconds(60))
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
    }
}
