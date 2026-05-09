/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.tx;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

import java.util.Map;

import eu.exeris.spring.runtime.tx.PersistenceEngineProvider;

import static org.assertj.core.api.Assertions.assertThat;

class ExerisTransactionAutoConfigurationTest {

    @Test
    void beanAbsentByDefault() {
        try (var context = createContext(Map.of())) {
            assertThat(context.getBeanNamesForType(ExerisPlatformTransactionManager.class)).isEmpty();
        }
    }

    @Test
    void beanPresentWhenPropertyEnabled() {
        try (var context = createContext(Map.of("exeris.runtime.tx.enabled", "true"))) {
            assertThat(context.getBeanNamesForType(ExerisPlatformTransactionManager.class)).hasSize(1);
        }
    }

    @Test
    void persistenceEngineProviderBeanPresentWhenPropertyEnabled() {
        try (var context = createContext(Map.of("exeris.runtime.tx.enabled", "true"))) {
            assertThat(context.getBeanNamesForType(PersistenceEngineProvider.class)).hasSize(1);
        }
    }

    private AnnotationConfigApplicationContext createContext(Map<String, Object> properties) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(
                new MapPropertySource("testProps", properties));
        context.register(ExerisTransactionAutoConfiguration.class, BaselineTxManagerConfig.class);
        context.refresh();
        return context;
    }

    @Configuration(proxyBeanMethods = false)
    static class BaselineTxManagerConfig {

        @Bean
        PlatformTransactionManager baselinePlatformTransactionManager() {
            return new PlatformTransactionManager() {
                @Override
                public @NonNull TransactionStatus getTransaction(@Nullable TransactionDefinition definition) {
                    throw new UnsupportedOperationException("test baseline");
                }

                @Override
                public void commit(@NonNull TransactionStatus status) {
                    throw new UnsupportedOperationException("test baseline");
                }

                @Override
                public void rollback(@NonNull TransactionStatus status) {
                    throw new UnsupportedOperationException("test baseline");
                }
            };
        }
    }
}
