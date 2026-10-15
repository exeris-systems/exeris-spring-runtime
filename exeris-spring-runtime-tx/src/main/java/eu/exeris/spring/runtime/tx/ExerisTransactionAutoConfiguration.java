/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.tx;

import eu.exeris.kernel.spi.context.KernelProviders;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Thin autoconfiguration for optional Exeris transaction integration.
 *
 * <p>Activated only when {@code exeris.runtime.tx.enabled=true}. Registers:
 * <ul>
 *   <li>{@link ExerisPlatformTransactionManager} — Spring transaction abstraction bridge.</li>
 *   <li>{@link PersistenceEngineProvider} — deferred ScopedValue accessor for
 *       Exeris-native repository injection.</li>
 * </ul>
 *
 * <h2>What This Does NOT Do</h2>
 * <p>Does not own transport logic, web handling, or data-source configuration.
 * Persistence engine access is deferred to call time via {@link PersistenceEngineProvider}.
 */
@AutoConfiguration
@ConditionalOnClass({PlatformTransactionManager.class, ExerisPlatformTransactionManager.class})
@ConditionalOnProperty(prefix = "exeris.runtime.tx", name = "enabled", havingValue = "true")
public class ExerisTransactionAutoConfiguration {

    /**
     * Creates the auto-configuration.
     *
     * <p>Holds no state; Spring instantiates it once and invokes its {@code @Bean} methods.
     */
    public ExerisTransactionAutoConfiguration() {
        // No construction logic beyond the superclass constructor.
        super();
    }

    /**
     * Registers the {@link ExerisPlatformTransactionManager} that bridges Spring's transaction
     * abstraction to the kernel's persistence transactions. Backs off when the application declares its own
     * bean of that type.
     *
     * @return a new transaction manager
     */
    @Bean
    @ConditionalOnMissingBean(ExerisPlatformTransactionManager.class)
    public ExerisPlatformTransactionManager exerisPlatformTransactionManager() {
        return new ExerisPlatformTransactionManager();
    }

    /**
     * Registers a deferred {@link PersistenceEngineProvider} bean.
     *
     * <p>The lambda reads {@link KernelProviders#PERSISTENCE_ENGINE} at call time —
     * never at bean construction time. This preserves the ScopedValue contract:
     * the engine is always read from the current kernel VT scope, not captured once
     * as a static singleton reference.
     *
     * @return a provider that resolves the persistence engine on every call
     */
    @Bean
    @ConditionalOnMissingBean
    public PersistenceEngineProvider persistenceEngineProvider() {
        return () -> KernelProviders.PERSISTENCE_ENGINE.get();
    }
}
