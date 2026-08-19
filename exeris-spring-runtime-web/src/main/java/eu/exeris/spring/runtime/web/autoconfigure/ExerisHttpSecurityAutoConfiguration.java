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
 * <h2>Absent bean means absent policy</h2>
 *
 * <p>ADR-063 obligation 6. With no {@code ExerisHttpSecurity} bean this contributes nothing, no
 * {@code HttpRoutePolicy} bean exists, the slot is left unbound, and the kernel applies no per-route
 * requirement — identical to a kernel with no policy at all. Declaring nothing changes nothing.
 *
 * <h2>Why the bean type crossing the module boundary is a kernel type</h2>
 *
 * <p>The binding happens in {@code exeris-spring-boot-autoconfigure}, and
 * {@code autoconfigure &rarr; web} is a banned dependency edge. So this module publishes the
 * <em>kernel SPI</em> type {@code HttpRoutePolicy}, and the lifecycle injects
 * {@code Optional<HttpRoutePolicy>} without knowing this module exists — the same shape already used
 * for {@code HttpHandler}. The compiler and the DSL stay here; only the compiled kernel contract
 * travels.
 *
 * <p>Compilation runs at bean-creation time, so a declaration that is incomplete or would deny the
 * kernel's probe endpoints fails context refresh — before the kernel boots and long before a request
 * arrives.
 *
 * @since 0.8.0
 */
@AutoConfiguration
@ConditionalOnBean(ExerisHttpSecurity.class)
public class ExerisHttpSecurityAutoConfiguration {

    /**
     * @param security the application's declaration
     * @return the compiled policy, published as the kernel SPI type
     */
    @Bean
    @ConditionalOnMissingBean(HttpRoutePolicy.class)
    public HttpRoutePolicy exerisHttpRoutePolicy(ExerisHttpSecurity security) {
        return ExerisRoutePolicyCompiler.compile(security);
    }
}
