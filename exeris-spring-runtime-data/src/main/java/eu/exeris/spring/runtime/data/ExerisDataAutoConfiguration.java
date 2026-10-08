/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.data;

import javax.sql.DataSource;

import eu.exeris.spring.runtime.data.compat.ExerisDataSource;
import eu.exeris.spring.runtime.data.compat.ExerisHibernateBootstrapCustomizer;
import eu.exeris.spring.runtime.tx.ExerisPlatformTransactionManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.lang.Nullable;

/**
 * Compatibility scaffold auto-configuration for Exeris DataSource adapter.
 *
 * <p>This configuration is intentionally non-default and must be enabled explicitly.
 * Activating it signals intent to use the JDBC compatibility bridge per ADR-017.
 * Exeris-native persistence remains the recommended path.
 *
 * <p>When an {@link ExerisPlatformTransactionManager} bean is present, wires it with
 * an {@link eu.exeris.spring.runtime.tx.ExerisJdbcResourceCallback} so that
 * transaction-bound connections are correctly shared with JPA/Hibernate.
 *
 * <h2>Auto-configuration ordering</h2>
 * <p>When opted in via {@code exeris.runtime.data.compat-datasource.enabled=true},
 * this auto-configuration is positioned to run <em>before</em> Spring Boot's
 * {@code DataSourceAutoConfiguration}. Combined with
 * {@link ConditionalOnMissingBean @ConditionalOnMissingBean(DataSource.class)}, this
 * guarantees that:
 * <ul>
 *   <li>If neither Exeris nor any user-supplied bean provides a {@link DataSource},
 *       Exeris registers its own and Spring Boot's autoconfig then skips (its own
 *       {@code @ConditionalOnMissingBean(DataSource.class)} sees the Exeris bean).</li>
 *   <li>If the application explicitly provides its own {@link DataSource} bean,
 *       Exeris stands down &mdash; Spring's standard precedence rules apply unchanged.</li>
 * </ul>
 * <p>The bean is also marked {@link Primary @Primary} as a belt-and-braces guard for
 * unusual wiring orders where two {@link DataSource} beans end up co-resident.
 *
 * @implNote The {@code beforeName} attribute of {@link AutoConfiguration @AutoConfiguration}
 *     is used with the FQN string rather than a class literal so this module does not require
 *     {@code spring-jdbc} on its compile classpath. Running before Spring Boot's
 *     {@code DataSourceAutoConfiguration} ensures that the Exeris adapter takes precedence
 *     whenever explicitly enabled.
 * @since 0.1
 * @see "ADR-017: Persistence Seam and Compatibility Mode"
 */
@AutoConfiguration(beforeName = "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration")
@ConditionalOnClass(ExerisDataSource.class)
@ConditionalOnProperty(
        prefix = "exeris.runtime.data.compat-datasource",
        name = "enabled",
        havingValue = "true"
)
public class ExerisDataAutoConfiguration {

    /**
     * Default constructor for auto-configuration.
     */
    public ExerisDataAutoConfiguration() {
    }

    /**
     * Creates and registers the Exeris compatibility {@link DataSource} bean.
     *
     * @param ptm optional transaction manager to wire with connection binding callbacks
     * @return the configured {@link ExerisDataSource}
     */
    @Bean
    @Primary
    @ConditionalOnMissingBean(DataSource.class)
    public ExerisDataSource exerisDataSource(
            @Autowired(required = false) @Nullable ExerisPlatformTransactionManager ptm) {
        ExerisDataSource dataSource = new ExerisDataSource();
        if (ptm != null) {
            ptm.setJdbcResourceCallback(dataSource::bindTransactionConnection);
        }
        return dataSource;
    }

    /**
     * Supplies the Hibernate bootstrap settings that the ordering contract requires, so a JPA
     * application does not have to know about it.
     *
     * <p>Registered as a {@code static @Bean} so it is instantiated as a
     * {@code BeanFactoryPostProcessor} rather than an ordinary singleton: the properties it
     * contributes must reach the environment before {@code JpaProperties} is bound and the
     * {@code EntityManagerFactory} is built.
     *
     * <p>Not gated on Hibernate being present at the bean level &mdash; the class carries no Hibernate or
     * Spring-Boot-JPA import and checks for Hibernate itself before contributing anything, so the
     * bean is inert rather than absent when JPA is not in use.
     *
     * @return the {@link ExerisHibernateBootstrapCustomizer} instance
     */
    @Bean
    @ConditionalOnMissingBean(ExerisHibernateBootstrapCustomizer.class)
    public static ExerisHibernateBootstrapCustomizer exerisHibernateBootstrapCustomizer() {
        return new ExerisHibernateBootstrapCustomizer();
    }
}

