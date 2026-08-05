/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.web.compat.security;

import eu.exeris.spring.runtime.web.compat.CompatibilityMode;

import org.springframework.beans.factory.BeanDefinitionStoreException;

/**
 * Thrown during context refresh when Compatibility Mode finds a {@code SecurityFilterChain} bean
 * it cannot execute.
 *
 * <p>Extends {@link BeanDefinitionStoreException} so it surfaces as a normal startup failure with
 * the rest of the context diagnostics, rather than as an unrelated runtime exception.
 *
 * @since 0.7.0
 */
@CompatibilityMode
public final class UnenforcedSecurityFilterChainException extends BeanDefinitionStoreException {

    private static final long serialVersionUID = 1L;

    public UnenforcedSecurityFilterChainException(String beanName) {
        super(buildMessage(beanName));
    }

    private static String buildMessage(String beanName) {
        return """
                Exeris Compatibility Mode found a SecurityFilterChain bean ('%s') that will NEVER be executed.

                Why: a SecurityFilterChain is run by Spring Security's FilterChainProxy, which is a servlet \
                filter. Exeris owns HTTP ingress and runs with web-application-type=none, so there is no \
                servlet container, no FilterChainProxy, and nothing to invoke your chain. Every rule you \
                declared in it — authorizeHttpRequests, custom filters, exception handling — is inert.

                Until 0.7.0 this was silent: the chain was ignored, the compatibility fallback filter stood \
                down because a chain was present, and requests were served with no authentication or \
                authorization at all. Startup now fails instead, because a migration that quietly serves \
                unauthenticated traffic is worse than one that does not start.

                What to do, in order of preference:

                  1. Move authentication to the compat resource-server path. Configure a JWT decoder via \
                spring.security.oauth2.resourceserver.jwt.* and let ExerisSecurityContextFilter populate \
                the security context, then enforce authorization with method security (@PreAuthorize, \
                @Secured) on your services. See docs/architecture/compat-spring-security-support.md for \
                what is supported and what has no equivalent.

                  2. Delete the SecurityFilterChain bean if the application no longer needs it.

                  3. Acknowledge it deliberately, if you have another enforcement mechanism and want the \
                bean kept for a non-Exeris deployment profile: set

                       exeris.runtime.web.compat.security.allow-unenforced-filter-chain=true

                     This silences the failure. It does NOT make the chain run — nothing does. Requests \
                will be served exactly as if the bean were absent, so make sure something else authorizes \
                them.
                """.formatted(beanName);
    }
}
