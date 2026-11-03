/*
 * Copyright (C) 2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.spring.runtime.web.autoconfigure;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;

import eu.exeris.kernel.spi.telemetry.TelemetryConfig;
import eu.exeris.kernel.spi.telemetry.TelemetryProvider;
import eu.exeris.kernel.spi.telemetry.TelemetrySink;
import eu.exeris.spring.boot.autoconfigure.ExerisRuntimeLifecycle;
import eu.exeris.spring.runtime.web.ExerisErrorMapper;
import eu.exeris.spring.runtime.web.ExerisErrorStatusResolver;
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
 *
 * @since 0.1
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

    /**
     * Default constructor for auto-configuration.
     */
    public ExerisWebAutoConfiguration() {
    }

    // Resolvers turn specific exceptions into specific statuses before the 500 fallback. Pure Mode
    // needs them too: @PreAuthorize is plain AOP on application beans, so an authorization refusal
    // reaches this dispatcher here exactly as it does on the compat path.
    /**
     * Creates the error mapper for unhandled exceptions.
     *
     * @param statusResolvers status resolvers provided by the Spring context
     * @return configured error mapper
     */
    @Bean
    @ConditionalOnMissingBean
    public ExerisErrorMapper exerisErrorMapper(ObjectProvider<ExerisErrorStatusResolver> statusResolvers) {
        return new ExerisErrorMapper(statusResolvers.orderedStream().toList());
    }

    // The registry is created empty and filled after every singleton exists. Collecting the
    // handlers here, while the context is still instantiating, would miss any handler that is
    // itself mid-creation: a handler depending on a bean that depends on ExerisRuntimeLifecycle sits
    // inside the lifecycle → dispatcher → registry chain, and getBeansOfType skips beans currently
    // in creation without an error, leaving the route to answer 404.
    /**
     * Creates the route registry, populated from {@link ExerisRoute} annotated handler beans by
     * {@link #exerisRouteRegistryPopulator} once all singletons are instantiated.
     *
     * @param ctx the application context; the populator reads it, so this method does not
     * @return a deferred route registry
     */
    @Bean
    @ConditionalOnMissingBean
    public ExerisRouteRegistry exerisRouteRegistry(ApplicationContext ctx) {
        return ExerisRouteRegistry.deferred();
    }

    /**
     * Populates a deferred {@link ExerisRouteRegistry} from every {@link ExerisRoute} annotated
     * {@link ExerisRequestHandler} bean. Runs after singleton instantiation and before any
     * {@code SmartLifecycle} starts, so the table is complete before the kernel accepts a request. A
     * handler bean that cannot be resolved fails startup and is named; a registry the application
     * supplied already populated is left as it is.
     *
     * @param routeRegistry the registry to populate
     * @param ctx Spring application context
     * @return the populating callback
     */
    @Bean
    public SmartInitializingSingleton exerisRouteRegistryPopulator(ExerisRouteRegistry routeRegistry,
                                                                   ApplicationContext ctx) {
        return () -> {
            if (!routeRegistry.isPopulated()) {
                routeRegistry.populate(collectRoutes(ctx));
            }
        };
    }

    /**
     * Fails context start when the route registry is still unpopulated, before the kernel binds
     * ingress.
     *
     * @param routeRegistry the registry the dispatcher routes through
     * @return the start-time guard
     */
    @Bean
    @SuppressWarnings("unused")
    SmartLifecycle exerisRouteRegistryPopulatedGuard(ExerisRouteRegistry routeRegistry) {
        return new RouteRegistryPopulatedGuard(routeRegistry);
    }

    private static ExerisRouteRegistry.Builder collectRoutes(ApplicationContext ctx) {
        ExerisRouteRegistry.Builder builder = ExerisRouteRegistry.builder();
        for (String beanName : ctx.getBeanNamesForType(ExerisRequestHandler.class)) {
            ExerisRoute route = ctx.findAnnotationOnBean(beanName, ExerisRoute.class);
            if (route == null) {
                continue;
            }
            ExerisRequestHandler handler;
            try {
                handler = ctx.getBean(beanName, ExerisRequestHandler.class);
            } catch (BeansException ex) {
                throw new IllegalStateException("@ExerisRoute handler bean '" + beanName + "' for "
                        + route.method() + " " + route.path() + " could not be resolved", ex);
            }
            builder.register(route.method(), route.path(), handler);
        }
        return builder;
    }

    @Bean(name = FALLBACK_TELEMETRY_SINKS_BEAN_NAME, destroyMethod = "close")
    @ConditionalOnMissingBean(name = FALLBACK_TELEMETRY_SINKS_BEAN_NAME)
    @SuppressWarnings("unused")
    Supplier<List<TelemetrySink>> exerisFallbackTelemetrySinks(ObjectProvider<TelemetryProvider> telemetryProviders) {
        return buildFallbackSinksSupplier(telemetryProviders);
    }

    /**
     * Builds the {@link RequestScopeBinder} that the dispatcher uses to
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
     *
     * @see "ADR-029: ScopedValue Context Propagation"
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
     * <p>Used as the sink list {@link ExerisHttpDispatcher} binds to
     * {@code KernelProviders.TELEMETRY_SINKS} when no binding is present on the handler thread.
     * The kernel bootstrap does not bind that slot, so in a running application this is the list a
     * Pure Mode handler sees.
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
