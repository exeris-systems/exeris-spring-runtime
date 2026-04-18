/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.boot.autoconfigure;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * ArchUnit guard tests for Pure Mode classpath isolation in autoconfigure module.
 *
 * <p>Ensures that autoconfigure module does not depend on servlet,
 * dispatcher servlet, netty, reactor, or webflux server abstractions
 * that would violate Pure Mode runtime ownership constraints.
 */
class PureModeClasspathGuardTest {

    private static JavaClasses autoconfigureModuleClasses;

    @BeforeAll
    static void importAutoconfigureModuleClasses() {
        autoconfigureModuleClasses = new ClassFileImporter()
            .importClasses(
                ExerisRuntimeAutoConfiguration.class,
                ExerisRuntimeLifecycle.class,
                ExerisRuntimeProperties.class,
                ExerisSpringConfigProvider.class
            );
    }

    @Test
    void pureMode_doesNotImportServletApi() {
        ArchRule rule = noClasses()
                .should().dependOnClassesThat()
            .resideInAnyPackage("jakarta.servlet..", "javax.servlet..")
            .allowEmptyShould(true);

        rule.check(autoconfigureModuleClasses);
    }

    @Test
    void pureMode_doesNotImportNettyOrReactor() {
        ArchRule rule = noClasses()
                .should().dependOnClassesThat()
            .resideInAnyPackage("io.netty..", "reactor..")
            .allowEmptyShould(true);

        rule.check(autoconfigureModuleClasses);
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

        rule.check(autoconfigureModuleClasses);
    }

    @Test
    void pureMode_doesNotImportDispatcherServlet() {
        ArchRule rule = noClasses()
                .should().dependOnClassesThat()
            .haveFullyQualifiedName("org.springframework.web.servlet.DispatcherServlet")
            .allowEmptyShould(true);

        rule.check(autoconfigureModuleClasses);
    }
}
