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
 *   <tr><td>{@link AccessDeniedException}</td><td>403 Forbidden</td><td>&mdash;</td></tr>
 * </table>
 * <p><b>Mode:</b> Mode-neutral &mdash; handles method-security exceptions across both Pure Mode and Compatibility Mode.
 *
 * @implSpec Maps authentication failures to 401 Unauthorized with {@code WWW-Authenticate: Bearer} challenge,
 *     and access denied failures to 403 Forbidden. The cause chain is walked up to 5 levels to uncover
 *     wrapped security exceptions.
 * @implNote In contrast to servlet deployments where anonymous access-denied is upgraded to 401, this resolver
 *     leaves anonymous access-denied as 403 to avoid reading {@code SecurityContextHolder}
 *     ({@code ThreadLocal}) on the mode-neutral path.
 * @since 0.7
 */
public final class SpringSecurityErrorStatusResolver implements ExerisErrorStatusResolver {

    /** RFC 9110 §11.6.1 makes a challenge mandatory on 401; bearer is the only scheme supported. */
    private static final List<HttpHeader> BEARER_CHALLENGE =
            List.of(new HttpHeader("WWW-Authenticate", "Bearer"));

    private static final int MAX_CAUSE_DEPTH = 5;

    /**
     * Default constructor for Spring Security error status resolver.
     */
    public SpringSecurityErrorStatusResolver() {
    }

    /**
     * {@inheritDoc}
     *
     * @param exception the throwable to inspect; may be {@code null}
     * @return the resolved status and headers, or empty if unhandled
     */
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
