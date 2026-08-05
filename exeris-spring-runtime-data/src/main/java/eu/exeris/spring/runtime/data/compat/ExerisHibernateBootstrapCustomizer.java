/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.data.compat;

import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.core.env.Environment;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;

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
 * probe would otherwise have discovered. Until now the application had to know that, and
 * {@code kernel-integration-seams.md} called it "the canonical compat-datasource configuration".
 * That was accurate but it is the wrong place for the knowledge: the ordering constraint is a
 * property of this runtime, not of the application's persistence code. Compatibility Mode aims at
 * drop-in — add the dependency, maybe change configuration — and "also set two Hibernate internals
 * you have never heard of" is not that. So the runtime sets them.
 *
 * <h2>What it does</h2>
 * <ul>
 *   <li>Sets {@code hibernate.boot.allow_jdbc_metadata_access=false}. Database-independent, and the
 *       actual ordering fix.</li>
 *   <li>Sets {@code hibernate.dialect}, derived from {@code exeris.runtime.persistence.jdbc-url} —
 *       the same URL the kernel pool is configured from, so the two cannot disagree.</li>
 * </ul>
 *
 * <h2>What it never does</h2>
 * <p>Overrides an application that has already spoken. An explicit
 * {@code spring.jpa.properties.hibernate.dialect}, an explicit
 * {@code spring.jpa.database-platform}, or an explicit
 * {@code spring.jpa.properties.hibernate.boot.allow_jdbc_metadata_access} all win untouched. The
 * runtime fills a gap; it does not take the decision away.
 *
 * <h2>Unrecognised URLs fail startup</h2>
 * <p>Only PostgreSQL and H2 are derived, because those are what the Community persistence engine
 * actually supports. For anything else this refuses to guess and fails the context with a message
 * naming {@code spring.jpa.database-platform}. Guessing a dialect is worse than not setting one:
 * Hibernate would run against subtly wrong SQL generation rather than failing, and the symptom would
 * surface later as a query defect. Failing here costs a startup and names the fix; the alternative
 * costs a debugging session at a call site that looks correct.
 *
 * <h2>Mode</h2>
 * <p>Compatibility Mode only — reached solely through the opt-in compat datasource
 * ({@code exeris.runtime.data.compat-datasource.enabled=true}). It also carries no {@code org.hibernate}
 * import: the settings are plain property keys and the dialect names are strings, so this class does
 * not make JPA a first-class path in this module (ADR-017).
 *
 * @since 0.7.0
 */
public final class ExerisHibernateBootstrapCustomizer implements HibernatePropertiesCustomizer {

    /** Hibernate's metadata probe switch. Disabling it is what defers the connection. */
    static final String METADATA_ACCESS_KEY = "hibernate.boot.allow_jdbc_metadata_access";

    /** Hibernate's dialect key, as it appears in the customized property map. */
    static final String DIALECT_KEY = "hibernate.dialect";

    /** Spring's own way of stating the dialect; if set, this class stands down. */
    static final String SPRING_DATABASE_PLATFORM = "spring.jpa.database-platform";

    private static final String JDBC_URL_PROPERTY = "exeris.runtime.persistence.jdbc-url";

    private static final String POSTGRESQL_DIALECT = "org.hibernate.dialect.PostgreSQLDialect";
    private static final String H2_DIALECT = "org.hibernate.dialect.H2Dialect";

    private static final System.Logger LOGGER =
            System.getLogger(ExerisHibernateBootstrapCustomizer.class.getName());

    private final Environment environment;

    public ExerisHibernateBootstrapCustomizer(Environment environment) {
        this.environment = Objects.requireNonNull(environment, "environment must not be null");
    }

    @Override
    public void customize(Map<String, Object> hibernateProperties) {
        applyMetadataAccess(hibernateProperties);
        applyDialect(hibernateProperties);
    }

    private void applyMetadataAccess(Map<String, Object> hibernateProperties) {
        if (hibernateProperties.containsKey(METADATA_ACCESS_KEY)) {
            // The application asked for something specific — including, possibly, leaving the probe
            // on because it supplies its own DataSource. Not ours to overrule.
            return;
        }
        hibernateProperties.put(METADATA_ACCESS_KEY, "false");
        LOGGER.log(System.Logger.Level.DEBUG,
                () -> "Exeris compat datasource: disabled " + METADATA_ACCESS_KEY
                        + " so EntityManagerFactory construction does not open a connection before "
                        + "the kernel has booted");
    }

    private void applyDialect(Map<String, Object> hibernateProperties) {
        if (hibernateProperties.containsKey(DIALECT_KEY)
                || environment.getProperty(SPRING_DATABASE_PLATFORM) != null) {
            return;
        }

        String jdbcUrl = environment.getProperty(JDBC_URL_PROPERTY);
        String dialect = dialectFor(jdbcUrl);
        if (dialect == null) {
            throw new IllegalStateException(buildUnknownDialectMessage(jdbcUrl));
        }

        hibernateProperties.put(DIALECT_KEY, dialect);
        LOGGER.log(System.Logger.Level.DEBUG,
                () -> "Exeris compat datasource: derived " + DIALECT_KEY + '=' + dialect
                        + " from " + JDBC_URL_PROPERTY);
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
