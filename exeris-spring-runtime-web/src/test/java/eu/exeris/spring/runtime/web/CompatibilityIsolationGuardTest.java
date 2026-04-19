/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.web;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Architecture guard that enforces Phase 2 Compatibility Mode isolation invariants:
 *
 * <ol>
 *   <li>No {@code jakarta.servlet.*} type in any class under
 *       {@code eu.exeris.spring.runtime.web.*}.</li>
 *   <li>{@code ExerisHttpDispatcher} (pure mode) must not import any
 *       {@code *.compat.*} class.</li>
 *   <li>{@code ThreadLocal}-bearing compatibility bindings must live exclusively
 *       in {@code *.compat.context.*}.</li>
 * </ol>
 */
class CompatibilityIsolationGuardTest {

    private static JavaClasses webClasses;

    @BeforeAll
    static void importWebClasses() {
        webClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("eu.exeris.spring.runtime.web");
    }

    /**
     * No production class in the web module may reference {@code jakarta.servlet}.
     * Servlet API is banned in all modes per the module boundaries contract.
     */
    @Test
    void noServletApiInWebModule() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("eu.exeris.spring.runtime.web..")
                .should().dependOnClassesThat()
                .resideInAPackage("jakarta.servlet..")
                .allowEmptyShould(true)
                .as("No jakarta.servlet.* dependency allowed in eu.exeris.spring.runtime.web");

        rule.check(webClasses);
    }

    /**
     * The pure-mode dispatcher must not import anything from the compat package.
     * Compat activation must not bleed into the pure path.
     */
    @Test
    void pureModeDispatcher_mustNotDependOnCompatPackage() {
        ArchRule rule = noClasses()
                .that().haveFullyQualifiedName(ExerisHttpDispatcher.class.getName())
                .should().dependOnClassesThat()
                .resideInAPackage("eu.exeris.spring.runtime.web.compat..")
                .allowEmptyShould(true)
                .as("ExerisHttpDispatcher (pure mode) must not depend on compat package");

        rule.check(webClasses);
    }

    /**
     * The compat package must not depend on {@code org.springframework.web.servlet}
     * (spring-webmvc). Only {@code spring-web} (non-servlet) is permitted.
     */
    @Test
    void compatPackage_mustNotDependOnSpringWebMvc() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("eu.exeris.spring.runtime.web.compat..")
                .should().dependOnClassesThat()
                .resideInAPackage("org.springframework.web.servlet..")
                .allowEmptyShould(true)
                .as("Compat package must not depend on spring-webmvc (org.springframework.web.servlet)");

        rule.check(webClasses);
    }

    /**
     * {@code ExerisContextHolder} must only be referenced from within the
     * {@code *.compat.context.*} package. Pure-mode paths must not import it.
     */
    @Test
    void exerisContextHolder_mustNotBeCalledFromPureModePath() {
        ArchRule rule = noClasses()
                .that().resideOutsideOfPackage("eu.exeris.spring.runtime.web.compat..")
                .should().dependOnClassesThat()
                .haveSimpleName("ExerisContextHolder")
                .allowEmptyShould(true);
        rule.check(webClasses);
    }

    /**
     * Within the compat package, {@code ExerisContextHolder} may only be referenced from
     * {@code *.compat.context.*} or {@code *.compat.filter.*} (entry-filter scope).
     * Deeper compat components — handlers, resolvers, adapters, bridges — must not
     * access it. This enforces the Decision 3 lifetime contract: ExerisContextHolder
     * is populated exactly once per request in the entry filter before {@code ScopedValue.run()},
     * not at later stages in the dispatch pipeline.
     */
    @Test
    void exerisContextHolder_mustOnlyBeCalledFromFilterOrContextScope() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("eu.exeris.spring.runtime.web.compat..")
                .and().resideOutsideOfPackages(
                        "eu.exeris.spring.runtime.web.compat.context..",
                        "eu.exeris.spring.runtime.web.compat.filter..")
                .should().dependOnClassesThat()
                .haveSimpleName("ExerisContextHolder")
                .allowEmptyShould(true)
                .as("ExerisContextHolder must only be called from compat.filter.* or compat.context.* — "
                        + "handlers, resolvers, and compat bridges must not access it");

        rule.check(webClasses);
    }

    /**
     * Web module production code must not import any {@code eu.exeris.kernel.core.*}
     * class — The Wall applies even at the Spring integration layer.
     */
    @Test
    void webModule_mustNotImportKernelCoreClasses() {
        ArchRule rule = noClasses()
                .should().dependOnClassesThat()
                .resideInAnyPackage("eu.exeris.kernel.core..")
                .allowEmptyShould(true);
        rule.check(webClasses);
    }

    /**
     * {@code *.compat.filter.*} must not import {@code jakarta.servlet.*}.
     * The security filter runs without a servlet container — any servlet contamination
     * here indicates an invalid dependency was introduced.
     */
    @Test
    void compatFilterPackage_mustNotImportServletApi() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("eu.exeris.spring.runtime.web.compat.filter..")
                .should().dependOnClassesThat()
                .resideInAPackage("jakarta.servlet..")
                .allowEmptyShould(true)
                .as("compat.filter.* must not depend on jakarta.servlet — security filter runs servlet-free");

        rule.check(webClasses);
    }
}