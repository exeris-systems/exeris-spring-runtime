/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.data;

import eu.exeris.spring.runtime.data.compat.ExerisDataSource;
import eu.exeris.spring.runtime.tx.ExerisPlatformTransactionManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.lang.Nullable;

/**
 * Compatibility scaffold auto-configuration for Exeris DataSource adapter.
 *
 * <p>This configuration is intentionally non-default and must be enabled explicitly.
 * Activating it signals intent to use the JDBC compatibility bridge (Level 2 per Phase 3 plan).
 * Level 1 (Exeris-native QueryExecutor) remains the recommended path.
 *
 * <p>When an {@link ExerisPlatformTransactionManager} bean is present, wires it with
 * an {@link eu.exeris.spring.runtime.tx.ExerisJdbcResourceCallback} so that
 * transaction-bound connections are correctly shared with JPA/Hibernate.
 */
@AutoConfiguration
@ConditionalOnClass(ExerisDataSource.class)
@ConditionalOnProperty(
        prefix = "exeris.runtime.data.compat-datasource",
        name = "enabled",
        havingValue = "true"
)
public class ExerisDataAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(ExerisDataSource.class)
    public ExerisDataSource exerisDataSource(
            @Autowired(required = false) @Nullable ExerisPlatformTransactionManager ptm) {
        ExerisDataSource dataSource = new ExerisDataSource();
        if (ptm != null) {
            ptm.setJdbcResourceCallback(dataSource::bindTransactionConnection);
        }
        return dataSource;
    }
}

