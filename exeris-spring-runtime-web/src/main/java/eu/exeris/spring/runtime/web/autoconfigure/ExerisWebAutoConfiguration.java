/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.web.autoconfigure;

import eu.exeris.kernel.spi.telemetry.TelemetryConfig;
import eu.exeris.kernel.spi.telemetry.TelemetryProvider;
import eu.exeris.kernel.spi.telemetry.TelemetrySink;
import eu.exeris.spring.runtime.web.ExerisErrorMapper;
import eu.exeris.spring.runtime.web.ExerisHttpDispatcher;
import eu.exeris.spring.runtime.web.ExerisRequestHandler;
import eu.exeris.spring.runtime.web.ExerisRoute;
import eu.exeris.spring.runtime.web.ExerisRouteRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

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
                                                      ExerisErrorMapper errorMapper,
                                                      ObjectProvider<TelemetryProvider> telemetryProviders) {
        return new ExerisHttpDispatcher(routeRegistry, errorMapper, buildFallbackSinksSupplier(telemetryProviders));
    }

    /**
     * Discovers Spring-managed {@link TelemetryProvider} beans and creates their sinks.
     *
     * <p>Used as the fallback telemetry sink list for {@link ExerisHttpDispatcher} when
     * {@code KernelProviders.TELEMETRY_SINKS} is not bound (testkit / non-kernel-scope paths).
     * In production the kernel always binds {@code TELEMETRY_SINKS} before handler invocation,
     * so this list is never consulted on the production hot path.
     *
     * <p>Returns an empty list when no {@link TelemetryProvider} beans are present in the context.
     */
    private static Supplier<List<TelemetrySink>> buildFallbackSinksSupplier(
            ObjectProvider<TelemetryProvider> telemetryProviders) {
        return () -> {
            if (telemetryProviders == null) {
                return List.of();
            }

            TelemetryConfig config = TelemetryConfig.defaults();
            List<TelemetrySink> sinks = new ArrayList<>();
            telemetryProviders.orderedStream()
                    .filter(Objects::nonNull)
                    .forEach(provider -> {
                        List<TelemetrySink> providerSinks = provider.createSinks(config);
                        if (providerSinks == null || providerSinks.isEmpty()) {
                            return;
                        }
                        providerSinks.stream()
                                .filter(Objects::nonNull)
                                .forEach(sinks::add);
                    });

            return sinks.isEmpty() ? List.of() : List.copyOf(sinks);
        };
    }
}
