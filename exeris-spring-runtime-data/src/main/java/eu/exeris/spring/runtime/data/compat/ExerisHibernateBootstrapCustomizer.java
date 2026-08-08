/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.data.compat;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.util.ClassUtils;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import eu.exeris.spring.boot.autoconfigure.compat.CompatibilityMode;

/**
 * Removes the last piece of Exeris-specific configuration a brownfield JPA application had to write
 * by hand: the Hibernate bootstrap settings that keep {@code EntityManagerFactory} construction from
 * reaching for a JDBC connection before the kernel exists.
 *
 * <h2>The ordering problem this closes</h2>
 * <p>Bootstrap order is invariant: Spring {@code refresh()} completes, <em>then</em>
 * {@code ExerisRuntimeLifecycle.start()} boots the kernel. {@code EntityManagerFactory} is built
 * during {@code refresh()}. By default Hibernate opens a connection at that point to probe database
 * metadata and infer its dialect — and {@link ExerisDataSource} cannot serve one, because the kernel
 * persistence engine it delegates to has not been created yet. The application fails to start, with
 * an error that points at Hibernate and says nothing about runtime ownership.
 *
 * <p>The fix is two Hibernate settings: switch the metadata probe off, and state the dialect that the
 * probe would otherwise have discovered. Until 0.7.0 the application had to know that, and
 * {@code kernel-integration-seams.md} called it "the canonical compat-datasource configuration".
 * That was accurate but it is the wrong place for the knowledge: the ordering constraint is a
 * property of this runtime, not of the application's persistence code.
 *
 * <h2>Why it contributes properties instead of implementing {@code HibernatePropertiesCustomizer}</h2>
 * <p>It did implement that interface when it first landed. Spring Boot 4 moved it — from
 * {@code org.springframework.boot.autoconfigure.orm.jpa} in {@code spring-boot-autoconfigure} to
 * {@code org.springframework.boot.hibernate.autoconfigure} in a new {@code spring-boot-hibernate}
 * artifact — and ADR-028 obligation 1 requires one source tree to compile under both matrix profiles,
 * so naming either package breaks the other line.
 *
 * <p>Rather than bridge the relocated interface, this contributes the same two settings as ordinary
 * {@code spring.jpa.properties.*} entries through a {@link BeanFactoryPostProcessor}. Boot binds those
 * into the very map {@code HibernatePropertiesCustomizer} would have handed us, so the effect is
 * identical while nothing version-specific appears on the compile path. It also runs before any
 * singleton — and therefore before {@code JpaProperties} is bound and the {@code EntityManagerFactory}
 * is built — which is the ordering the customizer interface was giving us anyway.
 *
 * <p>A side benefit: "never overrule an application that has already spoken" is now enforced by
 * property-source precedence rather than by an explicit key check. The contributed source is added
 * <em>last</em>, so any {@code spring.jpa.properties.hibernate.*} the application sets — from a
 * properties file, a profile, an environment variable, anywhere — wins automatically.
 *
 * <h2>Unrecognised URLs fail startup</h2>
 * <p>Only PostgreSQL and H2 are derived, because those are what the Community persistence engine
 * actually supports. For anything else this refuses to guess and fails the context with a message
 * naming {@code spring.jpa.database-platform}. Guessing a dialect is worse than not setting one:
 * Hibernate would run against subtly wrong SQL generation rather than failing, and the symptom would
 * surface later as a query defect. Failing here costs a startup and names the fix; the alternative
 * costs a debugging session at a call site that looks correct.
 *
 * <p>The refusal is gated on Hibernate actually being present. Without it there is no dialect to
 * state and no metadata probe to disable, so an application using the compat datasource without JPA
 * is unaffected.
 *
 * <h2>Mode</h2>
 * <p>Compatibility Mode only — registered solely through the opt-in compat datasource
 * ({@code exeris.runtime.data.compat-datasource.enabled=true}). Carries no {@code org.hibernate} or
 * Spring-Boot-JPA import, so it does not make JPA a first-class path in this module (ADR-017).
 *
 * @since 0.7.0
 */
@CompatibilityMode
public final class ExerisHibernateBootstrapCustomizer implements BeanFactoryPostProcessor, EnvironmentAware {

    /** Hibernate's metadata probe switch. Disabling it is what defers the connection. */
    static final String METADATA_ACCESS_KEY = "hibernate.boot.allow_jdbc_metadata_access";

    /** Hibernate's dialect key, as Spring Boot binds it. */
    static final String DIALECT_KEY = "hibernate.dialect";

    /** Spring's own way of stating the dialect; if set, the dialect is left alone. */
    static final String SPRING_DATABASE_PLATFORM = "spring.jpa.database-platform";

    /** Prefix Spring Boot binds into the Hibernate property map. */
    static final String JPA_PROPERTIES_PREFIX = "spring.jpa.properties.";

    static final String PROPERTY_SOURCE_NAME = "exerisCompatHibernateBootstrap";

    private static final String JDBC_URL_PROPERTY = "exeris.runtime.persistence.jdbc-url";
    private static final String HIBERNATE_MARKER_CLASS = "org.hibernate.SessionFactory";

