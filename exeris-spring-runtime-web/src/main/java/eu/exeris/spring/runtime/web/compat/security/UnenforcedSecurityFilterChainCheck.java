/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.web.compat.security;

import eu.exeris.spring.boot.autoconfigure.compat.CompatibilityMode;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;

/**
 * Fails context refresh when Compatibility Mode is active and the application declares a
 * {@code SecurityFilterChain} that this runtime cannot execute.
 *
 * @implNote Under {@code web-application-type=none} there is no {@code FilterChainProxy} to run a servlet filter chain.
 *     This processor runs after bean definitions are registered but before singletons are instantiated,
 *     detecting unenforced chains and failing startup before ports can bind.
 *     Setting {@value #ALLOW_PROPERTY} to {@code true} downgrades the failure to a warning log.
 * @since 0.7
 */
@CompatibilityMode
public final class UnenforcedSecurityFilterChainCheck implements BeanFactoryPostProcessor, EnvironmentAware {

    /** Property that downgrades the startup failure to a warning. */
    public static final String ALLOW_PROPERTY =
            "exeris.runtime.web.compat.security.allow-unenforced-filter-chain";

    private static final System.Logger LOGGER =
            System.getLogger(UnenforcedSecurityFilterChainCheck.class.getName());

    private Environment environment;

    /**
     * Default constructor for bean post-processing.
     */
    public UnenforcedSecurityFilterChainCheck() {
    }

    /**
     * {@inheritDoc}
     *
     * @param environment the environment to inspect
     */
    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    /**
     * {@inheritDoc}
     *
     * @param beanFactory the bean factory to inspect
     * @throws BeansException if post-processing fails
     */
    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
        SecurityFilterChainDetector.detect(beanFactory).ifPresent(this::report);
    }

    private void report(String beanName) {
        if (isExplicitlyAllowed()) {
            LOGGER.log(System.Logger.Level.WARNING,
                    () -> "Exeris Compatibility Mode: SecurityFilterChain bean '" + beanName
                            + "' will NOT be executed (no servlet container, no FilterChainProxy). "
                            + "Startup failure suppressed by " + ALLOW_PROPERTY + "=true — ensure "
                            + "requests are authorized by some other mechanism.");
            return;
        }
        throw new UnenforcedSecurityFilterChainException(beanName);
    }

    private boolean isExplicitlyAllowed() {
        return environment != null
                && environment.getProperty(ALLOW_PROPERTY, Boolean.class, Boolean.FALSE);
    }
}
