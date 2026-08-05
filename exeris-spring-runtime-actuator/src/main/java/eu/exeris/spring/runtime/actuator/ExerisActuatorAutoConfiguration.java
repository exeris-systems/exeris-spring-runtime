/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.actuator;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import java.util.Optional;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.support.RootBeanDefinition;
import eu.exeris.spring.runtime.actuator.bridge.SpringBootHealthIndicatorFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import eu.exeris.spring.boot.autoconfigure.ExerisRuntimeAutoConfiguration;
import eu.exeris.spring.boot.autoconfigure.ExerisRuntimeLifecycle;
import eu.exeris.spring.boot.autoconfigure.ExerisRuntimeProperties;
import eu.exeris.spring.runtime.actuator.compat.ExerisCompatibilityActuatorController;

/**
 * Autoconfiguration for Exeris Spring Runtime actuator integration.
 *
 * <p>Registers:
 * <ul>
 *   <li>{@link ExerisRuntimeHealthIndicator} — lifecycle liveness indicator (always,
 *       when Spring Boot Actuator is on the classpath)
 *   <li>{@link ExerisCompatibilityActuatorController} — compatibility-mode HTTP diagnostics
 *       bridge exposing Spring Boot Actuator's standard {@code /actuator/health} and
 *       {@code /actuator/info} endpoint shape without requiring servlet or reactive ownership
 *   <li>{@link ExerisActuatorTelemetryBridge} — Micrometer event-level counter bridge
 *       (only when {@code io.micrometer.core.instrument.MeterRegistry} is present)
 * </ul>
 *
 * <h2>What This Does NOT Do</h2>
 * <p>This class does not own any runtime execution path, transport logic, request
 * processing, or transaction management. It is limited to operational visibility:
 * health, info, and metrics exposure.
 *
 * @since 0.1.0
 */
@AutoConfiguration(after = ExerisRuntimeAutoConfiguration.class)
@ConditionalOnClass(name = "org.springframework.boot.actuate.endpoint.annotation.Endpoint")
@ConditionalOnProperty(prefix = "exeris.runtime", name = "enabled", matchIfMissing = true)
public class ExerisActuatorAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ExerisRuntimeHealthIndicator exerisRuntimeHealthIndicator(
            ExerisRuntimeLifecycle lifecycle) {
        return new ExerisRuntimeHealthIndicator(lifecycle);
    }

    /**
     * Registers the Spring Boot {@code HealthIndicator} shape.
     *
     * <p>Registered through a {@link BeanDefinitionRegistryPostProcessor} rather than a {@code @Bean}
     * method because the bean's type is not nameable at compile time — it differs per matrix line (see
     * {@link SpringBootHealthIndicatorFactory}). A {@code @Bean} method would have to declare
     * {@code Object}, and Spring Boot's health registry finds contributors by
     * {@code getBeansOfType(HealthIndicator.class)}, which resolves a factory method's declared return
     * type <em>before</em> instantiating it — so an {@code Object}-typed definition would never match
     * and the indicator would silently never appear. Setting the definition's target type explicitly
     * is what makes the proxy discoverable.
     */
    @Bean
    public static ExerisHealthIndicatorRegistrar exerisHealthIndicatorRegistrar() {
        return new ExerisHealthIndicatorRegistrar();
    }

    /**
     * Registers a {@code HealthIndicator}-typed bean definition backed by the runtime-created proxy.
     *
     * <p>Stands down quietly when the health types cannot be resolved: the actuator is operational
     * visibility, so a missing indicator must not stop the application from serving traffic. The
     * stand-down is logged so the absence is visible rather than silent.
     */
    static final class ExerisHealthIndicatorRegistrar
            implements BeanDefinitionRegistryPostProcessor, BeanFactoryAware {

        static final String BEAN_NAME = "exerisRuntimeSpringBootHealthIndicator";

        private static final System.Logger LOGGER =
                System.getLogger(ExerisHealthIndicatorRegistrar.class.getName());

        private BeanFactory beanFactory;

        @Override
        public void setBeanFactory(BeanFactory beanFactory) {
            this.beanFactory = beanFactory;
        }

        @Override
        public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) {
            if (registry.containsBeanDefinition(BEAN_NAME)) {
                return;
            }
            ClassLoader classLoader = ExerisActuatorAutoConfiguration.class.getClassLoader();
            Optional<Class<?>> indicatorType =
                    SpringBootHealthIndicatorFactory.healthIndicatorInterface(classLoader);
            if (indicatorType.isEmpty()) {
                LOGGER.log(System.Logger.Level.DEBUG,
                        () -> "No Spring Boot HealthIndicator type on the classpath; "
                                + "the Exeris runtime health indicator is not registered.");
                return;
            }

            RootBeanDefinition definition = new RootBeanDefinition();
            definition.setTargetType(indicatorType.get());
            definition.setInstanceSupplier(this::createIndicator);
            definition.setLazyInit(false);
            registry.registerBeanDefinition(BEAN_NAME, definition);
        }

        private Object createIndicator() {
            ExerisRuntimeHealthIndicator source = beanFactory.getBean(ExerisRuntimeHealthIndicator.class);
            return SpringBootHealthIndicatorFactory
                    .createHealthIndicator(source, ExerisActuatorAutoConfiguration.class.getClassLoader())
                    .orElseThrow(() -> new IllegalStateException(
                            "Spring Boot HealthIndicator interface resolved but its Health builder API "
                                    + "did not match either known Spring Boot line"));
        }
    }

    /**
     * Compatibility-mode HTTP diagnostics bridge.
     *
     * <p>Exposes management-style JSON responses through the Exeris compatibility dispatcher
     * when no servlet or reactive web server path exists.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.web.bind.annotation.RestController")
    @ConditionalOnProperty(prefix = "exeris.runtime.web", name = "mode", havingValue = "compatibility")
    static class CompatibilityEndpointsConfiguration {

        @Bean
        @ConditionalOnMissingBean
        public ExerisCompatibilityActuatorController exerisCompatibilityActuatorController(
                ExerisRuntimeHealthIndicator healthIndicator,
                ObjectProvider<ExerisRuntimeProperties> runtimeProperties,
                ObjectProvider<InfoContributor> infoContributors) {
            return new ExerisCompatibilityActuatorController(healthIndicator, runtimeProperties, infoContributors);
        }
    }

    /**
     * Micrometer bridge — only activated when {@code MeterRegistry} is on the classpath.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "io.micrometer.core.instrument.MeterRegistry")
    static class MicrometerBridgeConfiguration {

        @Bean
        @ConditionalOnMissingBean
        public ExerisActuatorTelemetryBridge exerisActuatorTelemetryBridge() {
            return new ExerisActuatorTelemetryBridge();
        }
    }
}
