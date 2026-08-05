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

import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link SpringSecurityErrorStatusResolver}.
 *
 * <p>Before this resolver existed both exception families fell through to
 * {@code ExerisErrorMapper}'s 500 fallback, so an authorization outcome was reported as a server
 * fault. These assertions pin the statuses and the mandatory 401 challenge.
 */
class SpringSecurityErrorStatusResolverTest {

    private final SpringSecurityErrorStatusResolver resolver = new SpringSecurityErrorStatusResolver();

    @Test
    void authenticationFailure_maps401WithBearerChallenge() {
        Optional<ExerisErrorStatus> resolved = resolver.resolve(new BadCredentialsException("nope"));

        assertThat(resolved).isPresent();
        assertThat(resolved.get().status()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(resolved.get().headers())
                .as("RFC 9110 §11.6.1 makes a challenge mandatory on 401")
                .extracting(HttpHeader::name, HttpHeader::value)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("WWW-Authenticate", "Bearer"));
    }

    @Test
    void oauth2AuthenticationFailure_isAlsoAnAuthenticationFailure() {
        // OAuth2AuthenticationException extends AuthenticationException; the resource-server path
        // throws this subtype, so a check written only against the base type must still catch it.
        Optional<ExerisErrorStatus> resolved =
                resolver.resolve(new OAuth2AuthenticationException(new OAuth2Error("invalid_token")));

        assertThat(resolved).isPresent();
        assertThat(resolved.get().status()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void accessDenied_maps403WithoutChallenge() {
        Optional<ExerisErrorStatus> resolved = resolver.resolve(new AccessDeniedException("denied"));

        assertThat(resolved).isPresent();
        assertThat(resolved.get().status()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(resolved.get().headers())
                .as("403 carries no challenge — re-authenticating would not help")
                .isEmpty();
    }

    @Test
    void wrappedSecurityFailure_isFoundThroughTheCauseChain() {
        // An interceptor between the security check and the dispatcher must not hide the outcome.
        Exception wrapped = new IllegalStateException("handler failed",
                new RuntimeException("proxy", new AccessDeniedException("denied")));

        assertThat(resolver.resolve(wrapped))
                .map(ExerisErrorStatus::status)
                .contains(HttpStatus.FORBIDDEN);
    }

    @Test
    void causeChainWalkIsBounded() {
        // Deliberate bound: an unbounded walk over an attacker-influenced chain is a DoS shape,
        // and a security exception buried this deep is more likely coincidence than outcome.
        Throwable deep = new AccessDeniedException("denied");
        for (int i = 0; i < 8; i++) {
            deep = new IllegalStateException("layer " + i, deep);
        }

        assertThat(resolver.resolve(deep))
                .as("beyond the depth bound the resolver must defer, not scan forever")
                .isEmpty();
    }

    @Test
    void selfReferencingCauseDoesNotLoop() {
        // Defensive: a cause pointing at itself must terminate the walk rather than spin.
        Exception selfCaused = new IllegalStateException("boom") {
            @Override
            public synchronized Throwable getCause() {
                return this;
            }
        };

        assertThat(resolver.resolve(selfCaused)).isEmpty();
    }

    @Test
    void unrelatedException_defersToTheFallback() {
        assertThat(resolver.resolve(new IllegalArgumentException("bad input"))).isEmpty();
    }
}
