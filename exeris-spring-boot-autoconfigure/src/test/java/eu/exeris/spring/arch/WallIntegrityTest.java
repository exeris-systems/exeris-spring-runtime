/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.arch;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Architecture guard: verifies that The Wall between the Exeris kernel and Spring remains intact.
 *
 * <h2>Rules Enforced</h2>
 * <ol>
 *   <li>No class in {@code eu.exeris.kernel.spi.*} imports any {@code org.springframework.*} type.</li>
 *   <li>No class in {@code eu.exeris.kernel.core.*} imports any {@code org.springframework.*} type.</li>
 *   <li>No class in {@code eu.exeris.spring.boot.autoconfigure} imports web-runtime types from
 *       {@code eu.exeris.spring.runtime.web.*} — autoconfigure must remain a thin wiring layer.</li>
 *   <li>No class in any module imports servlet API types — no servlet container on the classpath.</li>
 * </ol>
 *
 * <p>These rules encode the non-negotiable invariants from ADR-001 and cannot be relaxed
 * without an explicit superseding ADR.
 *
 * @since 0.1.0
 */
class WallIntegrityTest {

    private static JavaClasses allProjectClasses;

    @BeforeAll
    static void importAllClasses() {
        allProjectClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(
                        "eu.exeris.kernel.spi",
                        "eu.exeris.kernel.core",
                        "eu.exeris.spring.boot.autoconfigure",
                        "eu.exeris.spring.runtime.web");
    }

    @Test
    void kernelSpi_mustNotDependOnSpring() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("eu.exeris.kernel.spi..")
                .should().dependOnClassesThat()
            .resideInAPackage("org.springframework..")
            .allowEmptyShould(true);

        rule.check(allProjectClasses);
    }

    @Test
    void kernelCore_mustNotDependOnSpring() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("eu.exeris.kernel.core..")
                .should().dependOnClassesThat()
            .resideInAPackage("org.springframework..")
            .allowEmptyShould(true);

        rule.check(allProjectClasses);
    }

    @Test
    void autoconfigure_mustNotImportWebRuntimeClasses() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("eu.exeris.spring.boot.autoconfigure..")
                .should().dependOnClassesThat()
            .resideInAPackage("eu.exeris.spring.runtime.web..")
            .allowEmptyShould(true);

        rule.check(allProjectClasses);
    }

    @Test
    void noClassAnywhere_mustImportServletApi() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("eu.exeris..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                        "jakarta.servlet..",
                "javax.servlet..")
            .allowEmptyShould(true);

        rule.check(allProjectClasses);
    }

    @Test
    void noClassAnywhere_mustImportTomcatOrUndertowOrJetty() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("eu.exeris..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                        "org.apache.catalina..",
                        "org.apache.tomcat..",
                        "io.undertow..",
                "org.eclipse.jetty..")
            .allowEmptyShould(true);

        rule.check(allProjectClasses);
    }

    @Test
    void noClassAnywhere_mustImportReactorOrNetty() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("eu.exeris..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                        "reactor.core..",
                        "reactor.netty..",
                "io.netty..")
            .allowEmptyShould(true);

        rule.check(allProjectClasses);
    }
}
