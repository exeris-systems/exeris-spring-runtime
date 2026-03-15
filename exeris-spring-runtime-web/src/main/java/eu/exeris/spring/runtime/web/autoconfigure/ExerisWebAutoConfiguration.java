/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.web.autoconfigure;

import eu.exeris.spring.runtime.web.ExerisErrorMapper;
import eu.exeris.spring.runtime.web.ExerisHttpDispatcher;
import eu.exeris.spring.runtime.web.ExerisRequestHandler;
import eu.exeris.spring.runtime.web.ExerisRoute;
import eu.exeris.spring.runtime.web.ExerisRouteRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;

import java.util.Map;
import java.util.Objects;

/**
 * Auto-configuration for Exeris Pure Mode web routing and dispatcher bridge.
 */
@AutoConfiguration
@ConditionalOnClass(ExerisHttpDispatcher.class)
@ConditionalOnProperty(
        prefix = "exeris.runtime.web",
        name = "mode",
        havingValue = "pure",
        matchIfMissing = true)
public class ExerisWebAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ExerisErrorMapper exerisErrorMapper() {
        return new ExerisErrorMapper();
    }

    @Bean
    @ConditionalOnMissingBean
    public ExerisRouteRegistry exerisRouteRegistry(ApplicationContext ctx) {
        Map<String, ExerisRequestHandler> handlers = ctx.getBeansOfType(ExerisRequestHandler.class);
        ExerisRouteRegistry.Builder builder = ExerisRouteRegistry.builder();
        for (Map.Entry<String, ExerisRequestHandler> entry : handlers.entrySet()) {
            String beanName = Objects.requireNonNull(entry.getKey());
            ExerisRequestHandler handler = entry.getValue();
            ExerisRoute route = ctx.findAnnotationOnBean(beanName, ExerisRoute.class);
            if (route == null) {
                continue;
            }
            builder.register(route.method(), route.path(), handler);
        }
        return builder.build();
    }

    @Bean
    @ConditionalOnMissingBean
    public ExerisHttpDispatcher exerisHttpDispatcher(ExerisRouteRegistry routeRegistry,
                                                      ExerisErrorMapper errorMapper) {
        return new ExerisHttpDispatcher(routeRegistry, errorMapper);
    }
}
