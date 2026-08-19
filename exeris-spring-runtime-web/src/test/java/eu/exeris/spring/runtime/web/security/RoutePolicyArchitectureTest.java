/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.web.security;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Architecture guard for ADR-063 obligation 1: <strong>no second decision layer</strong>.
 *
 * <p>This runtime compiles rules; it does not evaluate them. Every authorization decision is taken by
 * the kernel's {@code RouteAuthorizationEnforcer} on the admission path, before either dispatcher
 * runs. ADR-061 placed that enforcer in kernel Core specifically so a Spring-side DSL would translate
 * onto it rather than growing a rival mechanism.
 *
 * <p>The failure this blocks is not hypothetical-sounding once written down: a Spring-side evaluation
 * of route rules can disagree with the kernel's, and then the effective policy for a route depends on
 * which layer ran for that request. Both layers fail closed individually, which makes the
 * disagreement quiet — a 403 that "should" be a 200 — and quiet is the worst property an
 * authorization defect can have.
 *
 * <p>The rule is stated as an absolute rather than as "outside the compilation seam": nothing here
 * needs the enforcer at all, so the honest guard is zero references. If a future slice genuinely
 * needs one, that is an ADR-063 amendment, and this test is where the conversation starts.
 */
class RoutePolicyArchitectureTest {

    private static final String ENFORCER =
            "eu.exeris.kernel.core.security.RouteAuthorizationEnforcer";

    private static JavaClasses webClasses;

    @BeforeAll
    static void importWebProductionClasses() {
        webClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("eu.exeris.spring.runtime.web");
    }

    /**
     * The rules below use {@code allowEmptyShould(true)}, which means they would pass on an empty
     * import — a guard reporting success on work it had not done. That is the exact failure mode the
     * kernel's preview-bytecode gate had to correct upstream, so the import is asserted rather than
     * assumed.
     */
    @Test
    void guardActuallySeesTheProductionClasses() {
        assertThat(webClasses).isNotEmpty();
        assertThat(webClasses.stream().map(c -> c.getName()))
                .contains(ExerisHttpSecurity.class.getName(),
                        CompiledHttpRoutePolicy.class.getName(),
                        "eu.exeris.spring.runtime.web.ExerisHttpDispatcher");
    }

    @Test
    void noProductionClassEvaluatesRouteAuthorizationItself() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("eu.exeris.spring.runtime.web..")
                .should().dependOnClassesThat().haveFullyQualifiedName(ENFORCER)
                .as("eu.exeris.spring.runtime.web.. must not reference " + ENFORCER
                        + " (ADR-063 obligation 1: the kernel decides, this runtime only compiles)")
                .allowEmptyShould(true);

        rule.check(webClasses);
    }

    /**
     * The seam is a single named class, so "where compilation happens" stays answerable by reading one
     * file rather than by tracing which of several places built the policy.
     */
    @Test
    void compilationHappensInExactlyOnePlace() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("eu.exeris.spring.runtime.web..")
                .and().doNotHaveFullyQualifiedName(ExerisRoutePolicyCompiler.class.getName())
                .and().doNotHaveFullyQualifiedName(ExerisHttpSecurity.class.getName())
                .and().doNotHaveFullyQualifiedName(
                        "eu.exeris.spring.runtime.web.autoconfigure.ExerisHttpSecurityAutoConfiguration")
                .should().dependOnClassesThat()
                .haveFullyQualifiedName(CompiledHttpRoutePolicy.class.getName())
                .as("only the compilation seam may construct or reference the compiled policy "
                        + "(ADR-063 obligation 1)")
                .allowEmptyShould(true);

        rule.check(webClasses);
    }

    /**
     * ADR-063 obligation 5: mode-neutral. The policy governs ingress for Pure Mode and Compatibility
     * Mode alike, so it must not sit in — or reach into — {@code *.compat.*}. A pure-mode application
     * that could not declare route rules without dragging in the compat surface would be a
     * mode-boundary breach dressed up as a feature.
     */
    @Test
    void routePolicyIsModeNeutral() {
        assertThat(ExerisHttpSecurity.class.getPackageName())
                .as("ADR-063 obligation 5: the DSL is mode-neutral, not a compat type")
                .doesNotContain(".compat");

        ArchRule rule = noClasses()
                .that().resideInAPackage("eu.exeris.spring.runtime.web.security..")
                .should().dependOnClassesThat()
                .resideInAPackage("eu.exeris.spring.runtime.web.compat..")
                .as("the route-policy seam must not depend on the compatibility surface "
                        + "(ADR-063 obligation 5)")
                .allowEmptyShould(true);

        rule.check(webClasses);
    }
}
