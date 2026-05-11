/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.data;

import javax.sql.DataSource;

import eu.exeris.spring.runtime.data.compat.ExerisDataSource;
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
 * Activating it signals intent to use the JDBC compatibility bridge (Level 2 per
 * Phase 3 plan / ADR-017). Level 1 (Exeris-native QueryExecutor) remains the
 * recommended path.
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
 *       Exeris stands down — Spring's standard precedence rules apply unchanged.</li>
 * </ul>
 * <p>The bean is also marked {@link Primary @Primary} as a belt-and-braces guard for
 * unusual wiring orders where two {@link DataSource} beans end up co-resident.
 *
 * <p>This ordering was added after downstream migration review surfaced that the prior
 * configuration could let Spring Boot's default {@code DataSourceAutoConfiguration}
 * win over the Exeris adapter even when the opt-in property was set — which
 * contradicted the Phase 3 / ADR-017 intent that opting in means the Exeris adapter
 * is the runtime-owned bridge. The {@code @AutoConfigureBefore} name reference is
 * used (rather than a class literal) so this module does not require {@code spring-jdbc}
 * on its compile classpath; the opt-in property remains the only activation switch.
 */
@AutoConfiguration(beforeName = "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration")
@ConditionalOnClass(ExerisDataSource.class)
@ConditionalOnProperty(
        prefix = "exeris.runtime.data.compat-datasource",
        name = "enabled",
        havingValue = "true"
)
public class ExerisDataAutoConfiguration {

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
}

