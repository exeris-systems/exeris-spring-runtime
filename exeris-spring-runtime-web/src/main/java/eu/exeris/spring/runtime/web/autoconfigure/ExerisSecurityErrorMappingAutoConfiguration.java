/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.web.autoconfigure;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

import eu.exeris.spring.runtime.web.security.SpringSecurityErrorStatusResolver;

/**
 * Registers the Spring Security error-status resolver whenever {@code spring-security-core} is on
 * the classpath.
 *
 * <h2>Why it is not inside either mode's auto-configuration</h2>
 * <p>{@code ExerisWebAutoConfiguration} and {@code ExerisCompatAutoConfiguration} are mutually
 * exclusive — each is gated on {@code exeris.runtime.web.mode}. The mapping this resolver provides
 * is needed in both: {@code @PreAuthorize} is plain Spring AOP on application beans and throws
 * {@code AccessDeniedException} in Pure Mode exactly as it does in Compatibility Mode. Putting the
 * bean in one of them would leave the other reporting an authorization refusal as a 500; putting it
 * in both would duplicate the declaration. It is therefore its own mode-neutral auto-configuration,
 * declared {@code before} both so the resolver definition exists when either mode builds its
 * {@code ExerisErrorMapper}.
 *
 * <h2>Mode</h2>
 * <p>Mode-neutral. Adds no dependency that either mode's classpath guard forbids —
 * {@code spring-security-core} carries no servlet, Netty, or Reactor types.
 *
 * @since 0.7.0
 */
@AutoConfiguration(before = { ExerisWebAutoConfiguration.class, ExerisCompatAutoConfiguration.class })
@ConditionalOnClass(name = "org.springframework.security.core.AuthenticationException")
public class ExerisSecurityErrorMappingAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public SpringSecurityErrorStatusResolver exerisSpringSecurityErrorStatusResolver() {
        return new SpringSecurityErrorStatusResolver();
    }
}
