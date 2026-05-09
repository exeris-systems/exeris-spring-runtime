/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.events;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Architecture guards for {@code exeris-spring-runtime-events}.
 *
 * <p>Pins the kernel-bus / Spring-bus separation: the events module must never wire
 * Spring's {@code ApplicationEventPublisher} or {@code org.springframework.context.event}
 * machinery into the Exeris {@code EventBus}, and must never pull in HTTP / transaction
 * / persistence concerns that belong in other modules.
 */
class EventModuleBoundaryTest {

    private static JavaClasses eventsModuleClasses;

    @BeforeAll
    static void importEventsModuleClasses() {
        eventsModuleClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("eu.exeris.spring.runtime.events");
    }

    @Test
    void doesNotDependOnSpringApplicationEventPublisher() {
        ArchRule rule = noClasses()
                .should().dependOnClassesThat()
                .haveFullyQualifiedName("org.springframework.context.ApplicationEventPublisher")
                .allowEmptyShould(true);
        rule.check(eventsModuleClasses);
    }

    @Test
    void doesNotDependOnSpringContextEventPackage() {
        ArchRule rule = noClasses()
                .should().dependOnClassesThat()
                .resideInAPackage("org.springframework.context.event..")
                .allowEmptyShould(true);
        rule.check(eventsModuleClasses);
    }

    @Test
    void doesNotImportHttpOrServletPackages() {
        ArchRule rule = noClasses()
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                        "jakarta.servlet..",
                        "javax.servlet..",
                        "eu.exeris.kernel.spi.http..",
                        "org.springframework.web..")
                .allowEmptyShould(true);
        rule.check(eventsModuleClasses);
    }

    @Test
    void doesNotImportTransactionOrPersistencePackages() {
        ArchRule rule = noClasses()
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                        "org.springframework.transaction..",
                        "javax.sql..",
                        "java.sql..",
                        "eu.exeris.kernel.spi.persistence..")
                .allowEmptyShould(true);
        rule.check(eventsModuleClasses);
    }

    @Test
    void doesNotImportJpaOrHibernate() {
        ArchRule rule = noClasses()
                .should().dependOnClassesThat()
                .resideInAnyPackage("jakarta.persistence..", "org.hibernate..")
                .allowEmptyShould(true);
        rule.check(eventsModuleClasses);
    }
}
