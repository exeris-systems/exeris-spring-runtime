/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.graph;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Module-boundary architecture guards for {@code exeris-spring-runtime-graph} per ADR-030
 * obligation 6.
 *
 * <p>Each test is merge-blocking — a violation indicates a banned cross-module edge or a
 * forbidden coupling per the module-boundaries entry added in Step 1
 * ({@code docs/architecture/module-boundaries.md} §"exeris-spring-runtime-graph"). The
 * guards collectively enforce the "kernel-independent Spring-side seam" ADR-030 contract:
 * the graph module imports only kernel SPI types and Spring autoconfig/context primitives;
 * concrete drivers stay test-scope.
 *
 * <h2>Banned edges (merge-blocking)</h2>
 *
 * <ul>
 *   <li>Spring Data ({@code org.springframework.data..}) — ADR-030 explicitly excludes Spring
 *       Data Neo4j compatibility; {@code @ExerisGraphQuery} is a thin declarative wrapper,
 *       not a repository surface.</li>
 *   <li>Spring web ({@code org.springframework.web..}) — graph operations are not request-
 *       path-bound; handlers may inject the template but the template carries no
 *       request-context coupling.</li>
 *   <li>Kernel community drivers ({@code eu.exeris.kernel.community..}) on production scope
 *       — concrete PGQ/Bolt drivers stay test-scope only per the module-boundaries entry.</li>
 *   <li>HTTP / transport / persistence packages — same rationale as Phase 4B
 *       {@code FlowModuleBoundaryTest}; graph is structured-data machinery, not a request
 *       handler, not a transaction manager, not a persistence layer.</li>
 *   <li>Spring's {@code ApplicationEventPublisher} / {@code @Async} machinery — graph
 *       operations are direct calls into the kernel SPI; there is no event bridging or
 *       async fan-out from this module.</li>
 * </ul>
 */
class GraphModuleBoundaryTest {

    private static JavaClasses graphModuleClasses;

    @BeforeAll
    static void importGraphModuleClasses() {
        graphModuleClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("eu.exeris.spring.runtime.graph");
    }

    /**
     * ADR-030 §"What is NOT in scope" + module-boundaries.md prohibited-edge row:
     * {@code @ExerisGraphQuery} is a thin declarative wrapper, not a Spring Data repository.
     * A future PR adding Spring Data Graph repository support is an ADR-030-amendment-worthy
     * decision, not a silent shim.
     */
    @Test
    void doesNotImportSpringData() {
        ArchRule rule = noClasses()
                .should().dependOnClassesThat()
                .resideInAPackage("org.springframework.data..")
                .allowEmptyShould(true);
        rule.check(graphModuleClasses);
    }

    /**
     * ADR-030 obligation 6 + module-boundaries.md: graph operations are independent of the
     * HTTP request path. Handlers can inject the template but the template itself does not
     * import {@code org.springframework.web..} types.
     */
    @Test
    void doesNotImportSpringWeb() {
        ArchRule rule = noClasses()
                .should().dependOnClassesThat()
                .resideInAPackage("org.springframework.web..")
                .allowEmptyShould(true);
        rule.check(graphModuleClasses);
    }

    /**
     * ADR-030 obligation 6: concrete drivers (PostgreSQL PGQ, Neo4j Bolt, Memgraph Bolt) stay
     * test-scope only; production classpath consumes only kernel SPI.
     */
    @Test
    void productionScopeDoesNotImportKernelCommunity() {
        ArchRule rule = noClasses()
                .should().dependOnClassesThat()
                .resideInAPackage("eu.exeris.kernel.community..")
                .allowEmptyShould(true);
        rule.check(graphModuleClasses);
    }

    /**
     * Graph is not a request handler — no servlet, no kernel HTTP SPI, no Spring web server
     * abstractions.
     */
    @Test
    void doesNotImportHttpOrServletPackages() {
        ArchRule rule = noClasses()
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                        "jakarta.servlet..",
                        "javax.servlet..",
                        "eu.exeris.kernel.spi.http..")
                .allowEmptyShould(true);
        rule.check(graphModuleClasses);
    }

    /**
     * Graph session transactions are kernel-local (per ADR-030 §"What is NOT in scope" —
     * cross-resource transactions are not bridged at Phase 4C). The graph module must not
     * pull in Spring's transaction package or direct JDBC types.
     */
    @Test
    void doesNotImportTransactionOrJdbcPackages() {
        ArchRule rule = noClasses()
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                        "org.springframework.transaction..",
                        "javax.sql..",
                        "java.sql..")
                .allowEmptyShould(true);
        rule.check(graphModuleClasses);
    }

    /**
     * Persistence ports — the graph engine owns its own backend connection; the Spring-side
     * seam does not pull in JPA/Hibernate or the kernel's persistence SPI.
     */
    @Test
    void doesNotImportJpaHibernateOrKernelPersistence() {
        ArchRule rule = noClasses()
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                        "jakarta.persistence..",
                        "org.hibernate..",
                        "eu.exeris.kernel.spi.persistence..")
                .allowEmptyShould(true);
        rule.check(graphModuleClasses);
    }

    /**
     * Graph operations are direct calls on the kernel SPI — no Spring async / task-executor
     * bridging and no Spring application-event bus.
     */
    @Test
    void doesNotImportSpringAsyncOrApplicationEventPublisher() {
        ArchRule rule = noClasses()
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                        "org.springframework.scheduling..",
                        "org.springframework.core.task..",
                        "org.springframework.context.event..")
                .allowEmptyShould(true);
        rule.check(graphModuleClasses);
        ArchRule appEventPublisher = noClasses()
                .should().dependOnClassesThat()
                .haveFullyQualifiedName("org.springframework.context.ApplicationEventPublisher")
                .allowEmptyShould(true);
        appEventPublisher.check(graphModuleClasses);
    }

    /**
     * Defence-in-depth against unrelated module cross-imports — the graph module must not
     * pull in {@code web} / {@code tx} / {@code data} / {@code actuator} / {@code events} /
     * {@code flow}. The dependency graph in {@code module-boundaries.md} restricts graph to
     * {@code kernel-spi + autoconfigure + spring-boot-autoconfigure + spring-context}.
     */
    @Test
    void doesNotImportOtherRuntimeModules() {
        ArchRule rule = noClasses()
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                        "eu.exeris.spring.runtime.web..",
                        "eu.exeris.spring.runtime.tx..",
                        "eu.exeris.spring.runtime.data..",
                        "eu.exeris.spring.runtime.actuator..",
                        "eu.exeris.spring.runtime.events..",
                        "eu.exeris.spring.runtime.flow..")
                .allowEmptyShould(true);
        rule.check(graphModuleClasses);
    }
}
