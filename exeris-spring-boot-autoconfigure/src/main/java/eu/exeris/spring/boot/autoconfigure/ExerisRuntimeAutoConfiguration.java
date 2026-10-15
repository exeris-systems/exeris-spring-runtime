/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.boot.autoconfigure;

import eu.exeris.kernel.spi.http.HttpHandler;
import eu.exeris.kernel.spi.http.HttpRoutePolicy;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

import java.util.Optional;

/**
 * Boot autoconfiguration entry point for the Exeris runtime integration.
 *
 * <p>This class is the top of the autoconfiguration hierarchy. It must remain thin:
 * its sole responsibility is to wire together externally defined beans
 * ({@link ExerisRuntimeProperties}, {@link ExerisSpringConfigProvider},
 * {@link ExerisRuntimeLifecycle}) and apply the correct activation conditions.
 *
 * <h2>Activation Conditions</h2>
 * <ul>
 *   <li>Exeris kernel SPI must be on the classpath (guarded by
 *       {@code @ConditionalOnClass}).</li>
 *   <li>{@code exeris.runtime.enabled} must be {@code true} (the default).</li>
 * </ul>
 *
 * <h2>What This Does NOT Do</h2>
 * <p>This class does not contain transport logic, request processing, transaction
 * management, or compatibility mode logic. Those belong in
 * {@code exeris-spring-runtime-web}, {@code exeris-spring-runtime-tx}, etc.
 *
 * @since 0.1
 */
@AutoConfiguration
@EnableConfigurationProperties(ExerisRuntimeProperties.class)
@ConditionalOnClass(HttpHandler.class)
@ConditionalOnProperty(prefix = "exeris.runtime", name = "enabled", matchIfMissing = true)
public class ExerisRuntimeAutoConfiguration {

    /**
     * Creates the auto-configuration.
     *
     * <p>Holds no state; Spring instantiates it once and invokes its {@code @Bean} methods.
     */
    public ExerisRuntimeAutoConfiguration() {
    }

    /**
     * Registers the {@link ExerisSpringConfigProvider} that exposes Spring's {@link Environment} to the
     * kernel as its configuration source. Backs off when the application declares its own provider bean.
     *
     * @param environment the Spring environment the provider reads configuration from
     * @return the provider backed by {@code environment}
     */
    @Bean
    @ConditionalOnMissingBean
    public ExerisSpringConfigProvider exerisSpringConfigProvider(Environment environment) {
        return new ExerisSpringConfigProvider(environment);
    }

    /**
     * Registers the {@link ExerisRuntimeLifecycle} that starts the kernel after the Spring context has
     * refreshed and stops it on shutdown. Backs off when the application declares its own lifecycle bean.
     *
     * @param properties the bound {@code exeris.runtime.*} properties
     * @param configProvider the configuration provider handed to the kernel at bootstrap
     * @param httpHandler the application's kernel HTTP handler, empty when none is declared
     * @param httpRoutePolicy the compiled per-route authorization policy, empty when none is declared
     * @return the lifecycle bean
     */
    @Bean
    @ConditionalOnMissingBean
    public ExerisRuntimeLifecycle exerisRuntimeLifecycle(
            ExerisRuntimeProperties properties,
            ExerisSpringConfigProvider configProvider,
            Optional<HttpHandler> httpHandler,
            Optional<HttpRoutePolicy> httpRoutePolicy) {
        return new ExerisRuntimeLifecycle(properties, configProvider, httpHandler, httpRoutePolicy);
    }
}
