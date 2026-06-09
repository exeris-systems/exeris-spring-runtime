/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.web.autoconfigure;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;

import eu.exeris.kernel.spi.telemetry.TelemetryConfig;
import eu.exeris.kernel.spi.telemetry.TelemetryProvider;
import eu.exeris.kernel.spi.telemetry.TelemetrySink;
import eu.exeris.spring.boot.autoconfigure.ExerisRuntimeLifecycle;
import eu.exeris.spring.runtime.web.ExerisErrorMapper;
import eu.exeris.spring.runtime.web.ExerisHttpDispatcher;
import eu.exeris.spring.runtime.web.ExerisRequestHandler;
import eu.exeris.spring.runtime.web.ExerisRoute;
import eu.exeris.spring.runtime.web.ExerisRouteRegistry;
import eu.exeris.spring.runtime.web.scope.ExerisContextScopeProperties;
import eu.exeris.spring.runtime.web.scope.KernelProviderBinder;
import eu.exeris.spring.runtime.web.scope.RequestScopeBinder;
import eu.exeris.spring.runtime.web.scope.RequestScopeResolver;

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
@EnableConfigurationProperties(ExerisContextScopeProperties.class)
public class ExerisWebAutoConfiguration {

    private static final System.Logger LOGGER = System.getLogger(ExerisWebAutoConfiguration.class.getName());
    private static final String FALLBACK_TELEMETRY_SINKS_BEAN_NAME = "exerisFallbackTelemetrySinks";

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

    @Bean(name = FALLBACK_TELEMETRY_SINKS_BEAN_NAME, destroyMethod = "close")
    @ConditionalOnMissingBean(name = FALLBACK_TELEMETRY_SINKS_BEAN_NAME)
    @SuppressWarnings("unused")
    Supplier<List<TelemetrySink>> exerisFallbackTelemetrySinks(ObjectProvider<TelemetryProvider> telemetryProviders) {
        return buildFallbackSinksSupplier(telemetryProviders);
    }

    /**
     * Phase 3B-α (ADR-029): build the {@link RequestScopeBinder} that the dispatcher uses to
     * optionally bind a request scope around each {@code HttpHandler.handle} invocation.
     *
     * <p>Wiring decision matrix:
     * <ul>
     *   <li>Property disabled ({@code exeris.runtime.context.scope.enabled=false}, default) →
     *       {@link RequestScopeBinder#noop()}, zero-cost pass-through.</li>
     *   <li>Property enabled + no {@link RequestScopeResolver} bean → noop binder (the scope
     *       cannot be built without an application-side resolver; logged at INFO once).</li>
     *   <li>Property enabled + resolver bean present → resolving binder.</li>
     * </ul>
     */
    @Bean
    @ConditionalOnMissingBean
    @SuppressWarnings("unused")
    RequestScopeBinder exerisRequestScopeBinder(ExerisContextScopeProperties properties,
                                                 ObjectProvider<RequestScopeResolver> resolverProvider) {
        if (!properties.enabled()) {
            return RequestScopeBinder.noop();
        }
        RequestScopeResolver resolver = resolverProvider.getIfAvailable();
        if (resolver == null) {
            LOGGER.log(System.Logger.Level.INFO,
                    "exeris.runtime.context.scope.enabled=true but no RequestScopeResolver bean is present; "
                            + "the request scope will not be bound. Provide a @Bean RequestScopeResolver to "
                            + "build a RequestScope from each incoming request (typically from headers).");
            return RequestScopeBinder.noop();
        }
        return RequestScopeBinder.resolving(resolver);
    }

