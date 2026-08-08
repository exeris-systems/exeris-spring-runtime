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
 * <h2>The fail-open this closes</h2>
 * <p>{@code NoSecurityFilterChainCondition} stands the compatibility fallback filter down when a
 * chain is present — correct in itself, because the two must not both run. But under
 * {@code web-application-type=none} there is no {@code FilterChainProxy} to run the chain either.
 * The result was a context that started cleanly, logged nothing, and served every request with
 * neither authentication nor authorization: the application believed its chain was enforcing rules,
 * and nothing was. A migration must fail loudly at startup rather than pass unauthenticated traffic.
 *
 * <h2>Why a BeanFactoryPostProcessor</h2>
 * <p>It runs after every bean definition is registered but before any singleton is instantiated, so
 * detection sees the full picture and the failure arrives before the application can bind a port.
 * It also inspects definitions rather than instances, which keeps servlet-only types off the
 * classpath — see {@link SecurityFilterChainDetector}.
 *
 * <h2>Escape hatch</h2>
 * <p>{@code exeris.runtime.web.compat.security.allow-unenforced-filter-chain=true} downgrades the
 * failure to a warning. It does not make the chain run.
 *
 * @since 0.7.0
 */
@CompatibilityMode
public final class UnenforcedSecurityFilterChainCheck implements BeanFactoryPostProcessor, EnvironmentAware {

    /** Property that downgrades the startup failure to a warning. */
    public static final String ALLOW_PROPERTY =
            "exeris.runtime.web.compat.security.allow-unenforced-filter-chain";

    private static final System.Logger LOGGER =
            System.getLogger(UnenforcedSecurityFilterChainCheck.class.getName());

    private Environment environment;

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

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