    private static final String POSTGRESQL_DIALECT = "org.hibernate.dialect.PostgreSQLDialect";
    private static final String H2_DIALECT = "org.hibernate.dialect.H2Dialect";

    private static final System.Logger LOGGER =
            System.getLogger(ExerisHibernateBootstrapCustomizer.class.getName());

    private final String hibernateMarkerClass;
    private Environment environment;

    public ExerisHibernateBootstrapCustomizer() {
        this(HIBERNATE_MARKER_CLASS);
    }

    /**
     * Package-private seam naming the class whose presence means "Hibernate is in use".
     *
     * <p>It exists so a test can drive the contribute path without Hibernate on this module's test
     * classpath, which ADR-017 deliberately keeps off it — a test passes the name of a class that is
     * present. Without the seam the only reachable branch in {@link #postProcessBeanFactory} is the
     * stand-down, and the property-source mutation, which is the whole point of the class, would go
     * untested.
     */
    ExerisHibernateBootstrapCustomizer(String hibernateMarkerClass) {
        this.hibernateMarkerClass = hibernateMarkerClass;
    }

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
        if (!(environment instanceof ConfigurableEnvironment configurable) || !hibernatePresent()) {
            return;
        }
        // addLast: lowest precedence, so anything the application states itself wins.
        Map<String, Object> contributed = buildContribution(configurable);
        configurable.getPropertySources()
                .addLast(new MapPropertySource(PROPERTY_SOURCE_NAME, contributed));
    }

    /**
     * Builds the settings to contribute. Package-private and static so the decision can be tested
     * directly: {@link #postProcessBeanFactory} is gated on Hibernate being on the classpath, and
     * Hibernate is deliberately absent from this module's test classpath (ADR-017 — JPA is not a
     * first-class path here), which would otherwise make every assertion about the contribution
     * vacuous.
     */
    static Map<String, Object> buildContribution(Environment configurable) {
        Map<String, Object> contributed = new LinkedHashMap<>();
        contributed.put(JPA_PROPERTIES_PREFIX + METADATA_ACCESS_KEY, "false");

        if (configurable.getProperty(SPRING_DATABASE_PLATFORM) != null
                || configurable.getProperty(JPA_PROPERTIES_PREFIX + DIALECT_KEY) != null) {
            // The application stated the dialect. The ordering fix still applies — it is orthogonal
            // to who supplies the dialect — but we add nothing further.
            return contributed;
        }

        String jdbcUrl = configurable.getProperty(JDBC_URL_PROPERTY);
        String dialect = dialectFor(jdbcUrl);
        if (dialect == null) {
            throw new IllegalStateException(buildUnknownDialectMessage(jdbcUrl));
        }
        contributed.put(JPA_PROPERTIES_PREFIX + DIALECT_KEY, dialect);
        LOGGER.log(System.Logger.Level.DEBUG,
                () -> "Exeris compat datasource: derived " + DIALECT_KEY + '=' + dialect
                        + " from " + JDBC_URL_PROPERTY);
        return contributed;
    }

    private boolean hibernatePresent() {
        return ClassUtils.isPresent(
                hibernateMarkerClass, ExerisHibernateBootstrapCustomizer.class.getClassLoader());
    }

    /**
     * Maps a JDBC URL onto a Hibernate dialect, or {@code null} when the URL is absent or names a
     * database this runtime will not guess for.
     */
    static String dialectFor(String jdbcUrl) {
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            return null;
        }
        String normalized = jdbcUrl.toLowerCase(Locale.ROOT);
        if (normalized.startsWith("jdbc:postgresql:")) {
            return POSTGRESQL_DIALECT;
        }
        if (normalized.startsWith("jdbc:h2:")) {
            return H2_DIALECT;
        }
        return null;
    }

    private static String buildUnknownDialectMessage(String jdbcUrl) {
        String urlDescription = jdbcUrl == null || jdbcUrl.isBlank()
                ? "no " + JDBC_URL_PROPERTY + " is configured"
                : "the configured " + JDBC_URL_PROPERTY + " ('" + jdbcUrl + "') names a database this "
                        + "runtime does not derive a dialect for";

        return """
                The Exeris compat datasource cannot determine the Hibernate dialect: %s.

                Why this is needed: Exeris owns the runtime, so the kernel persistence engine does not \
                exist until after Spring refresh() completes — but EntityManagerFactory is built during \
                refresh(). Hibernate's usual dialect discovery opens a JDBC connection at that moment, \
                which cannot be served yet. The runtime therefore disables the metadata probe and must \
                supply the dialect instead.

                Set the dialect explicitly:

                    spring.jpa.database-platform=org.hibernate.dialect.<YourDialect>

                Dialects are derived automatically only for PostgreSQL and H2, which are what the \
                Community persistence engine supports. This does not guess for other databases: a \
                wrong dialect does not fail, it generates subtly wrong SQL, and the symptom appears \
                later at a call site that looks correct.
                """.formatted(urlDescription);
    }
}
