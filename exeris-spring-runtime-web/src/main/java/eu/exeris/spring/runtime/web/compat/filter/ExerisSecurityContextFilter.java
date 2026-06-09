/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.web.compat.filter;

import eu.exeris.kernel.spi.http.HttpRequest;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;

import java.util.Objects;

/**
 * Compatibility-mode security context populator for Exeris-hosted requests.
 *
 * <h2>Purpose</h2>
 * <p>Extracts a Bearer token from the {@code Authorization} header of the kernel
 * {@link HttpRequest}, decodes it with {@link JwtDecoder}, converts it to a Spring
 * Security {@link Authentication}, and stores it in {@link SecurityContextHolder}
 * (VT-scoped {@code MODE_THREADLOCAL}).
 *
 * <h2>Activation</h2>
 * <p>Active only in Compatibility Mode when {@code spring-security-oauth2-resource-server}
 * is on the classpath. Conditional on absence of a {@code SecurityFilterChain} bean
 * (if a full Spring Security configuration is provided, this filter must not activate).
 *
 * <p>Token-to-{@link Authentication} conversion honours an application-supplied
 * {@code Converter<Jwt, ? extends AbstractAuthenticationToken>} (or
 * {@link JwtAuthenticationConverter}) bean when one is registered, falling back to a default
 * {@link JwtAuthenticationConverter} otherwise — so custom claim-to-authority mapping survives
 * a brownfield migration onto the Exeris-hosted compat path.
 *
 * <h2>Lifetime Contract</h2>
 * <p>Called exactly once per request: {@link #populateContext(HttpRequest)} before dispatch,
 * {@link #clearContext()} in {@code finally} after dispatch. Must not be called from
 * handler or resolver code — only from {@code *.compat.filter.*} or dispatcher scope.
 *
 * <h2>Invalid Token Behaviour</h2>
 * <p>If the token is absent or invalid, the security context is left empty (anonymous).
 * Authorization enforcement is delegated to method-level security annotations
 * ({@code @PreAuthorize}, {@code @Secured}) or {@code ExerisHandlerMethodRegistry} guards.
 *
 * <h2>ThreadLocal Rule</h2>
 * <p>{@code SecurityContextHolder.MODE_THREADLOCAL} (default) is VT-scoped: non-inherited,
 * cleared deterministically in {@code finally}. Permitted in Compatibility Mode per
 * the ThreadLocal Rule clarification (ADR-007, exeris-kernel).
 *
 * <h2>Mode</h2>
 * <p>Compatibility Mode only. Not active in pure-mode request paths.
 *
 * @since 0.1.0
 */
public final class ExerisSecurityContextFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtDecoder jwtDecoder;
    private final Converter<Jwt, ? extends AbstractAuthenticationToken> jwtAuthenticationConverter;

    /**
     * Creates a filter with the default {@link JwtAuthenticationConverter} (scope-based
     * authorities only). Retained for backward compatibility; prefer the converter-aware
     * constructor so an application's custom claim-to-authority mapping is honoured.
     */
    public ExerisSecurityContextFilter(JwtDecoder jwtDecoder) {
        this(jwtDecoder, new JwtAuthenticationConverter());
    }

    /**
     * Creates a filter that converts decoded tokens with the supplied converter — typically
     * an application-registered {@code Converter<Jwt, ? extends AbstractAuthenticationToken>}
     * or {@link JwtAuthenticationConverter} bean (e.g. mapping {@code realm_access.roles} or a
     * custom authority prefix). Mirrors how Spring Security's resource server honours a
     * user-supplied JWT authentication converter.
     */
    public ExerisSecurityContextFilter(
            JwtDecoder jwtDecoder,
            Converter<Jwt, ? extends AbstractAuthenticationToken> jwtAuthenticationConverter) {
        this.jwtDecoder = Objects.requireNonNull(jwtDecoder, "jwtDecoder must not be null");
        this.jwtAuthenticationConverter =
                Objects.requireNonNull(jwtAuthenticationConverter, "jwtAuthenticationConverter must not be null");
    }

    /**
     * Populates the {@link SecurityContextHolder} from the request's Bearer token.
     * If no valid token is present, the context remains empty (anonymous request).
     *
     * @param request the kernel HTTP request (never null)
     */
    public void populateContext(HttpRequest request) {
        // Clear any pre-existing context before processing this request.
        // Essential for VT reuse: prevents inherited authentication from prior requests.
        SecurityContextHolder.clearContext();
        
        String token = extractBearerToken(request);
        if (token == null) {
            return;
        }
        try {
            Jwt jwt = jwtDecoder.decode(token);
            Authentication authentication = jwtAuthenticationConverter.convert(jwt);
            SecurityContextHolder.getContext().setAuthentication(authentication);
        } catch (JwtException | OAuth2AuthenticationException ignored) {
            // Invalid token — leave context empty; authorization enforcement
            // is delegated to method-level security.
        }
    }

    /**
     * Clears the {@link SecurityContextHolder}. Must be called in {@code finally}
     * after every dispatch, even when {@link #populateContext} set no authentication.
     */
    public void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private static String extractBearerToken(HttpRequest request) {
        return request.firstHeader("Authorization")
                .filter(v -> v.startsWith(BEARER_PREFIX))
                .map(v -> v.substring(BEARER_PREFIX.length()).strip())
                .filter(t -> !t.isEmpty())
                .orElse(null);
    }
}
