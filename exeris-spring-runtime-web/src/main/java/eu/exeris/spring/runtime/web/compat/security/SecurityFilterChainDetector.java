/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.web.compat.security;

import eu.exeris.spring.runtime.web.compat.CompatibilityMode;

import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;

import java.util.Optional;

/**
 * Detects an application-declared {@code SecurityFilterChain} bean without loading servlet-only
 * Spring Security types.
 *
 * <h2>Why by name</h2>
 * <p>{@code SecurityFilterChain} lives in {@code spring-security-web}, which drags in
 * {@code jakarta.servlet}. Referencing the type here would put a servlet class on the compat
 * classpath and fail {@code PureModeClasspathGuardTest}. Detection therefore works on bean
 * definition metadata and type names only — nothing is instantiated and no chain class is loaded.
 *
 * <h2>Why it exists as a separate class</h2>
 * <p>Two callers need the same answer at two different points in the lifecycle: the condition that
 * keeps the compat fallback filter from activating, and the fail-fast check that stops the context
 * from starting. Duplicating the detection was how the two could silently disagree — the condition
 * seeing a chain and standing down while the check saw nothing and let the context start with no
 * security at all.
 *
 * @since 0.7.0
 */
@CompatibilityMode
public final class SecurityFilterChainDetector {

    private static final String SECURITY_FILTER_CHAIN_TYPE =
            "org.springframework.security.web.SecurityFilterChain";
    private static final String DEFAULT_CHAIN_BEAN_NAME = "springSecurityFilterChain";

    private SecurityFilterChainDetector() {
    }

    /**
     * Finds the name of a declared {@code SecurityFilterChain} bean, if any.
     *
     * @param beanFactory the bean factory to inspect; may be {@code null}, in which case nothing is
     *                    detected
     * @return the detected bean name, or {@link Optional#empty()} when no chain is declared
     */
    public static Optional<String> detect(ConfigurableListableBeanFactory beanFactory) {
        if (beanFactory == null) {
            return Optional.empty();
        }
        if (beanFactory.containsBean(DEFAULT_CHAIN_BEAN_NAME)) {
            return Optional.of(DEFAULT_CHAIN_BEAN_NAME);
        }
        for (String beanName : beanFactory.getBeanDefinitionNames()) {
            if (matchesChainType(beanFactory, beanName)) {
                return Optional.of(beanName);
            }
        }
        return Optional.empty();
    }

    private static boolean matchesChainType(ConfigurableListableBeanFactory beanFactory, String beanName) {
        var definition = beanFactory.getBeanDefinition(beanName);
        if (SECURITY_FILTER_CHAIN_TYPE.equals(definition.getBeanClassName())) {
            return true;
        }
        Object objectType = definition.getAttribute(FactoryBean.OBJECT_TYPE_ATTRIBUTE);
        if (objectType instanceof Class<?> objectClass
                && SECURITY_FILTER_CHAIN_TYPE.equals(objectClass.getName())) {
            return true;
        }
        if (objectType instanceof String typeName
                && SECURITY_FILTER_CHAIN_TYPE.equals(typeName)) {
            return true;
        }
        try {
            Class<?> beanType = beanFactory.getType(beanName, false);
            return beanType != null && SECURITY_FILTER_CHAIN_TYPE.equals(beanType.getName());
        } catch (Throwable _) {
            // Type introspection can fail for beans whose declared types are servlet-only and
            // absent from this classpath — which is precisely the situation this runtime creates.
            // A failure to resolve is not evidence of a chain, so treat it as "no match".
            return false;
        }
    }
}
