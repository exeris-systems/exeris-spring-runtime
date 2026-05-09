/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.flow;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import eu.exeris.kernel.spi.flow.model.FlowStepAction;

/**
 * Architecture guards for {@code exeris-spring-runtime-flow}.
 *
 * <p>The flow module is structured-execution machinery; its boundary set mirrors the
 * events module's. Specifically:
 * <ul>
 *   <li>It must never wire Spring's {@code ApplicationEventPublisher} or
 *       {@code org.springframework.context.event} types into the kernel
 *       {@code FlowEngine} — flow choreography reads from the kernel
 *       {@code EventBus} via the events module's bridge, not from Spring's bus.</li>
 *   <li>It must never pull HTTP / transport concerns — flows are not request handlers.</li>
 *   <li>It must never reach into transaction or persistence packages directly —
 *       persistence (when re-enabled in a later step) lands via the kernel's
 *       {@code FlowSnapshotStore} SPI, not via JDBC / JPA in this module.</li>
 *   <li>It must never wire Spring's {@code @Async} / {@code TaskExecutor} as a flow
 *       execution path — kernel {@code FlowScheduler} owns flow execution.</li>
 * </ul>
 */
class FlowModuleBoundaryTest {

    private static JavaClasses flowModuleClasses;

    @BeforeAll
    static void importFlowModuleClasses() {
        flowModuleClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("eu.exeris.spring.runtime.flow");
    }

    @Test
    void doesNotDependOnSpringApplicationEventPublisher() {
        ArchRule rule = noClasses()
                .should().dependOnClassesThat()
                .haveFullyQualifiedName("org.springframework.context.ApplicationEventPublisher")
                .allowEmptyShould(true);
        rule.check(flowModuleClasses);
    }

    @Test
    void doesNotDependOnSpringContextEventPackage() {
        ArchRule rule = noClasses()
                .should().dependOnClassesThat()
                .resideInAPackage("org.springframework.context.event..")
                .allowEmptyShould(true);
        rule.check(flowModuleClasses);
    }

    @Test
    void doesNotImportHttpOrServletPackages() {
        ArchRule rule = noClasses()
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                        "jakarta.servlet..",
                        "javax.servlet..",
                        "eu.exeris.kernel.spi.http..",
                        "org.springframework.web..")
                .allowEmptyShould(true);
        rule.check(flowModuleClasses);
    }

    @Test
    void doesNotImportTransactionOrPersistencePackages() {
        ArchRule rule = noClasses()
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                        "org.springframework.transaction..",
                        "javax.sql..",
                        "java.sql..",
                        "eu.exeris.kernel.spi.persistence..")
                .allowEmptyShould(true);
        rule.check(flowModuleClasses);
    }

    @Test
    void doesNotImportJpaOrHibernate() {
        ArchRule rule = noClasses()
                .should().dependOnClassesThat()
                .resideInAnyPackage("jakarta.persistence..", "org.hibernate..")
                .allowEmptyShould(true);
        rule.check(flowModuleClasses);
    }

    @Test
    void doesNotImportSpringAsyncOrTaskExecutor() {
        ArchRule rule = noClasses()
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                        "org.springframework.scheduling..",
                        "org.springframework.core.task..")
                .allowEmptyShould(true);
        rule.check(flowModuleClasses);
    }

    /**
     * Step 2 closure-boundary guard: any class implementing
     * {@link ExerisFlowDefinition} (or the lower-level kernel
     * {@link FlowStepAction}) within this module's reach must not couple to HTTP /
     * web / transaction / persistence packages.
     *
     * <p>{@code FlowStepAction} lambdas execute on Exeris-owned virtual threads under a
     * {@code ScopedValue} scope independent of the Spring request thread — pulling
     * request-path or transaction-context types into a step body inverts ownership and
     * would silently break the kernel's threading model. Constructor-injected ports
     * (e.g. an {@code InventoryPort} bean delegating to JDBC) are the supported
     * collaboration shape; the step body must call those collaborators by interface,
     * not import their underlying technology directly.
     *
     * <p>The bridge module ships no production flow implementations, so the rule passes
     * vacuously here today. It serves as a forward-compatibility guard against future
     * contributors landing example flows that violate the closure-boundary contract.
     */
    @Test
    void flowDefinitionAndStepActionImplementorsDoNotImportRequestPathOrTxPackages() {
        ArchRule rule = classes()
                .that().implement(ExerisFlowDefinition.class)
                .or().implement(FlowStepAction.class)
                .should().onlyDependOnClassesThat().resideOutsideOfPackages(
                        "jakarta.servlet..",
                        "javax.servlet..",
                        "eu.exeris.kernel.spi.http..",
                        "org.springframework.web..",
                        "org.springframework.transaction..",
                        "javax.sql..",
                        "java.sql..",
                        "jakarta.persistence..",
                        "org.hibernate..")
                .allowEmptyShould(true);
        rule.check(flowModuleClasses);
    }
}
