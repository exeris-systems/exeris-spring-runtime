/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.web.security;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpRoutePolicy;
import eu.exeris.kernel.spi.http.RouteRequirement;

/**
 * Spring-shaped per-path authorization rules, compiled onto the kernel's route-policy contract.
 *
 * <p>Declare one of these as a bean and the runtime compiles it, once at startup, into a
 * {@code HttpRoutePolicy} bound into the kernel's {@code HTTP_ROUTE_POLICY} slot:
 *
 * {@snippet lang="java" :
 * @Bean
 * ExerisHttpSecurity httpSecurity() {
 *     return ExerisHttpSecurity.create()
 *             .requestMatchers("/health", "/health/live", "/health/ready").permitAll()
 *             .requestMatchers(HttpMethod.GET, "/api/orders/**").hasAnyScope("orders:read")
 *             .requestMatchers(HttpMethod.POST, "/api/orders/**").hasAllScopes("orders:write", "orders:read")
 *             .anyRequest().authenticated();
 * }
 * }
 *
 * <p><strong>This is a compiler, not an enforcement mechanism.</strong> Every decision is taken by
 * the kernel on the admission path, before {@code ExerisHttpDispatcher} or
 * {@code ExerisCompatDispatcher} is reached — so a refused request never enters Spring at all: no
 * handler, no argument resolver, no {@code @Transactional} advice runs for a caller who will be
 * refused. Declaring no bean leaves the slot unbound and changes nothing.
 *
 * <p><b>Mode:</b> Mode-neutral. This governs ingress for Pure Mode and Compatibility Mode alike and is
 * deliberately not a {@code *.compat.*} type (ADR-063 obligation 5).</p>
 *
 * @apiNote There is no {@code hasRole} method: the kernel's {@code RouteRequirement} checks scopes
 *          alone via {@code PrincipalContext.hasScope}. Simulating roles as {@code ROLE_} scopes is
 *          rejected to avoid divergence from Spring's authority model. Role-based checks should use
 *          method-level {@code @PreAuthorize} instead, which evaluates Spring's {@code Authentication}.
 * @since 0.8
 * @see "ADR-063: Exeris HTTP Security Route Policy Binding"
 */
public final class ExerisHttpSecurity {

    /**
     * Endpoints the kernel's Community HTTP driver serves itself, which no application handler backs.
     *
     * <p>The kernel does not exempt them from a bound policy — ADR-061 is explicit that they are
     * routes like any other, because a driver-local notion of "public" would be a second answer to
     * the question the policy contract now owns. So a fail-closed unmatched answer denies them, and
     * the symptom is a rollout whose pods never become ready while the process is perfectly healthy.
     *
     * <p>Spring's idiomatic closing line is {@code anyRequest().authenticated()}, which maps exactly
     * onto fail-closed. An application that writes the thing it has always written therefore breaks
     * its own orchestrator probes. {@link #build()} refuses that at startup rather than at 3am.
     */
    private static final List<String> PROBE_ROUTES =
            List.of("/health", "/health/live", "/health/ready", "/db/ping", "/db/roundtrip");

    private final List<CompiledHttpRoutePolicy.Rule> rules = new ArrayList<>();
    private RouteRequirement unmatched;

    private ExerisHttpSecurity() {
    }

    /**
     * Starts a declaration.
     *
     * @return a new security configuration builder
     */
    public static ExerisHttpSecurity create() {
        return new ExerisHttpSecurity();
    }

    /**
     * Begins a rule matching the given path patterns on any method.
     *
     * @param patterns one or more patterns — see {@link RoutePathPattern} for the syntax
     * @return an authorization builder for the matched route
     */
    public Authorization requestMatchers(String... patterns) {
        return new Authorization(null, patterns);
    }

    /**
     * Begins a rule matching the given path patterns on one method only.
     *
     * @param method   the method this rule applies to
     * @param patterns one or more patterns
     * @return an authorization builder for the matched route
     */
    public Authorization requestMatchers(HttpMethod method, String... patterns) {
        Objects.requireNonNull(method, "method must not be null");
        return new Authorization(method, patterns);
    }

    /**
     * Declares the answer for any path no preceding rule matched.
     *
     * <p>Required. There is no default, deliberately — see {@link #build()}.
     *
     * @return an authorization builder for unmatched routes
     */
    public Authorization anyRequest() {
        return new Authorization();
    }

