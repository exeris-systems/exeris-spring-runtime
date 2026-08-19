/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.web.security;

import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpRoutePolicy;
import eu.exeris.kernel.spi.http.RouteRequirement;

/**
 * The immutable result of compiling an {@link ExerisHttpSecurity} declaration (ADR-063).
 *
 * <p>This is the whole of what this runtime contributes to an authorization decision: a lookup from
 * (method, path) to a pre-built {@link RouteRequirement}. The decision itself — 401 for no identity,
 * 403 for an identity without the required scope — is taken by the kernel's
 * {@code RouteAuthorizationEnforcer} on the admission path, before either dispatcher is invoked.
 * There is deliberately no second enforcement layer on the Spring side (ADR-063 obligation 1).
 *
 * <h2>Order is declaration order, and that is a decision</h2>
 *
 * <p>Rules are evaluated in the order the application declared them and the first match wins —
 * exactly how a Spring {@code SecurityFilterChain} behaves. The alternative, sorting by
 * "specificity", reads as helpful until two patterns overlap in a way the sort ranks differently
 * from how the author read them, at which point the effective rule is one nobody wrote. A migrating
 * application is transcribing rules whose order already carried meaning; preserving that order is
 * the only translation that cannot silently change what they meant.
 *
 * <h2>Allocation</h2>
 *
 * <p>{@code requirementFor} allocates nothing (ADR-063 obligation 2, ADR-061's contract): the rules
 * are a flat array, the requirements are built at startup, and matching walks the path by index.
 *
 * @since 0.8.0
 */
final class CompiledHttpRoutePolicy implements HttpRoutePolicy {

    private final Rule[] rules;
    private final RouteRequirement unmatched;

    CompiledHttpRoutePolicy(Rule[] rules, RouteRequirement unmatched) {
        this.rules = rules;
        this.unmatched = unmatched;
    }

    @Override
    public RouteRequirement requirementFor(HttpMethod method, String path) {
        for (Rule rule : rules) {
            if (rule.appliesTo(method, path)) {
                return rule.requirement();
            }
        }
        return unmatched;
    }

    /** The answer for a path no rule describes. Exposed for startup validation, not for the hot path. */
    RouteRequirement unmatched() {
        return unmatched;
    }

    /** The compiled rules, in declaration order. Startup diagnostics only. */
    Rule[] rules() {
        return rules.clone();
    }

    /**
     * One compiled rule.
     *
     * @param method      the method this rule is restricted to, or {@code null} for any method
     * @param pattern     the compiled path pattern
     * @param requirement the pre-built requirement handed back on a match
     */
    record Rule(HttpMethod method, RoutePathPattern pattern, RouteRequirement requirement) {

        boolean appliesTo(HttpMethod candidate, String path) {
            return (method == null || method == candidate) && pattern.matches(path);
        }

        @Override
        public String toString() {
            return (method == null ? "*" : method.name()) + " " + pattern;
        }
    }
}
