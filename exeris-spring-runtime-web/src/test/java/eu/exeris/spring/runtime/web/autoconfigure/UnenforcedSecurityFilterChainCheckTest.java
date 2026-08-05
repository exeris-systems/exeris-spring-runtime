/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.web.autoconfigure;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import eu.exeris.spring.runtime.web.compat.ExerisCompatDispatcher;
import eu.exeris.spring.runtime.web.compat.security.UnenforcedSecurityFilterChainCheck;
import eu.exeris.spring.runtime.web.compat.security.UnenforcedSecurityFilterChainException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Module-integration tests for the Compatibility Mode fail-fast on an unenforceable
 * {@code SecurityFilterChain}.
 *
 * <h2>What was wrong</h2>
 * <p>A brownfield application migrating onto Exeris keeps its {@code SecurityFilterChain}. Under
 * {@code web-application-type=none} there is no {@code FilterChainProxy} to run it, and
 * {@code NoSecurityFilterChainCondition} correctly stood the compatibility fallback filter down
 * because a chain was present. The two together produced a context that started cleanly and served
 * every request with neither authentication nor authorization — the application believing its
 * chain was enforcing rules, and nothing enforcing anything. Startup must fail instead.
 *
 * <h2>Bean naming</h2>
 * <p>The chain is registered under the name Spring Security itself uses,
 * {@code springSecurityFilterChain}. The real type lives in {@code spring-security-web}, which
 * drags in {@code jakarta.servlet} and is therefore absent from this runtime's classpath by design
 * — which is exactly why detection works on names and definition metadata rather than on the type.
 */
class UnenforcedSecurityFilterChainCheckTest {

    private final ApplicationContextRunner compatRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ExerisCompatAutoConfiguration.class))
            .withPropertyValues("exeris.runtime.web.mode=compatibility");

    @Test
    void compatMode_withSecurityFilterChain_failsStartup() {
        compatRunner
                .withUserConfiguration(ChainConfiguration.class)
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        // Surfaces unwrapped: a BeanFactoryPostProcessor failure is already a
                        // startup failure, so Spring does not re-wrap it.
                        .isInstanceOf(UnenforcedSecurityFilterChainException.class));
    }

    @Test
    void failureMessage_saysWhatIsBroken_whatToDo_andHowToSilenceIt() {
        // The message is the whole deliverable for an operator hitting this at 3am mid-migration.
        // A fail-fast that only says "not supported" moves the outage from runtime to startup
        // without helping anyone, so assert it carries all three parts.
        compatRunner
                .withUserConfiguration(ChainConfiguration.class)
                .run(context -> {
                    assertThat(context).hasFailed();
                    String message = rootCauseMessage(context.getStartupFailure());
                    assertThat(message)
                            .as("names the offending bean")
                            .contains("springSecurityFilterChain");
                    assertThat(message)
                            .as("explains why the chain cannot run")
                            .contains("FilterChainProxy");
                    assertThat(message)
                            .as("points at the supported replacement")
                            .contains("compat-spring-security-support.md");
                    assertThat(message)
                            .as("gives the exact escape-hatch property")
                            .contains(UnenforcedSecurityFilterChainCheck.ALLOW_PROPERTY);
                });
    }

    @Test
    void explicitAcknowledgement_allowsStartup() {
        compatRunner
                .withUserConfiguration(ChainConfiguration.class)
                .withPropertyValues(UnenforcedSecurityFilterChainCheck.ALLOW_PROPERTY + "=true")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void compatMode_withoutSecurityFilterChain_startsNormally() {
        // Non-vacuity: the failure above must come from the chain, not from the compat context
        // being unable to start at all in this harness.
        compatRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(ExerisCompatDispatcher.class);
        });
    }

    private static String rootCauseMessage(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current.getMessage();
    }

    @Configuration(proxyBeanMethods = false)
    static class ChainConfiguration {

        /**
         * Stands in for an application-declared chain. Registered under Spring Security's own bean
         * name; the declared type is irrelevant to detection and cannot be the real one here.
         */
        @Bean(name = "springSecurityFilterChain")
        Object springSecurityFilterChain() {
            return new Object();
        }
    }
}
