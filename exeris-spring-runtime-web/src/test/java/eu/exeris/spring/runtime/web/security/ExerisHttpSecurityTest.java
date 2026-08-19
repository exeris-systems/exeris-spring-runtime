/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.web.security;

import org.junit.jupiter.api.Test;

import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpRoutePolicy;
import eu.exeris.kernel.spi.http.RouteRequirement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExerisHttpSecurityTest {

    private static ExerisHttpSecurity permittingProbes() {
        return ExerisHttpSecurity.create()
                .requestMatchers("/health", "/health/live", "/health/ready", "/db/ping", "/db/roundtrip")
                .permitAll();
    }

    @Test
    void compilesEachShapeOntoTheKernelRequirement() {
        HttpRoutePolicy policy = ExerisRoutePolicyCompiler.compile(permittingProbes()
                .requestMatchers("/open").permitAll()
                .requestMatchers("/who").authenticated()
                .requestMatchers("/any").hasAnyScope("a", "b")
                .requestMatchers("/all").hasAllScopes("a", "b")
                .anyRequest().authenticated());

        assertThat(policy.requirementFor(HttpMethod.GET, "/open").kind())
                .isEqualTo(RouteRequirement.Kind.PERMIT_ALL);
        assertThat(policy.requirementFor(HttpMethod.GET, "/who").kind())
                .isEqualTo(RouteRequirement.Kind.AUTHENTICATED);
        assertThat(policy.requirementFor(HttpMethod.GET, "/any").kind())
                .isEqualTo(RouteRequirement.Kind.ANY_SCOPE);
        assertThat(policy.requirementFor(HttpMethod.GET, "/all").kind())
                .isEqualTo(RouteRequirement.Kind.ALL_SCOPES);
    }

    @Test
    void unmatchedPathGetsTheDeclaredAnswer() {
        HttpRoutePolicy policy = ExerisRoutePolicyCompiler.compile(
                permittingProbes().anyRequest().authenticated());

        assertThat(policy.requirementFor(HttpMethod.GET, "/nothing/declared/here").kind())
                .isEqualTo(RouteRequirement.Kind.AUTHENTICATED);
    }

    @Test
    void methodScopedRuleDoesNotApplyToOtherMethods() {
        HttpRoutePolicy policy = ExerisRoutePolicyCompiler.compile(permittingProbes()
                .requestMatchers(HttpMethod.GET, "/api/orders").permitAll()
                .anyRequest().authenticated());

        assertThat(policy.requirementFor(HttpMethod.GET, "/api/orders").kind())
                .isEqualTo(RouteRequirement.Kind.PERMIT_ALL);
        assertThat(policy.requirementFor(HttpMethod.POST, "/api/orders").kind())
                .isEqualTo(RouteRequirement.Kind.AUTHENTICATED);
    }

    /**
     * Overlap resolves by declaration order, not by "specificity" — the documented decision, and the
     * one a migrating {@code SecurityFilterChain} already relies on. Asserted in both directions so a
     * future change to sort by specificity fails here rather than silently changing what rules mean.
     */
    @Test
    void overlappingPatternsResolveInDeclarationOrder() {
        HttpRoutePolicy broadFirst = ExerisRoutePolicyCompiler.compile(permittingProbes()
                .requestMatchers("/api/**").hasAnyScope("broad")
                .requestMatchers("/api/orders").hasAnyScope("narrow")
                .anyRequest().authenticated());

        HttpRoutePolicy narrowFirst = ExerisRoutePolicyCompiler.compile(permittingProbes()
                .requestMatchers("/api/orders").hasAnyScope("narrow")
                .requestMatchers("/api/**").hasAnyScope("broad")
                .anyRequest().authenticated());

        assertThat(scopesOf(broadFirst.requirementFor(HttpMethod.GET, "/api/orders")))
                .containsExactly("broad");
        assertThat(scopesOf(narrowFirst.requirementFor(HttpMethod.GET, "/api/orders")))
                .containsExactly("narrow");
    }

    @Test
    void queryStringDoesNotChangeWhichRuleApplies() {
        HttpRoutePolicy policy = ExerisRoutePolicyCompiler.compile(permittingProbes()
                .requestMatchers("/api/orders").hasAnyScope("orders:read")
                .anyRequest().permitAll());

        // If the query string were part of the match this would fall through to permitAll — which is
        // the bypass shape, not merely a wrong answer.
        assertThat(policy.requirementFor(HttpMethod.GET, "/api/orders?page=2").kind())
                .isEqualTo(RouteRequirement.Kind.ANY_SCOPE);
    }

    @Test
    void missingAnyRequestClauseFailsToCompile() {
        assertThatThrownBy(() -> ExerisRoutePolicyCompiler.compile(
                ExerisHttpSecurity.create().requestMatchers("/x").permitAll()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("declares no answer for unmatched requests")
                .hasMessageContaining("There is no default");
    }

    @Test
    void anyRequestDeclaredTwiceIsRejected() {
        assertThatThrownBy(() -> permittingProbes().anyRequest().authenticated().anyRequest().permitAll())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already declared");
    }

    /**
     * The trap ADR-063 exists to close: {@code anyRequest().authenticated()} is what a Spring
     * application already writes, and against a bound policy it denies the kernel's own probe
     * endpoints — producing a rollout that never becomes ready against a perfectly healthy process.
     */
    @Test
    void failClosedWithoutProbeRoutesFailsStartupAndNamesThem() {
        assertThatThrownBy(() -> ExerisRoutePolicyCompiler.compile(
                ExerisHttpSecurity.create()
                        .requestMatchers("/api/**").hasAnyScope("api")
                        .anyRequest().authenticated()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("/health")
                .hasMessageContaining("/health/live")
                .hasMessageContaining("/health/ready")
                .hasMessageContaining("/db/ping")
                .hasMessageContaining("/db/roundtrip")
                .hasMessageContaining("never become ready");
    }

    @Test
    void failClosedWithProbeRoutesDeclaredCompiles() {
        HttpRoutePolicy policy = ExerisRoutePolicyCompiler.compile(
                permittingProbes().anyRequest().authenticated());

        assertThat(policy.requirementFor(HttpMethod.GET, "/health").kind())
                .isEqualTo(RouteRequirement.Kind.PERMIT_ALL);
    }

    /**
     * A wildcard covering the probes satisfies the check — the guard asks whether the probes are
     * permitted, not whether they were spelled out.
     */
    @Test
    void wildcardCoveringProbesSatisfiesTheCheck() {
        HttpRoutePolicy policy = ExerisRoutePolicyCompiler.compile(ExerisHttpSecurity.create()
                .requestMatchers("/health/**", "/db/**").permitAll()
                .requestMatchers("/health").permitAll()
                .anyRequest().authenticated());

        assertThat(policy.requirementFor(HttpMethod.GET, "/db/ping").kind())
                .isEqualTo(RouteRequirement.Kind.PERMIT_ALL);
    }

    /**
     * A permissive unmatched answer covers the probes by definition, so the check does not fire —
     * the application has said, explicitly, that undeclared paths are open.
     */
    @Test
    void permitAllUnmatchedNeedsNoProbeDeclaration() {
        HttpRoutePolicy policy = ExerisRoutePolicyCompiler.compile(ExerisHttpSecurity.create()
                .requestMatchers("/api/**").hasAnyScope("api")
                .anyRequest().permitAll());

        assertThat(policy.requirementFor(HttpMethod.GET, "/health").kind())
                .isEqualTo(RouteRequirement.Kind.PERMIT_ALL);
    }

    @Test
    void declarationInputIsValidated() {
        assertThatThrownBy(() -> ExerisHttpSecurity.create().requestMatchers().permitAll())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one pattern");

        assertThatThrownBy(() -> ExerisHttpSecurity.create().requestMatchers("/x").hasAnyScope(" "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be null or blank");

        // A repeated scope is a typo, not a startup failure — Set.of would have thrown here.
        assertThat(scopesOf(RouteRequirement.requiringAnyScope(java.util.Set.of("a"))))
                .containsExactly("a");
        HttpRoutePolicy policy = ExerisRoutePolicyCompiler.compile(permittingProbes()
                .requestMatchers("/dup").hasAnyScope("a", "a")
                .anyRequest().authenticated());
        assertThat(scopesOf(policy.requirementFor(HttpMethod.GET, "/dup"))).containsExactly("a");
    }

    private static String[] scopesOf(RouteRequirement requirement) {
        String[] scopes = new String[requirement.scopeCount()];
        for (int i = 0; i < scopes.length; i++) {
            scopes[i] = requirement.scopeAt(i);
        }
        return scopes;
    }
}
