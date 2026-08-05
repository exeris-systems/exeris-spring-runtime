/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.data.compat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link ExerisHibernateBootstrapCustomizer}.
 *
 * <p>The behaviour under test is what a brownfield JPA application no longer has to write by hand.
 * The two things that matter most are that it fills the gap, and that it never overrules an
 * application that has already stated an answer.
 */
class ExerisHibernateBootstrapCustomizerTest {

    private static final String PG_URL = "jdbc:postgresql://localhost:5432/app";
    private static final String H2_URL = "jdbc:h2:mem:app;DB_CLOSE_DELAY=-1";

    @Test
    void disablesTheMetadataProbe_andDerivesTheDialect() {
        Map<String, Object> properties = customize(new MockEnvironment()
                .withProperty("exeris.runtime.persistence.jdbc-url", PG_URL));

        assertThat(properties)
                .containsEntry("hibernate.boot.allow_jdbc_metadata_access", "false")
                .containsEntry("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect");
    }

    @Test
    void derivesTheH2Dialect() {
        Map<String, Object> properties = customize(new MockEnvironment()
                .withProperty("exeris.runtime.persistence.jdbc-url", H2_URL));

        assertThat(properties).containsEntry("hibernate.dialect", "org.hibernate.dialect.H2Dialect");
    }

    @Test
    void urlSchemeMatchIsCaseInsensitive() {
        // JDBC URLs are not case-normalised anywhere on the way in; a config file carrying
        // JDBC:PostgreSQL: is unusual but legal, and failing startup over its casing would be
        // an absurd way to meet the caller.
        Map<String, Object> properties = customize(new MockEnvironment()
                .withProperty("exeris.runtime.persistence.jdbc-url", "JDBC:POSTGRESQL://db:5432/app"));

        assertThat(properties).containsEntry("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect");
    }

    // =========================================================================
    // Never overrule an application that has already spoken
    // =========================================================================

    @Test
    void explicitHibernateDialectProperty_isLeftAlone() {
        Map<String, Object> properties = new HashMap<>();
        properties.put("hibernate.dialect", "com.example.CustomDialect");

        customizeInto(properties, new MockEnvironment()
                .withProperty("exeris.runtime.persistence.jdbc-url", PG_URL));

        assertThat(properties).containsEntry("hibernate.dialect", "com.example.CustomDialect");
    }

    @Test
    void explicitSpringDatabasePlatform_standsDownWithoutSettingTheDialect() {
        // spring.jpa.database-platform is Spring's own way of stating the dialect; it reaches
        // Hibernate through the vendor adapter rather than this map, so "the map has no dialect"
        // does NOT mean the application failed to specify one. Writing ours here would silently
        // beat a setting the application believes is in force.
        Map<String, Object> properties = customize(new MockEnvironment()
                .withProperty("exeris.runtime.persistence.jdbc-url", PG_URL)
                .withProperty("spring.jpa.database-platform", "com.example.CustomDialect"));

        assertThat(properties).doesNotContainKey("hibernate.dialect");
        assertThat(properties)
                .as("the ordering fix is still required regardless of who supplies the dialect")
                .containsEntry("hibernate.boot.allow_jdbc_metadata_access", "false");
    }

    @Test
    void explicitMetadataAccessSetting_isLeftAlone() {
        // An application that supplies its own DataSource alongside the compat bridge may legitimately
        // want the probe on.
        Map<String, Object> properties = new HashMap<>();
        properties.put("hibernate.boot.allow_jdbc_metadata_access", "true");

        customizeInto(properties, new MockEnvironment()
                .withProperty("exeris.runtime.persistence.jdbc-url", PG_URL));

        assertThat(properties).containsEntry("hibernate.boot.allow_jdbc_metadata_access", "true");
    }

    // =========================================================================
    // Refusing to guess
    // =========================================================================

    @Test
    void unknownDatabase_failsWithAMessageNamingTheProperty() {
        assertThatThrownBy(() -> customize(new MockEnvironment()
                .withProperty("exeris.runtime.persistence.jdbc-url", "jdbc:oracle:thin:@db:1521:app")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("jdbc:oracle:thin:@db:1521:app")
                .hasMessageContaining("spring.jpa.database-platform");
    }

    @Test
    void missingJdbcUrl_failsWithAMessageNamingTheProperty() {
        assertThatThrownBy(() -> customize(new MockEnvironment()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("spring.jpa.database-platform");
    }

    @Test
    void unknownDatabase_isNotAFailureWhenTheApplicationSuppliedTheDialect() {
        // Non-vacuity guard for the two failures above: the refusal is specifically about *deriving*
        // a dialect nobody stated. An Oracle deployment that names its own dialect is fine, and must
        // not be blocked by this class.
        assertThatCode(() -> customize(new MockEnvironment()
                .withProperty("exeris.runtime.persistence.jdbc-url", "jdbc:oracle:thin:@db:1521:app")
                .withProperty("spring.jpa.database-platform", "org.hibernate.dialect.OracleDialect")))
                .doesNotThrowAnyException();
    }

    // =========================================================================

    private static Map<String, Object> customize(MockEnvironment environment) {
        Map<String, Object> properties = new HashMap<>();
        customizeInto(properties, environment);
        return properties;
    }

    private static void customizeInto(Map<String, Object> properties, MockEnvironment environment) {
        new ExerisHibernateBootstrapCustomizer(environment).customize(properties);
    }
}
