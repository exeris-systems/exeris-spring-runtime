/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.web;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import eu.exeris.spring.runtime.web.autoconfigure.ExerisWebAutoConfiguration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class PureModeClasspathGuardTest {

    private static JavaClasses webModuleClasses;

    @BeforeAll
    static void importWebModuleClasses() {
        webModuleClasses = new ClassFileImporter()
            .importClasses(
                ExerisHttpDispatcher.class,
                ExerisErrorMapper.class,
                ExerisRequestHandler.class,
                ExerisRoute.class,
                ExerisRouteRegistry.class,
                ExerisServerRequest.class,
                ExerisServerResponse.class,
                ExerisWebAutoConfiguration.class
            );
    }

    @Test
    void pureMode_doesNotImportServletApi() {
        ArchRule rule = noClasses()
                .should().dependOnClassesThat()
            .resideInAnyPackage("jakarta.servlet..", "javax.servlet..")
            .allowEmptyShould(true);

        rule.check(webModuleClasses);
    }

    @Test
    void pureMode_doesNotImportNettyOrReactor() {
        ArchRule rule = noClasses()
                .should().dependOnClassesThat()
            .resideInAnyPackage("io.netty..", "reactor..")
            .allowEmptyShould(true);

        rule.check(webModuleClasses);
    }

    @Test
    void pureMode_doesNotImportDispatcherServlet() {
        ArchRule rule = noClasses()
                .should().dependOnClassesThat()
            .haveFullyQualifiedName("org.springframework.web.servlet.DispatcherServlet")
            .allowEmptyShould(true);

        rule.check(webModuleClasses);
    }
}