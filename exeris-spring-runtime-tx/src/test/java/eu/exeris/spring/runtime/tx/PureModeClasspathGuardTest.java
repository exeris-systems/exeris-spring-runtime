/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.tx;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * ArchUnit guard tests for Pure Mode classpath isolation in the tx module.
 *
 * <p>Ensures that tx-module main classes do not depend on servlet,
 * dispatcher servlet, Netty, Reactor, or WebFlux server abstractions
 * that would violate Pure Mode runtime ownership constraints.
 */
class PureModeClasspathGuardTest {

    private static JavaClasses txModuleClasses;

    @BeforeAll
    static void importTxModuleClasses() {
        txModuleClasses = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("eu.exeris.spring.runtime.tx");
    }

    @Test
    void pureMode_doesNotImportServletApi() {
        ArchRule rule = noClasses()
                .should().dependOnClassesThat()
            .resideInAnyPackage("jakarta.servlet..", "javax.servlet..")
            .allowEmptyShould(true);

        rule.check(txModuleClasses);
    }

    @Test
    void pureMode_doesNotImportNettyOrReactor() {
        ArchRule rule = noClasses()
                .should().dependOnClassesThat()
            .resideInAnyPackage("io.netty..", "reactor..")
            .allowEmptyShould(true);

        rule.check(txModuleClasses);
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

        rule.check(txModuleClasses);
    }

    @Test
    void pureMode_doesNotImportDispatcherServlet() {
        ArchRule rule = noClasses()
                .should().dependOnClassesThat()
            .haveFullyQualifiedName("org.springframework.web.servlet.DispatcherServlet")
            .allowEmptyShould(true);

        rule.check(txModuleClasses);
    }
}
