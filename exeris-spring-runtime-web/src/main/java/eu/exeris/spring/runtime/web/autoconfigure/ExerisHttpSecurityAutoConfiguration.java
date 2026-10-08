/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.web.autoconfigure;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

import eu.exeris.kernel.spi.http.HttpRoutePolicy;
import eu.exeris.spring.runtime.web.security.ExerisHttpSecurity;
import eu.exeris.spring.runtime.web.security.ExerisRoutePolicyCompiler;

/**
 * Turns an application's {@link ExerisHttpSecurity} declaration into the kernel
 * {@code HttpRoutePolicy} bean that {@code ExerisRuntimeLifecycle} binds into
 * {@code HttpKernelProviders.HTTP_ROUTE_POLICY} (ADR-063).
 *
 * <p><b>Absent Policy Invariant:</b> Per ADR-063 obligation 6, with no {@code ExerisHttpSecurity} bean
 * this contributes nothing, leaving the kernel route policy slot unbound.
 *
 * @implNote The binding occurs in {@code exeris-spring-boot-autoconfigure}, and
 *     {@code autoconfigure &rarr; web} is a banned dependency edge per architecture rules.
 *     This configuration publishes the kernel SPI type {@code HttpRoutePolicy}, allowing
 *     {@code ExerisRuntimeLifecycle} to consume an {@code Optional<HttpRoutePolicy>} without
 *     depending on this module.
 * @since 0.8
 * @see "ADR-063: Exeris HTTP Security Route Policy Binding"
 */
@AutoConfiguration
@ConditionalOnBean(ExerisHttpSecurity.class)
public class ExerisHttpSecurityAutoConfiguration {

    /**
     * Default constructor for auto-configuration.
     */
    public ExerisHttpSecurityAutoConfiguration() {
    }

    /**
     * Compiles the application security declaration into a kernel route policy.
     *
     * @param security the application's declaration
     * @return the compiled policy, published as the kernel SPI type
     */
    @Bean
    @ConditionalOnMissingBean(HttpRoutePolicy.class)
    public HttpRoutePolicy exerisHttpRoutePolicy(ExerisHttpSecurity security) {
        return ExerisRoutePolicyCompiler.compile(security);
    }
}
