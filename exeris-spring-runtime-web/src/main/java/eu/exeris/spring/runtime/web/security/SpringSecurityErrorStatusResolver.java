/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.web.security;

import eu.exeris.kernel.spi.http.HttpHeader;
import eu.exeris.kernel.spi.http.HttpStatus;
import eu.exeris.spring.runtime.web.ExerisErrorStatus;
import eu.exeris.spring.runtime.web.ExerisErrorStatusResolver;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;

import java.util.List;
import java.util.Optional;

/**
 * Maps Spring Security's authentication and authorization failures onto their HTTP statuses.
 *
 * <table>
 *   <caption>Mapping</caption>
 *   <tr><th>Exception</th><th>Status</th><th>Headers</th></tr>
 *   <tr><td>{@link AuthenticationException}</td><td>401 Unauthorized</td>
 *       <td>{@code WWW-Authenticate: Bearer}</td></tr>
 *   <tr><td>{@link AccessDeniedException}</td><td>403 Forbidden</td><td>—</td></tr>
 * </table>
 *
 * <h2>Why this is not optional polish</h2>
 * <p>Without it both land in {@code ExerisErrorMapper}'s 500 fallback. A caller presenting a bad
 * token, and a caller correctly refused for lack of authority, were both told the server had
 * failed — which is wrong for the client (a 500 invites a retry; a 401/403 does not), wrong for
 * any alerting built on 5xx rates, and hides an authorization outcome behind a fault.
 *
 * <h2>Deliberate deviation: anonymous access-denied stays 403</h2>
 * <p>In a servlet deployment Spring's {@code ExceptionTranslationFilter} inspects the security
 * context on {@code AccessDeniedException} and upgrades it to a 401 when the caller is anonymous,
 * on the reasoning that an unauthenticated caller should be told to authenticate rather than told
 * "no". This resolver does not do that: it would require reading {@code SecurityContextHolder} —
 * a {@code ThreadLocal} — from the mode-neutral path, which the ThreadLocal Rule confines to
 * Compatibility Mode.
 *
 * <p>The practical exposure is narrow. An <em>invalid</em> token is rejected with a 401 by
 * {@code ExerisSecurityContextFilter} before any handler or method-security check runs, so it never
 * reaches this resolver. What remains is a caller with <em>no</em> token hitting a method-secured
 * endpoint: Spring would answer 401, this answers 403. The client is refused either way and no
 * request is let through, but the status differs. Per-path rules resolve it properly by deciding
 * authentication before authorization; until then this is recorded as a known difference in
 * {@code docs/architecture/compat-spring-security-support.md} rather than papered over.
 *
 * <h2>Cause walking</h2>
 * <p>The cause chain is walked to a bounded depth so a security failure wrapped by an
 * interceptor is still recognised. The bound exists because an unbounded walk over an attacker-
 * influenced chain is a denial-of-service shape, and because a security exception buried more than
 * a few frames deep is more likely a coincidence than the actual outcome.
 *
 * <h2>Mode</h2>
 * <p>Mode-neutral. {@code @PreAuthorize} is plain AOP and fires in Pure Mode too, so this must not
 * be confined to the compat path.
 *
 * @since 0.7.0
 */
public final class SpringSecurityErrorStatusResolver implements ExerisErrorStatusResolver {

    /** RFC 9110 §11.6.1 makes a challenge mandatory on 401; bearer is the only scheme supported. */
    private static final List<HttpHeader> BEARER_CHALLENGE =
            List.of(new HttpHeader("WWW-Authenticate", "Bearer"));

    private static final int MAX_CAUSE_DEPTH = 5;

    @Override
    public Optional<ExerisErrorStatus> resolve(Throwable exception) {
        Throwable current = exception;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (current instanceof AuthenticationException) {
                return Optional.of(new ExerisErrorStatus(HttpStatus.UNAUTHORIZED, BEARER_CHALLENGE));
            }
            if (current instanceof AccessDeniedException) {
                return Optional.of(ExerisErrorStatus.of(HttpStatus.FORBIDDEN));
            }
            Throwable cause = current.getCause();
            if (cause == current) {
                break;
            }
            current = cause;
        }
        return Optional.empty();
    }
}