    /**
     * Compiles the declaration, validating it.
     *
     * <p>Two failures are raised here rather than left to be discovered in production:
     *
     * <ol>
     *   <li><strong>No unmatched answer.</strong> Every other rule describes paths the author thought
     *       about; the unmatched answer covers the ones they did not, which is where an authorization
     *       mistake actually lands. Defaulting it either way is a decision made on the author's behalf
     *       that they never see, so the DSL refuses to guess.</li>
     *   <li><strong>Fail-closed unmatched with undeclared probe routes.</strong> See
     *       {@link #PROBE_ROUTES}. The message names the routes that would be denied.</li>
     * </ol>
     *
     * @return the compiled policy
     * @throws IllegalStateException if the declaration is incomplete or would deny orchestrator probes
     */
    HttpRoutePolicy build() {
        if (unmatched == null) {
            throw new IllegalStateException(
                    "ExerisHttpSecurity declares no answer for unmatched requests. Add a terminal "
                            + "anyRequest() clause — anyRequest().authenticated() is the fail-closed "
                            + "equivalent of Spring's usual closing line, anyRequest().permitAll() "
                            + "leaves undeclared paths open. There is no default.");
        }

        CompiledHttpRoutePolicy policy = new CompiledHttpRoutePolicy(
                rules.toArray(new CompiledHttpRoutePolicy.Rule[0]), unmatched);

        if (unmatched.kind() != RouteRequirement.Kind.PERMIT_ALL) {
            Set<String> denied = probeRoutesNotPermitted(policy);
            if (!denied.isEmpty()) {
                throw new IllegalStateException(
                        "ExerisHttpSecurity closes fail-closed (anyRequest() is not permitAll), which "
                                + "denies the kernel's own probe endpoints: " + String.join(", ", denied)
                                + ". The kernel serves these itself and does not exempt them from a bound "
                                + "policy, so an orchestrator's liveness/readiness checks would be refused "
                                + "against a healthy process and the deployment would never become ready. "
                                + "Declare them explicitly, e.g. "
                                + "requestMatchers(\"/health\", \"/health/live\", \"/health/ready\").permitAll()");
            }
        }
        return policy;
    }

    private static Set<String> probeRoutesNotPermitted(CompiledHttpRoutePolicy policy) {
        Set<String> denied = new LinkedHashSet<>();
        for (String probe : PROBE_ROUTES) {
            for (HttpMethod method : HttpMethod.values()) {
                RouteRequirement requirement = policy.requirementFor(method, probe);
                if (requirement.kind() != RouteRequirement.Kind.PERMIT_ALL) {
                    denied.add(probe);
                    break;
                }
            }
        }
        return denied;
    }

    private ExerisHttpSecurity add(HttpMethod method, String[] patterns, RouteRequirement requirement) {
        if (patterns.length == 0) {
            throw new IllegalArgumentException("requestMatchers requires at least one pattern");
        }
        for (String pattern : patterns) {
            rules.add(new CompiledHttpRoutePolicy.Rule(method, RoutePathPattern.compile(pattern), requirement));
        }
        return ExerisHttpSecurity.this;
    }

    private ExerisHttpSecurity terminate(RouteRequirement requirement) {
        if (unmatched != null) {
            throw new IllegalStateException("anyRequest() is already declared; declare it exactly once");
        }
        unmatched = requirement;
        return ExerisHttpSecurity.this;
    }

    /**
     * The requirement half of a rule.
     *
     * <p>Only scope-shaped predicates are offered (ADR-063 obligation 4) — see the class Javadoc for
     * why {@code hasRole} is absent.
     */
    public final class Authorization {

        private final HttpMethod method;
        private final String[] patterns;
        private final boolean terminal;

        private Authorization(HttpMethod method, String... patterns) {
            this.method = method;
            this.patterns = patterns.clone();
            this.terminal = false;
        }

        private Authorization() {
            this.method = null;
            this.patterns = new String[0];
            this.terminal = true;
        }

        /**
         * Requires no identity. The kernel admits without running its security interceptor.
         *
         * @return parent security configuration builder
         */
        public ExerisHttpSecurity permitAll() {
            return apply(RouteRequirement.permitAll());
        }

        /**
         * Demands a verified identity, with no scope demanded.
         *
         * <p>Distinct from {@link #permitAll()} in a way that matters: {@code permitAll} skips the
         * interceptor entirely, so the handler sees no principal even when the caller presented a
         * valid token. A route that wants identity without demanding a scope declares this.
         *
         * @return parent security configuration builder
         */
        public ExerisHttpSecurity authenticated() {
            return apply(RouteRequirement.authenticated());
        }

        /**
         * Demands at least one of the named scopes.
         *
         * @param scopes scope names required
         * @return parent security configuration builder
         */
        public ExerisHttpSecurity hasAnyScope(String... scopes) {
            return apply(RouteRequirement.requiringAnyScope(setOf(scopes)));
        }

        /**
         * Demands all of the named scopes.
         *
         * @param scopes scope names required
         * @return parent security configuration builder
         */
        public ExerisHttpSecurity hasAllScopes(String... scopes) {
            return apply(RouteRequirement.requiringAllScopes(setOf(scopes)));
        }

        private ExerisHttpSecurity apply(RouteRequirement requirement) {
            return terminal ? terminate(requirement) : add(method, patterns, requirement);
        }

        private static Set<String> setOf(String... scopes) {
            Objects.requireNonNull(scopes, "scopes must not be null");
            // LinkedHashSet, then copyOf: Set.of rejects duplicates with IllegalArgumentException, and
            // a repeated scope in a declaration is a typo to tolerate rather than a startup failure.
            Set<String> unique = new LinkedHashSet<>();
            for (String scope : scopes) {
                if (scope == null || scope.isBlank()) {
                    throw new IllegalArgumentException("scope must not be null or blank");
                }
                unique.add(scope);
            }
            return Set.copyOf(unique);
        }
    }
}
