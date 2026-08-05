/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.data.compat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.mock.env.MockEnvironment;

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
 *
 * <p>Most assertions drive {@code buildContribution} directly rather than
 * {@code postProcessBeanFactory}: the latter is gated on Hibernate being on the classpath, and
 * Hibernate is deliberately absent here (ADR-017 keeps JPA off this module's test classpath). The
 * gate itself is covered by {@link #contributesNothing_whenHibernateIsAbsent()}, which is
 * non-vacuous precisely because of that absence.
 */
class ExerisHibernateBootstrapCustomizerTest {

    private static final String PG_URL = "jdbc:postgresql://localhost:5432/app";
    private static final String H2_URL = "jdbc:h2:mem:app;DB_CLOSE_DELAY=-1";

    private static final String METADATA_KEY =
            "spring.jpa.properties.hibernate.boot.allow_jdbc_metadata_access";
    private static final String DIALECT_KEY = "spring.jpa.properties.hibernate.dialect";

    /**
     * A marker class that is always present, standing in for "Hibernate is on the classpath". Only
     * presence is ever checked, never the type itself, so any resolvable name works.
     */
    private static final String MARKER_PRESENT = "java.lang.String";

    @Test
    void disablesTheMetadataProbe_andDerivesTheDialect() {
        Map<String, Object> contributed = ExerisHibernateBootstrapCustomizer.buildContribution(
                new MockEnvironment().withProperty("exeris.runtime.persistence.jdbc-url", PG_URL));

        assertThat(contributed)
                .containsEntry(METADATA_KEY, "false")
                .containsEntry(DIALECT_KEY, "org.hibernate.dialect.PostgreSQLDialect");
    }

    @Test
    void derivesTheH2Dialect() {
        Map<String, Object> contributed = ExerisHibernateBootstrapCustomizer.buildContribution(
                new MockEnvironment().withProperty("exeris.runtime.persistence.jdbc-url", H2_URL));

        assertThat(contributed).containsEntry(DIALECT_KEY, "org.hibernate.dialect.H2Dialect");
    }

    @Test
    void urlSchemeMatchIsCaseInsensitive() {
        // JDBC URLs are not case-normalised anywhere on the way in; failing startup over the casing
        // of a legal URL would be an absurd way to meet the caller.
        Map<String, Object> contributed = ExerisHibernateBootstrapCustomizer.buildContribution(
                new MockEnvironment()
                        .withProperty("exeris.runtime.persistence.jdbc-url", "JDBC:POSTGRESQL://db:5432/app"));

        assertThat(contributed).containsEntry(DIALECT_KEY, "org.hibernate.dialect.PostgreSQLDialect");
    }

    // =========================================================================
    // Never overrule an application that has already spoken
    // =========================================================================

    @Test
    void explicitSpringDatabasePlatform_standsDownWithoutSettingTheDialect() {
        // spring.jpa.database-platform reaches Hibernate through the vendor adapter, not through the
        // property map, so "no dialect among the JPA properties" does NOT mean the application failed
        // to specify one. Contributing ours would silently beat a setting it believes is in force.
        Map<String, Object> contributed = ExerisHibernateBootstrapCustomizer.buildContribution(
                new MockEnvironment()
                        .withProperty("exeris.runtime.persistence.jdbc-url", PG_URL)
                        .withProperty("spring.jpa.database-platform", "com.example.CustomDialect"));

        assertThat(contributed).doesNotContainKey(DIALECT_KEY);
        assertThat(contributed)
                .as("the ordering fix is required regardless of who supplies the dialect")
                .containsEntry(METADATA_KEY, "false");
    }

    @Test
    void explicitJpaPropertiesDialect_standsDown() {
        Map<String, Object> contributed = ExerisHibernateBootstrapCustomizer.buildContribution(
                new MockEnvironment()
                        .withProperty("exeris.runtime.persistence.jdbc-url", PG_URL)
                        .withProperty(DIALECT_KEY, "com.example.CustomDialect"));

        assertThat(contributed).doesNotContainKey(DIALECT_KEY);
    }

    @Test
    void contributedSourceHasLowestPrecedence_soApplicationValuesWin() {
        // The "never overrule" guarantee is enforced by property-source ordering, not by a key check,
        // so it must hold for a value the application set anywhere — including the metadata switch,
        // which buildContribution always emits.
        MockEnvironment environment = new MockEnvironment()
                .withProperty("exeris.runtime.persistence.jdbc-url", PG_URL)
                .withProperty(METADATA_KEY, "true");

        ExerisHibernateBootstrapCustomizer customizer = new ExerisHibernateBootstrapCustomizer();
        customizer.setEnvironment(environment);
        environment.getPropertySources().addLast(new org.springframework.core.env.MapPropertySource(
                ExerisHibernateBootstrapCustomizer.PROPERTY_SOURCE_NAME,
                ExerisHibernateBootstrapCustomizer.buildContribution(environment)));

        assertThat(environment.getProperty(METADATA_KEY))
                .as("an application-set value must survive our lower-precedence contribution")
                .isEqualTo("true");
    }

    // =========================================================================
    // Refusing to guess
    // =========================================================================

    @Test
    void unknownDatabase_failsWithAMessageNamingTheProperty() {
        assertThatThrownBy(() -> ExerisHibernateBootstrapCustomizer.buildContribution(
                new MockEnvironment()
                        .withProperty("exeris.runtime.persistence.jdbc-url", "jdbc:oracle:thin:@db:1521:app")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("jdbc:oracle:thin:@db:1521:app")
                .hasMessageContaining("spring.jpa.database-platform");
    }

    @Test
    void missingJdbcUrl_failsWithAMessageNamingTheProperty() {
        assertThatThrownBy(() -> ExerisHibernateBootstrapCustomizer.buildContribution(new MockEnvironment()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("spring.jpa.database-platform");
    }

    @Test
    void unknownDatabase_isNotAFailureWhenTheApplicationSuppliedTheDialect() {
        // Non-vacuity guard for the two failures above: the refusal is about *deriving* a dialect
        // nobody stated. An Oracle deployment naming its own dialect must not be blocked.
        assertThatCode(() -> ExerisHibernateBootstrapCustomizer.buildContribution(
                new MockEnvironment()
                        .withProperty("exeris.runtime.persistence.jdbc-url", "jdbc:oracle:thin:@db:1521:app")
                        .withProperty("spring.jpa.database-platform", "org.hibernate.dialect.OracleDialect")))
                .doesNotThrowAnyException();
    }

    // =========================================================================
    // The Hibernate gate
    // =========================================================================

    @Test
    void contributesThePropertySource_whenHibernateIsPresent() {
        // The success path — the property-source mutation this class exists for. Driven through the
        // classloader seam because Hibernate is deliberately absent from this module's test classpath
        // (ADR-017), which would otherwise leave the only reachable branch the stand-down.
        MockEnvironment environment = new MockEnvironment()
                .withProperty("exeris.runtime.persistence.jdbc-url", PG_URL);

        ExerisHibernateBootstrapCustomizer customizer =
                new ExerisHibernateBootstrapCustomizer(MARKER_PRESENT);
        customizer.setEnvironment(environment);
        customizer.postProcessBeanFactory(new DefaultListableBeanFactory());

        assertThat(environment.getPropertySources()
                .contains(ExerisHibernateBootstrapCustomizer.PROPERTY_SOURCE_NAME)).isTrue();
        assertThat(environment.getProperty(METADATA_KEY)).isEqualTo("false");
        assertThat(environment.getProperty(DIALECT_KEY)).isEqualTo("org.hibernate.dialect.PostgreSQLDialect");
    }

    @Test
    void contributesNothing_whenTheEnvironmentIsNotConfigurable() {
        // EnvironmentAware hands us an Environment; only a ConfigurableEnvironment can take a new
        // property source. A non-configurable one is not an error, just nothing to do.
        ExerisHibernateBootstrapCustomizer customizer =
                new ExerisHibernateBootstrapCustomizer(MARKER_PRESENT);
        // A bare Environment (not Configurable) via proxy — implementing the interface by hand
        // would be twenty stub methods for one behavioural bit.
        org.springframework.core.env.Environment plain =
                (org.springframework.core.env.Environment) java.lang.reflect.Proxy.newProxyInstance(
                        getClass().getClassLoader(),
                        new Class<?>[] { org.springframework.core.env.Environment.class },
                        (proxy, method, args) -> switch (method.getName()) {
                            case "toString" -> "plain-environment";
                            case "hashCode" -> System.identityHashCode(proxy);
                            case "equals" -> args != null && args.length == 1 && proxy == args[0];
                            default -> null;
                        });
        customizer.setEnvironment(plain);

        assertThatCode(() -> customizer.postProcessBeanFactory(new DefaultListableBeanFactory()))
                .doesNotThrowAnyException();
    }

    @Test
    void contributesNothing_whenHibernateIsAbsent() {
        // Hibernate is not on this module's test classpath by design, so this exercises the real
        // gate rather than a simulated one: an application using the compat datasource without JPA
        // must be untouched — including the unknown-dialect failure, which must not fire.
        MockEnvironment environment = new MockEnvironment()
                .withProperty("exeris.runtime.persistence.jdbc-url", "jdbc:oracle:thin:@db:1521:app");

        ExerisHibernateBootstrapCustomizer customizer = new ExerisHibernateBootstrapCustomizer();
        customizer.setEnvironment(environment);

        assertThatCode(() -> customizer.postProcessBeanFactory(new DefaultListableBeanFactory()))
                .doesNotThrowAnyException();
        assertThat(environment.getPropertySources()
                .contains(ExerisHibernateBootstrapCustomizer.PROPERTY_SOURCE_NAME)).isFalse();
    }
}
