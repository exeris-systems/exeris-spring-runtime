/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.data;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * ArchUnit module boundary guard tests for {@code exeris-spring-runtime-data}.
 *
 * <p>Enforces ADR-017 §7 Rules 1 and 3.
 */
class DataModuleBoundaryTest {

    private static JavaClasses dataClasses;

    @BeforeAll
    static void importClasses() {
        dataClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("eu.exeris.spring.runtime.data");
    }

    /**
     * ADR-017 §7 Rule 1 — JDBC adapter classes must reside in {@code *.data.compat.*}.
     */
    @Test
    void jdbcAdapterClasses_mustResideInCompatPackage() {
        ArchRule rule = noClasses()
                .that().implement("java.sql.Connection")
                .or().implement("java.sql.PreparedStatement")
                .or().implement("java.sql.ResultSet")
                .should().resideOutsideOfPackage("eu.exeris.spring.runtime.data.compat..")
                .allowEmptyShould(true)
                .because("JDBC compatibility adapters must be isolated in *.data.compat.* (ADR-017 §7 Rule 1)");

        rule.check(dataClasses);
    }

    /**
     * ADR-017 §7 Rule 3 — data module must not import web or actuator concerns.
     */
    @Test
    void dataModule_mustNotImportWebOrActuator() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("eu.exeris.spring.runtime.data..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                        "eu.exeris.spring.runtime.web..",
                        "eu.exeris.spring.runtime.actuator.."
                )
                .allowEmptyShould(true)
                .because("data module must not import web or actuator concerns (ADR-017 §7 Rule 3 / module-boundaries.md)");

        rule.check(dataClasses);
    }

    /**
     * ADR-017 §7 — data module must not import servlet API.
     */
    @Test
    void dataModule_mustNotImportServletApi() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("eu.exeris.spring.runtime.data..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("jakarta.servlet..", "javax.servlet..")
                .allowEmptyShould(true)
                .because("data module must not import servlet API — compat mode does not use servlet stack");

        rule.check(dataClasses);
    }

    /**
     * The Wall — data module must not import HikariCP (ADR-017 §7 Rule 2 / module-boundaries.md).
     */
    @Test
    void dataModule_mustNotImportHikariCP() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("eu.exeris.spring.runtime.data..")
                .should().dependOnClassesThat()
                .resideInAPackage("com.zaxxer.hikari..")
                .allowEmptyShould(true)
                .because("HikariCP must not appear in exeris-spring-runtime-data (ADR-017 §4.3)");

        rule.check(dataClasses);
    }
}
