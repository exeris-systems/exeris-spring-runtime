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
 * Pure-Mode classpath isolation guard for the events module. Mirrors the same checks
 * that ship with the other runtime modules: no servlet, no Netty, no Reactor, no
 * WebFlux server abstractions, no DispatcherServlet.
 */
class PureModeClasspathGuardTest {

    private static JavaClasses eventsModuleClasses;

    @BeforeAll
    static void importEventsModuleClasses() {
        eventsModuleClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("eu.exeris.spring.runtime.events");
    }

    @Test
    void pureMode_doesNotImportServletApi() {
        ArchRule rule = noClasses()
                .should().dependOnClassesThat()
                .resideInAnyPackage("jakarta.servlet..", "javax.servlet..")
                .allowEmptyShould(true);
        rule.check(eventsModuleClasses);
    }

    @Test
    void pureMode_doesNotImportNettyOrReactor() {
        ArchRule rule = noClasses()
                .should().dependOnClassesThat()
                .resideInAnyPackage("io.netty..", "reactor..")
                .allowEmptyShould(true);
        rule.check(eventsModuleClasses);
    }

    @Test
    void pureMode_doesNotImportWebFluxServerAbstractions() {
        ArchRule rule = noClasses()
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                        "org.springframework.web.server..",
                        "org.springframework.web.reactive..",
                        "org.springframework.http.server.reactive..")
                .allowEmptyShould(true);
        rule.check(eventsModuleClasses);
    }

    @Test
    void pureMode_doesNotImportDispatcherServlet() {
        ArchRule rule = noClasses()
                .should().dependOnClassesThat()
                .haveFullyQualifiedName("org.springframework.web.servlet.DispatcherServlet")
                .allowEmptyShould(true);
        rule.check(eventsModuleClasses);
    }
}
