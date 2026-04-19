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

import static org.assertj.core.api.Assertions.assertThat;
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
    void populateContext_invalidToken_leavesContextEmpty() {
        when(jwtDecoder.decode("bad-token")).thenThrow(new JwtException("expired"));

        HttpRequest request = stubRequest(Map.of("Authorization", List.of("Bearer bad-token")));
        filter.populateContext(request);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
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
