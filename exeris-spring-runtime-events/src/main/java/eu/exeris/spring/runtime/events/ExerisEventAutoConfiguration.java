/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.events;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;

import eu.exeris.kernel.spi.events.EventEngine;
import eu.exeris.spring.boot.autoconfigure.ExerisRuntimeAutoConfiguration;
import eu.exeris.spring.boot.autoconfigure.ExerisRuntimeLifecycle;

/**
 * Autoconfiguration for the Exeris events bridge.
 *
 * <h2>Activation</h2>
 * <ul>
 *   <li>{@code exeris.runtime.events.enabled = true} (default-off; must remain explicit and
 *       never silently enabled).</li>
 *   <li>The kernel events SPI must be on the classpath (guarded by
 *       {@code @ConditionalOnClass(EventEngine.class)}).</li>
 *   <li>Runs after {@link ExerisRuntimeAutoConfiguration} so the lifecycle bean is
 *       available to wire the {@link EventEngineSupplier}.</li>
 * </ul>
 *
 * <h2>What This Does NOT Do</h2>
 * <ul>
 *   <li>Does not wire {@code ApplicationEventPublisher} into the Exeris bus.</li>
 *   <li>Does not own or replace any HTTP / transaction / persistence concerns.</li>
 *   <li>Does not create an {@code EventEngine} — that is owned by the kernel via its
 *       {@code EventProvider}; this module only bridges Spring beans to the engine the
 *       kernel produced.</li>
 * </ul>
 *
 * @since 0.1
 */
@AutoConfiguration(after = ExerisRuntimeAutoConfiguration.class)
@ConditionalOnClass(EventEngine.class)
@ConditionalOnProperty(prefix = "exeris.runtime.events", name = "enabled", matchIfMissing = false)
@EnableConfigurationProperties(ExerisEventProperties.class)
public class ExerisEventAutoConfiguration {

    /**
     * Default constructor for auto-configuration.
     */
    public ExerisEventAutoConfiguration() {
    }

    /**
     * Creates the supplier for accessing the kernel event engine.
     *
     * @param lifecycle the runtime lifecycle owner
     * @return the {@link EventEngineSupplier}
     */
    @Bean
    @ConditionalOnMissingBean
    public EventEngineSupplier exerisEventEngineSupplier(ExerisRuntimeLifecycle lifecycle) {
        return lifecycle::getEventEngine;
    }

    /**
     * Creates the event type registry.
     *
     * @param engineSupplier supplier for the kernel event engine
     * @return the {@link ExerisEventTypeRegistry}
     */
    @Bean
    @ConditionalOnMissingBean
    public ExerisEventTypeRegistry exerisEventTypeRegistry(EventEngineSupplier engineSupplier) {
        return new ExerisEventTypeRegistry(engineSupplier);
    }

    /**
     * Creates the event publisher.
     *
     * @param engineSupplier supplier for the kernel event engine
     * @param typeRegistry the event type registry
     * @return the {@link ExerisEventPublisher}
     */
    @Bean
    @ConditionalOnMissingBean
    public ExerisEventPublisher exerisEventPublisher(EventEngineSupplier engineSupplier,
                                                     ExerisEventTypeRegistry typeRegistry) {
        return new ExerisEventPublisher(engineSupplier, typeRegistry);
    }

    /**
     * Creates the event listener registrar lifecycle bean.
     *
     * @param applicationContext the application context
     * @param engineSupplier supplier for the kernel event engine
     * @param properties configuration properties
     * @return the {@link ExerisEventListenerRegistrar}
     */
    @Bean
    @ConditionalOnMissingBean
    public ExerisEventListenerRegistrar exerisEventListenerRegistrar(ApplicationContext applicationContext,
                                                                      EventEngineSupplier engineSupplier,
                                                                      ExerisEventProperties properties) {
        return new ExerisEventListenerRegistrar(applicationContext, engineSupplier, properties);
    }
}
