/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.web.security;

import java.util.Objects;

import eu.exeris.kernel.spi.http.HttpRoutePolicy;

/**
 * The compilation seam named by ADR-063 obligation 1: the single place an {@link ExerisHttpSecurity}
 * declaration becomes a kernel {@code HttpRoutePolicy}.
 *
 * <p>Nothing else in this runtime participates in an authorization decision. In particular no
 * production class here references the kernel's {@code RouteAuthorizationEnforcer} — a guard asserts
 * that, because the failure it prevents is the expensive kind: a second decision layer on the Spring
 * side that can disagree with the kernel's, leaving a route whose effective policy depends on which
 * layer ran. ADR-061 put the enforcer in kernel Core precisely so a Spring-side DSL would translate
 * onto it instead of growing a rival mechanism.
 *
 * @since 0.8.0
 */
public final class ExerisRoutePolicyCompiler {

    private ExerisRoutePolicyCompiler() {
        // Static seam — never instantiated.
    }

    /**
     * Compiles a declaration into the policy bound to the kernel slot.
     *
     * <p>Called once, at startup. The result is immutable and its {@code requirementFor} allocates
     * nothing (ADR-063 obligation 2).
     *
     * @param security the application's declaration
     * @return the compiled policy
     * @throws IllegalStateException if the declaration is incomplete, or would deny the kernel's own
     *                               probe endpoints — see {@link ExerisHttpSecurity#build()}
     */
    public static HttpRoutePolicy compile(ExerisHttpSecurity security) {
        Objects.requireNonNull(security, "security must not be null");
        return security.build();
    }
}
