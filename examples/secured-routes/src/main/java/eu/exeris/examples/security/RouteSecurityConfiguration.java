/*
 * Copyright (C) 2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.examples.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import eu.exeris.spring.runtime.web.security.ExerisHttpSecurity;

@Configuration
public class RouteSecurityConfiguration {

    // tag::policy[]
    @Bean
    ExerisHttpSecurity httpSecurity() {
        return ExerisHttpSecurity.create()
                // A closed anyRequest() must permit the kernel probe routes, or startup fails.
                .requestMatchers("/health", "/health/live", "/health/ready", "/db/ping", "/db/roundtrip")
                    .permitAll()
                .requestMatchers("/catalog").permitAll()
                .requestMatchers("/orders").hasAnyScope("orders:read")
                .anyRequest().authenticated();
    }
    // end::policy[]
}
