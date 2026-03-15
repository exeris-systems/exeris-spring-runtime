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
 * Architecture guard: verifies that Pure Mode and Compatibility Mode cannot bleed into each other,
 * and that module boundaries within {@code exeris-spring-runtime} are respected.
 *
 * <h2>Rules Enforced</h2>
 * <ol>
 *   <li>Classes in {@code *.web.pure.*} must not import from {@code *.web.compat.*}.</li>
 *   <li>Classes in {@code *.web.*} (non-compat) must not import {@code DispatcherServlet}.</li>
 *   <li>Classes outside {@code *.data.*} must not import HikariCP or any JDBC pool.</li>
 *   <li>Classes outside {@code *.tx.*} must not import Spring transaction management internals.</li>
 * </ol>
 *
 * @since 0.1.0
 */
class ModuleBoundaryTest {

    private static JavaClasses allProjectClasses;

    @BeforeAll
    static void importAllClasses() {
        allProjectClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("eu.exeris.spring..");
    }

    @Test
    void pureModeClasses_mustNotImportCompatClasses() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("eu.exeris.spring.runtime.web.pure..")
                .should().dependOnClassesThat()
            .resideInAPackage("eu.exeris.spring.runtime.web.compat..")
            .allowEmptyShould(true);

        rule.check(allProjectClasses);
    }

    @Test
    void webClasses_mustNotUseDispatcherServlet() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("eu.exeris.spring.runtime.web..")
                .and().resideOutsideOfPackage("eu.exeris.spring.runtime.web.compat..")
                .should().dependOnClassesThat()
            .haveFullyQualifiedName("org.springframework.web.servlet.DispatcherServlet")
            .allowEmptyShould(true);

        rule.check(allProjectClasses);
    }

    @Test
    void nonDataClasses_mustNotImportHikariCp() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("eu.exeris.spring..")
                .and().resideOutsideOfPackage("eu.exeris.spring.runtime.data..")
                .should().dependOnClassesThat()
            .resideInAPackage("com.zaxxer.hikari..")
            .allowEmptyShould(true);

        rule.check(allProjectClasses);
    }

    @Test
    void nonTxClasses_mustNotImportTransactionInterceptor() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("eu.exeris.spring..")
                .and().resideOutsideOfPackage("eu.exeris.spring.runtime.tx..")
                .should().dependOnClassesThat()
                .haveFullyQualifiedName(
                "org.springframework.transaction.interceptor.TransactionInterceptor")
            .allowEmptyShould(true);

        rule.check(allProjectClasses);
    }
}
