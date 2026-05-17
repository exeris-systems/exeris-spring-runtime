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
 * Pure-Mode classpath isolation guard for the graph module. Mirrors the same checks that ship
 * with the other runtime modules: no servlet, no Netty, no Reactor, no WebFlux server
 * abstractions, no DispatcherServlet.
 *
 * <p>Per Phase 1 invariant #10: every Pure Mode module ships its own
 * {@code PureModeClasspathGuardTest}. The graph module is the eighth module to carry it
 * (autoconfigure / web / tx / data / actuator / events / flow / graph).
 */
class PureModeClasspathGuardTest {

    private static JavaClasses graphModuleClasses;

    @BeforeAll
    static void importGraphModuleClasses() {
        graphModuleClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("eu.exeris.spring.runtime.graph");
    }

    @Test
    void pureMode_doesNotImportServletApi() {
        ArchRule rule = noClasses()
                .should().dependOnClassesThat()
                .resideInAnyPackage("jakarta.servlet..", "javax.servlet..")
                .allowEmptyShould(true);
        rule.check(graphModuleClasses);
    }

    @Test
    void pureMode_doesNotImportNettyOrReactor() {
        ArchRule rule = noClasses()
                .should().dependOnClassesThat()
                .resideInAnyPackage("io.netty..", "reactor..")
                .allowEmptyShould(true);
        rule.check(graphModuleClasses);
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
        rule.check(graphModuleClasses);
    }

    @Test
    void pureMode_doesNotImportDispatcherServlet() {
        ArchRule rule = noClasses()
                .should().dependOnClassesThat()
                .haveFullyQualifiedName("org.springframework.web.servlet.DispatcherServlet")
                .allowEmptyShould(true);
        rule.check(graphModuleClasses);
    }
}
