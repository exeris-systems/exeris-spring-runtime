/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.web.compat.filter;

import eu.exeris.spring.boot.autoconfigure.compat.CompatibilityMode;

import eu.exeris.kernel.spi.http.HttpRequest;
import eu.exeris.spring.runtime.web.compat.security.BearerTokenRejectedEvent;
import eu.exeris.spring.runtime.web.compat.security.InvalidBearerTokenException;
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
 * <p>An <b>absent</b> token leaves the context empty (anonymous) — public endpoints must keep
 * working, and authorization for the rest is enforced by method-level security
 * ({@code @PreAuthorize}, {@code @Secured}) or {@code ExerisHandlerMethodRegistry} guards.
 *
 * <p>An <b>invalid</b> token is rejected: {@link #populateContext} throws
 * {@link InvalidBearerTokenException}, which {@code ExerisCompatDispatcher} answers with
 * {@code 401} and a {@code WWW-Authenticate: Bearer} challenge. Every rejection also emits a
 * {@link BearerTokenRejectedEvent} JFR event.
 *
 * <p>This was previously silent: the decoder failure was swallowed and the request continued as
 * anonymous, with no log line, no metric and no response difference. A caller presenting an expired
 * or forged token was treated exactly like a caller presenting none, so a token that stopped
 * validating — rotated key, clock skew, wrong issuer — surfaced only as unexplained authorization
 * failures deeper in the application, if at all. Silently downgrading a rejected credential to
 * anonymous is a fail-open shape, and it is the caller's request that has to fail, not the operator's
 * ability to notice.
 *
 * <p>Rejection can be turned off with
 * {@code exeris.runtime.web.compat.security.reject-invalid-token=false}, restoring the previous
 * continue-as-anonymous behaviour for a migration that depends on it. The JFR event is emitted on
 * both paths, so the escape hatch silences the response, never the telemetry.
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
@CompatibilityMode
public final class ExerisSecurityContextFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private static final System.Logger LOGGER =
            System.getLogger(ExerisSecurityContextFilter.class.getName());

    private final JwtDecoder jwtDecoder;
    private final Converter<Jwt, ? extends AbstractAuthenticationToken> jwtAuthenticationConverter;
    private final boolean rejectInvalidToken;

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
     *
     * <p>Rejects invalid tokens. Use
     * {@link #ExerisSecurityContextFilter(JwtDecoder, Converter, boolean)} to opt out.
     */
    public ExerisSecurityContextFilter(
            JwtDecoder jwtDecoder,
            Converter<Jwt, ? extends AbstractAuthenticationToken> jwtAuthenticationConverter) {
        this(jwtDecoder, jwtAuthenticationConverter, true);
    }

    /**
     * Creates a filter with explicit control over invalid-token handling.
     *
     * @param rejectInvalidToken {@code true} (the default) to answer a presented-but-invalid token
     *                           with 401; {@code false} to continue the request anonymously, which
     *                           is the pre-0.7.0 behaviour and is fail-open — see the class Javadoc
     *                           before choosing it
     */
    public ExerisSecurityContextFilter(
            JwtDecoder jwtDecoder,
            Converter<Jwt, ? extends AbstractAuthenticationToken> jwtAuthenticationConverter,
            boolean rejectInvalidToken) {
        this.jwtDecoder = Objects.requireNonNull(jwtDecoder, "jwtDecoder must not be null");
        this.jwtAuthenticationConverter =
                Objects.requireNonNull(jwtAuthenticationConverter, "jwtAuthenticationConverter must not be null");
        this.rejectInvalidToken = rejectInvalidToken;
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
            // No credential presented. Not an error: public endpoints exist, and method-level
            // security refuses the rest. Only a *presented and invalid* credential is a rejection.
            return;
        }
        try {
            Jwt jwt = jwtDecoder.decode(token);
            Authentication authentication = jwtAuthenticationConverter.convert(jwt);
            SecurityContextHolder.getContext().setAuthentication(authentication);
        } catch (JwtException | OAuth2AuthenticationException failure) {
            BearerTokenRejectedEvent.emit(failure, rejectInvalidToken);
            LOGGER.log(System.Logger.Level.DEBUG,
                    () -> "Bearer token rejected for " + request.method() + " " + request.path()
                            + " (" + failure.getClass().getSimpleName() + "); request "
                            + (rejectInvalidToken ? "answered 401" : "continues anonymously"));
            if (rejectInvalidToken) {
                throw new InvalidBearerTokenException(failure);
            }
            // Permissive mode: context stays empty and the request proceeds as anonymous.
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
