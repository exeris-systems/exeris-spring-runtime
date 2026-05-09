/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.data;

import eu.exeris.spring.runtime.data.compat.ExerisDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

class ExerisDataAutoConfigurationTest {

    @Test
    void beanAbsentByDefault() {
        try (AnnotationConfigApplicationContext context = createContext()) {
            assertThat(context.getBeansOfType(ExerisDataSource.class)).isEmpty();
        }
    }

    @Test
    void beanPresentWhenPropertyEnabled() {
        try (AnnotationConfigApplicationContext context =
                     createContext("exeris.runtime.data.compat-datasource.enabled=true")) {
            assertThat(context.getBeansOfType(ExerisDataSource.class)).hasSize(1);
        }
    }

    private AnnotationConfigApplicationContext createContext(String... properties) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        TestPropertyValues.of(properties).applyTo(context);
        context.register(ExerisDataAutoConfiguration.class);
        context.refresh();
        return context;
    }
}