    /**
     * Builds the {@link KernelProviderBinder} the dispatcher uses to re-bind kernel provider
     * {@code ScopedValue} slots (persistence engine, memory allocator) onto the request handler
     * thread. The externally-supplied {@code HttpHandler} runs on the transport carrier thread,
     * which does not inherit the kernel bootstrap scope, so the captured references held by
     * {@link ExerisRuntimeLifecycle} are re-bound per request (only when currently unbound).
     *
     * <p>The {@link ExerisRuntimeLifecycle} bean is resolved <em>lazily</em>, per request, via the
     * {@link ObjectProvider} — NOT at bean construction. Eager resolution here would create a
     * construction-time cycle: {@code ExerisRuntimeLifecycle} depends on the {@code HttpHandler}
     * (dispatcher), which depends on this binder. Deferring the lookup to request time (when the
     * lifecycle singleton is fully built and has captured its engines) breaks that cycle. When no
     * lifecycle is present (web-slice tests) the suppliers yield empty and the binder is a
     * zero-cost pass-through.
     */
    @Bean
    @ConditionalOnMissingBean
    @SuppressWarnings("unused")
    KernelProviderBinder exerisKernelProviderBinder(ObjectProvider<ExerisRuntimeLifecycle> lifecycleProvider) {
        return KernelProviderBinder.capturing(
                () -> Optional.ofNullable(lifecycleProvider.getIfAvailable())
                        .flatMap(ExerisRuntimeLifecycle::getPersistenceEngine),
                () -> Optional.ofNullable(lifecycleProvider.getIfAvailable())
                        .flatMap(ExerisRuntimeLifecycle::getMemoryAllocator));
    }

    @Bean
    @ConditionalOnMissingBean
    @SuppressWarnings("unused")
    ExerisHttpDispatcher exerisHttpDispatcher(ExerisRouteRegistry routeRegistry,
                                               ExerisErrorMapper errorMapper,
                                               @Qualifier(FALLBACK_TELEMETRY_SINKS_BEAN_NAME)
                                               Supplier<List<TelemetrySink>> fallbackTelemetrySinks,
                                               RequestScopeBinder scopeBinder,
                                               KernelProviderBinder kernelProviderBinder) {
        return new ExerisHttpDispatcher(
                routeRegistry, errorMapper, fallbackTelemetrySinks, scopeBinder, kernelProviderBinder);
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
        return new FallbackTelemetrySinksSupplier(telemetryProviders);
    }

    private static final class FallbackTelemetrySinksSupplier implements Supplier<List<TelemetrySink>>, AutoCloseable {

        private final ObjectProvider<TelemetryProvider> telemetryProviders;
        private final Object lock = new Object();
        private final AtomicReference<List<TelemetrySink>> cached = new AtomicReference<>();

        FallbackTelemetrySinksSupplier(ObjectProvider<TelemetryProvider> telemetryProviders) {
            this.telemetryProviders = telemetryProviders;
        }

        @Override
        public List<TelemetrySink> get() {
            List<TelemetrySink> resolved = cached.get();
            if (resolved != null) {
                return resolved;
            }

            synchronized (lock) {
                resolved = cached.get();
                if (resolved == null) {
                    resolved = resolveSinks();
                    cached.set(resolved);
                }
                return resolved;
            }
        }

        @Override
        public void close() {
            List<TelemetrySink> sinks = cached.getAndSet(List.of());
            if (sinks == null || sinks.isEmpty()) {
                return;
            }
            sinks.forEach(FallbackTelemetrySinksSupplier::closeQuietly);
        }

        private List<TelemetrySink> resolveSinks() {
            if (telemetryProviders == null) {
                return List.of();
            }

            TelemetryConfig config = TelemetryConfig.defaults();
            List<TelemetrySink> sinks = new ArrayList<>();
            telemetryProviders.orderedStream()
                    .filter(Objects::nonNull)
                    .forEach(provider -> {
                        try {
                            List<TelemetrySink> providerSinks = provider.createSinks(config);
                            if (providerSinks == null || providerSinks.isEmpty()) {
                                return;
                            }
                            providerSinks.stream()
                                    .filter(Objects::nonNull)
                                    .forEach(sinks::add);
                        } catch (RuntimeException ex) {
                            LOGGER.log(System.Logger.Level.WARNING,
                                    "Skipping fallback telemetry provider '" + safeProviderName(provider)
                                            + "' after createSinks() failure; continuing with remaining providers.",
                                    ex);
                        }
                    });

            return sinks.isEmpty() ? List.of() : List.copyOf(sinks);
        }

        private static String safeProviderName(TelemetryProvider provider) {
            try {
                return provider.providerName();
            } catch (RuntimeException _) {
                return provider.getClass().getName();
            }
        }

        private static void closeQuietly(TelemetrySink sink) {
            try {
                sink.close();
            } catch (RuntimeException _) {
                // Best-effort shutdown only for Spring-managed fallback sinks.
            }
        }
    }
}
