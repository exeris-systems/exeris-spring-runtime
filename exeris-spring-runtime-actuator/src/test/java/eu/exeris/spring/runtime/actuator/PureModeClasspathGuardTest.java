/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.actuator;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import eu.exeris.spring.boot.autoconfigure.compat.CompatibilityMode;

/**
 * ArchUnit guard tests for Pure Mode classpath isolation in the actuator module.
 *
 * <p>Ensures that actuator-module main classes do not depend on servlet,
 * dispatcher servlet, Netty, Reactor, or WebFlux server abstractions
 * that would violate Pure Mode runtime ownership constraints.
 */
class PureModeClasspathGuardTest {

    private static JavaClasses actuatorModuleClasses;

    @BeforeAll
    static void importActuatorModuleClasses() {
        actuatorModuleClasses = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("eu.exeris.spring.runtime.actuator");
    }

    @Test
    void pureMode_doesNotImportServletApi() {
        ArchRule rule = noClasses()
                .should().dependOnClassesThat()
            .resideInAnyPackage("jakarta.servlet..", "javax.servlet..")
            .allowEmptyShould(true);

        rule.check(actuatorModuleClasses);
    }

    @Test
    void pureMode_doesNotImportNettyOrReactor() {
        ArchRule rule = noClasses()
                .should().dependOnClassesThat()
            .resideInAnyPackage("io.netty..", "reactor..")
            .allowEmptyShould(true);

        rule.check(actuatorModuleClasses);
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

        rule.check(actuatorModuleClasses);
    }

    @Test
    void pureMode_doesNotImportDispatcherServlet() {
        ArchRule rule = noClasses()
                .should().dependOnClassesThat()
            .haveFullyQualifiedName("org.springframework.web.servlet.DispatcherServlet")
            .allowEmptyShould(true);

        rule.check(actuatorModuleClasses);
    }

    /**
     * ADR-011's marker must cover this module too. It moved to
     * {@code eu.exeris.spring.boot.autoconfigure.compat} precisely because {@code actuator}
     * cannot depend on {@code web}, which is where it used to live — so before the move the compat
     * actuator controller was structurally unmarkable and the grep the marker exists for
     * under-reported it. This guard is the reason that cannot silently come back.
     */
    @Test
    void everyCompatClass_carriesTheCompatibilityModeMarker() {
        ArchRule rule = classes()
                .that().resideInAPackage("eu.exeris.spring.runtime.actuator.compat..")
                .and().areNotMemberClasses()
                .and().areNotLocalClasses()
                .and().areNotAnonymousClasses()
                .should().beAnnotatedWith(CompatibilityMode.class)
                .as("every eu.exeris.spring.runtime.actuator.compat.. type must carry @CompatibilityMode (ADR-011)");

        rule.check(actuatorModuleClasses);
    }
}
