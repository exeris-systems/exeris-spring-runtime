/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.web.scope;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Architecture guard for Phase 3B-α (per ADR-029 obligation 4): the request scope package
 * uses {@link ScopedValue} as the only carrier and must not introduce {@link ThreadLocal}.
 *
 * <p>The {@code ThreadLocal}-on-hot-path ban is documented in {@code CLAUDE.md} §"Pure Mode vs
 * Compatibility Mode" as a narrative rule. This test promotes that narrative to a per-package
 * merge-blocking ArchUnit assertion for the scope package specifically. A future PR that adds
 * a {@code ThreadLocal} field, parameter, or static under
 * {@code eu.exeris.spring.runtime.web.scope..} is an ADR-029-violating PR; the reviewer cites
 * ADR-029 by number when blocking.
 *
 * <p>Note: this is the FIRST per-package {@code ThreadLocal} ban in the repo at the type level.
 * {@link eu.exeris.spring.runtime.web.CompatibilityIsolationGuardTest} regulates
 * {@code ThreadLocal} usage by sub-package isolation (compat-only) but does not ban the type
 * itself at the package level.
 */
class RequestScopeArchitectureTest {

    private static JavaClasses scopeClasses;

    @BeforeAll
    static void importScopeClasses() {
        scopeClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("eu.exeris.spring.runtime.web.scope");
    }

    /**
     * No production class under {@code eu.exeris.spring.runtime.web.scope..} may depend on
     * {@link ThreadLocal}. {@code ScopedValue} is the only carrier permitted by ADR-029.
     */
    @Test
    void scopePackageMustNotUseThreadLocal() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("eu.exeris.spring.runtime.web.scope..")
                .should().dependOnClassesThat()
                .haveFullyQualifiedName("java.lang.ThreadLocal")
                .orShould().dependOnClassesThat()
                .haveFullyQualifiedName("java.lang.InheritableThreadLocal")
                .as("eu.exeris.spring.runtime.web.scope.. must not depend on ThreadLocal "
                        + "(per ADR-029 obligation 4: ScopedValue is the only carrier)")
                .allowEmptyShould(true);

        rule.check(scopeClasses);
    }

    /**
     * The scope package must not depend on Spring's legacy web request scopes
     * ({@code @RequestScope}, {@code @SessionScope}) — those are servlet-bound and not part of
     * Phase 3B-α's affordance set per ADR-029 §"What is NOT in scope".
     */
    @Test
    void scopePackageMustNotDependOnSpringWebContextRequest() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("eu.exeris.spring.runtime.web.scope..")
                .should().dependOnClassesThat()
                .resideInAPackage("org.springframework.web.context.request..")
                .as("eu.exeris.spring.runtime.web.scope.. must not depend on Spring's legacy "
                        + "web request-context types (per ADR-029 §'What is NOT in scope': "
                        + "@RequestScope / @SessionScope bridging is intentionally not provided)")
                .allowEmptyShould(true);

        rule.check(scopeClasses);
    }
}
