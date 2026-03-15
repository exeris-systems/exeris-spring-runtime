/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.boot.autoconfigure;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

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
 * @since 0.1.0
 */
@AutoConfiguration
@EnableConfigurationProperties(ExerisRuntimeProperties.class)
@ConditionalOnProperty(prefix = "exeris.runtime", name = "enabled", matchIfMissing = true)
public class ExerisRuntimeAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ExerisSpringConfigProvider exerisSpringConfigProvider(Environment environment) {
        return new ExerisSpringConfigProvider(environment);
    }

    @Bean
    @ConditionalOnMissingBean
    public ExerisRuntimeLifecycle exerisRuntimeLifecycle(
            ExerisRuntimeProperties properties,
            ExerisSpringConfigProvider configProvider) {
        return new ExerisRuntimeLifecycle(properties, configProvider);
    }
}
