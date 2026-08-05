/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.actuator;

import eu.exeris.spring.boot.autoconfigure.ExerisRuntimeLifecycle;
import eu.exeris.spring.boot.autoconfigure.ExerisRuntimeProperties;
import eu.exeris.spring.runtime.actuator.ExerisActuatorAutoConfiguration.ExerisHealthIndicatorRegistrar;
import eu.exeris.spring.runtime.actuator.bridge.SpringBootHealthIndicatorFactory;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ExerisHealthIndicatorRegistrar}.
 *
 * <p>The registrar exists for one reason, and it is the thing worth testing: Spring Boot finds health
 * contributors with {@code getBeansOfType(HealthIndicator.class)}, which resolves a factory method's
 * <em>declared</em> return type before instantiating it. The proxy's type is not nameable at compile
 * time, so an {@code Object}-returning {@code @Bean} would never match and the indicator would silently
 * never appear. These assertions pin the two properties that prevent that: the definition carries the
 * line's {@code HealthIndicator} as its target type, and the instance it supplies really is one.
 */
class ExerisHealthIndicatorRegistrarTest {

    @Test
    void registersADefinitionTypedAsTheLinesHealthIndicator() {
        DefaultListableBeanFactory beanFactory = beanFactoryWithHealthSource();
        ExerisHealthIndicatorRegistrar registrar = registrar(beanFactory);

        registrar.postProcessBeanDefinitionRegistry(beanFactory);

        assertThat(beanFactory.containsBeanDefinition(ExerisHealthIndicatorRegistrar.BEAN_NAME)).isTrue();

        Class<?> expected = SpringBootHealthIndicatorFactory
                .healthIndicatorInterface(getClass().getClassLoader()).orElseThrow();
        RootBeanDefinition definition = (RootBeanDefinition) beanFactory
                .getBeanDefinition(ExerisHealthIndicatorRegistrar.BEAN_NAME);

        assertThat(definition.getTargetType())
                .as("without an explicit target type Boot's getBeansOfType would never match this bean")
                .isEqualTo(expected);
    }

    @Test
    void theRegisteredBeanIsDiscoverableByType_andReportsRuntimeHealth() throws Exception {
        DefaultListableBeanFactory beanFactory = beanFactoryWithHealthSource();
        registrar(beanFactory).postProcessBeanDefinitionRegistry(beanFactory);

        Class<?> indicatorType = SpringBootHealthIndicatorFactory
                .healthIndicatorInterface(getClass().getClassLoader()).orElseThrow();

        // The end-to-end property: this is how Spring Boot's health registry actually finds it.
        assertThat(beanFactory.getBeanNamesForType(indicatorType))
                .contains(ExerisHealthIndicatorRegistrar.BEAN_NAME);

        Object indicator = beanFactory.getBean(ExerisHealthIndicatorRegistrar.BEAN_NAME);
        Object health = indicator.getClass().getMethod("health").invoke(indicator);
        Object status = health.getClass().getMethod("getStatus").invoke(health);

        assertThat(status.getClass().getMethod("getCode").invoke(status)).isEqualTo("DOWN");
    }

    @Test
    void isIdempotent_whenTheDefinitionAlreadyExists() {
        DefaultListableBeanFactory beanFactory = beanFactoryWithHealthSource();
        beanFactory.registerBeanDefinition(
                ExerisHealthIndicatorRegistrar.BEAN_NAME, new RootBeanDefinition(String.class));

        registrar(beanFactory).postProcessBeanDefinitionRegistry(beanFactory);

        assertThat(beanFactory.getBeanDefinition(ExerisHealthIndicatorRegistrar.BEAN_NAME).getBeanClassName())
                .as("an existing definition must be left alone, not replaced")
                .isEqualTo(String.class.getName());
    }

    @Test
    void doesNotFailWhenTheHealthSourceIsMissingUntilTheBeanIsRequested() {
        // Registration is definition-only: the supplier resolves ExerisRuntimeHealthIndicator lazily,
        // so a registry without one still post-processes cleanly. That laziness is what keeps the
        // registrar independent of bean-definition ordering.
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        ExerisHealthIndicatorRegistrar registrar = registrar(beanFactory);

        registrar.postProcessBeanDefinitionRegistry(beanFactory);

        assertThat(beanFactory.containsBeanDefinition(ExerisHealthIndicatorRegistrar.BEAN_NAME)).isTrue();
    }

    @Test
    void standsDownWhenNoHealthTypeResolves() {
        // No HealthIndicator interface on the classpath — a deployment without the actuator's health
        // artifact. Registering nothing is correct: operational visibility must not stop the
        // application, and a definition typed as null would fail later and less clearly.
        ClassLoader empty = new ClassLoader(null) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                throw new ClassNotFoundException(name);
            }
        };
        DefaultListableBeanFactory beanFactory = beanFactoryWithHealthSource();
        ExerisHealthIndicatorRegistrar registrar = new ExerisHealthIndicatorRegistrar(empty);
        registrar.setBeanFactory(beanFactory);

        registrar.postProcessBeanDefinitionRegistry(beanFactory);

        assertThat(beanFactory.containsBeanDefinition(ExerisHealthIndicatorRegistrar.BEAN_NAME)).isFalse();
    }

    // =========================================================================

    private static ExerisHealthIndicatorRegistrar registrar(DefaultListableBeanFactory beanFactory) {
        ExerisHealthIndicatorRegistrar registrar = new ExerisHealthIndicatorRegistrar();
        registrar.setBeanFactory(beanFactory);
        return registrar;
    }

    private static DefaultListableBeanFactory beanFactoryWithHealthSource() {
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        ExerisRuntimeLifecycle lifecycle =
                new ExerisRuntimeLifecycle(new ExerisRuntimeProperties(), null, Optional.empty());
        beanFactory.registerSingleton(
                "exerisRuntimeHealthIndicator", new ExerisRuntimeHealthIndicator(lifecycle));
        return beanFactory;
    }
}
