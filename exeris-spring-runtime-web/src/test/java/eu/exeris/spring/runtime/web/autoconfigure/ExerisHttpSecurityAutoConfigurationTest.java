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

import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpRoutePolicy;
import eu.exeris.kernel.spi.http.RouteRequirement;
import eu.exeris.spring.runtime.web.security.ExerisHttpSecurity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Module-integration coverage for ADR-063's wiring: a declaration becomes a kernel
 * {@code HttpRoutePolicy} bean, and declaring nothing produces nothing.
 */
class ExerisHttpSecurityAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ExerisHttpSecurityAutoConfiguration.class));

    /**
     * ADR-063 obligation 6. This is the case that decides whether adopting the runtime changes an
     * application's authorization posture by accident: with no declaration the slot must be left
     * unbound, so the kernel applies no per-route requirement — identical to a kernel with no policy.
     */
    @Test
    void noDeclarationProducesNoPolicyBean() {
        runner.run(context -> assertThat(context).doesNotHaveBean(HttpRoutePolicy.class));
    }

    @Test
    void declarationIsCompiledIntoAPolicyBean() {
        runner.withUserConfiguration(DeclaringConfig.class).run(context -> {
            assertThat(context).hasSingleBean(HttpRoutePolicy.class);

            HttpRoutePolicy policy = context.getBean(HttpRoutePolicy.class);
            assertThat(policy.requirementFor(HttpMethod.GET, "/health").kind())
                    .isEqualTo(RouteRequirement.Kind.PERMIT_ALL);
            assertThat(policy.requirementFor(HttpMethod.GET, "/api/orders").kind())
                    .isEqualTo(RouteRequirement.Kind.ANY_SCOPE);
            assertThat(policy.requirementFor(HttpMethod.GET, "/anything/else").kind())
                    .isEqualTo(RouteRequirement.Kind.AUTHENTICATED);
        });
    }

    /**
     * Compilation runs at bean creation, so a declaration that would deny the kernel's probe endpoints
     * fails context refresh. That timing is the point: the alternative is a deployment that starts
     * cleanly and never becomes ready.
     */
    @Test
    void invalidDeclarationFailsContextRefresh() {
        runner.withUserConfiguration(LocksOutProbesConfig.class).run(context ->
                assertThat(context).hasFailed()
                        .getFailure()
                        .rootCause()
                        .hasMessageContaining("/health")
                        .hasMessageContaining("never become ready"));
    }

    /** An application supplying its own policy bean is left alone. */
    @Test
    void userSuppliedPolicyWins() {
        runner.withUserConfiguration(DeclaringConfig.class, OwnPolicyConfig.class).run(context -> {
            assertThat(context).hasSingleBean(HttpRoutePolicy.class);
            assertThat(context.getBean(HttpRoutePolicy.class)).isSameAs(OwnPolicyConfig.POLICY);
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class DeclaringConfig {
        @Bean
        ExerisHttpSecurity httpSecurity() {
            return ExerisHttpSecurity.create()
                    .requestMatchers("/health", "/health/live", "/health/ready", "/db/ping", "/db/roundtrip")
                    .permitAll()
                    .requestMatchers("/api/orders/**").hasAnyScope("orders:read")
                    .anyRequest().authenticated();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class LocksOutProbesConfig {
        @Bean
        ExerisHttpSecurity httpSecurity() {
            return ExerisHttpSecurity.create()
                    .requestMatchers("/api/**").hasAnyScope("api")
                    .anyRequest().authenticated();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class OwnPolicyConfig {
        static final HttpRoutePolicy POLICY = (method, path) -> RouteRequirement.permitAll();

        @Bean
        HttpRoutePolicy ownPolicy() {
            return POLICY;
        }
    }
}
