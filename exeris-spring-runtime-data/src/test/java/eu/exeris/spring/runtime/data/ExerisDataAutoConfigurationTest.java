/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.data;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;

import eu.exeris.spring.runtime.data.compat.ExerisDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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

    /**
     * The Exeris adapter is registered as the primary {@link DataSource} bean so that
     * a {@link DataSource} autowiring point resolves to it unambiguously even if a
     * concurrent {@link DataSource} bean ends up co-resident through unusual wiring.
     */
    @Test
    void exerisDataSourceBeanIsMarkedPrimary() {
        try (AnnotationConfigApplicationContext context =
                     createContext("exeris.runtime.data.compat-datasource.enabled=true")) {
            assertThat(context.getBeanDefinition("exerisDataSource").isPrimary())
                    .as("@Primary on exerisDataSource is the belt-and-braces guard "
                            + "against ambiguity if two DataSource beans co-reside")
                    .isTrue();
        }
    }

    /**
     * When the application explicitly provides its own {@link DataSource} bean,
     * the Exeris adapter must stand down — {@code @ConditionalOnMissingBean(DataSource.class)}
     * is the standard Spring contract for "I'm only here if no one else is."
     */
    @Test
    void exerisAdapterStandsDownWhenUserProvidesOwnDataSource() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ExerisDataAutoConfiguration.class))
                .withUserConfiguration(UserDataSourceConfig.class)
                .withPropertyValues("exeris.runtime.data.compat-datasource.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBeansOfType(ExerisDataSource.class))
                            .as("user-supplied DataSource → Exeris adapter skips")
                            .isEmpty();
                    assertThat(context.getBeansOfType(DataSource.class))
                            .as("exactly one DataSource — the user's")
                            .hasSize(1);
                });
    }

    /**
     * Ordering guard: the {@code @AutoConfiguration(beforeName=…)} declaration must
     * explicitly position this autoconfig ahead of Spring Boot's
     * {@code DataSourceAutoConfiguration}. Without that, Spring Boot's Hikari-backed
     * adapter could win over the Exeris-owned bridge — inverting the runtime
     * ownership claim the opt-in property is making.
     *
     * <p>This test reads the annotation directly via reflection so it does not
     * require Spring Boot's {@code DataSourceAutoConfiguration} or {@code spring-jdbc}
     * on the test classpath — both are intentionally absent from this module's
     * dependency set (ADR-017 §4.3 forbids them at compile/runtime scope).
     */
    @Test
    void autoConfigurationDeclaresOrderingBeforeDataSourceAutoConfiguration() {
        AutoConfiguration annotation = ExerisDataAutoConfiguration.class
                .getAnnotation(AutoConfiguration.class);
        assertThat(annotation)
                .as("@AutoConfiguration must be present on ExerisDataAutoConfiguration")
                .isNotNull();
        assertThat(annotation.beforeName())
                .as("beforeName must reference Spring Boot's DataSourceAutoConfiguration "
                        + "by FQN — using a class literal would force spring-jdbc onto "
                        + "this module's classpath, which ADR-017 §4.3 forbids")
                .contains("org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration");
    }

    private AnnotationConfigApplicationContext createContext(String... properties) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        TestPropertyValues.of(properties).applyTo(context);
        context.register(ExerisDataAutoConfiguration.class);
        context.refresh();
        return context;
    }

    @Configuration
    static class UserDataSourceConfig {

        @Bean
        @SuppressWarnings("unused") // discovered reflectively by Spring as a @Bean method
        DataSource userDataSource() {
            return new StubDataSource();
        }
    }

    /**
     * Minimal {@link DataSource} stub — only required to be a Spring bean of type
     * {@link DataSource}; no method on it is ever invoked by this test.
     */
    private static final class StubDataSource implements DataSource {

        @Override
        public java.sql.Connection getConnection() {
            throw new UnsupportedOperationException("test-stub DataSource");
        }

        @Override
        public java.sql.Connection getConnection(String username, String password) {
            throw new UnsupportedOperationException("test-stub DataSource");
        }

        @Override
        public java.io.PrintWriter getLogWriter() {
            throw new UnsupportedOperationException("test-stub DataSource");
        }

        @Override
        public void setLogWriter(java.io.PrintWriter out) {
            throw new UnsupportedOperationException("test-stub DataSource");
        }

        @Override
        public void setLoginTimeout(int seconds) {
            throw new UnsupportedOperationException("test-stub DataSource");
        }

        @Override
        public int getLoginTimeout() {
            return 0;
        }

        @Override
        public java.util.logging.Logger getParentLogger() {
            throw new UnsupportedOperationException("test-stub DataSource");
        }

        @Override
        public <T> T unwrap(Class<T> iface) {
            throw new UnsupportedOperationException("test-stub DataSource");
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) {
            return false;
        }
    }
}
