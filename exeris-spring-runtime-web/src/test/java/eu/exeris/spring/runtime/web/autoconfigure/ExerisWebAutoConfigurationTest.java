/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.web.autoconfigure;

import java.lang.reflect.Proxy;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;

import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.http.HttpExchange;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpRequest;
import eu.exeris.kernel.spi.http.HttpVersion;
import eu.exeris.kernel.spi.telemetry.KernelEvent;
import eu.exeris.kernel.spi.telemetry.TelemetryConfig;
import eu.exeris.kernel.spi.telemetry.TelemetryProvider;
import eu.exeris.kernel.spi.telemetry.TelemetrySink;
import eu.exeris.spring.runtime.web.ExerisHttpDispatcher;
import eu.exeris.spring.runtime.web.ExerisRequestHandler;
import eu.exeris.spring.runtime.web.ExerisRoute;
import eu.exeris.spring.runtime.web.ExerisRouteRegistry;
import eu.exeris.spring.runtime.web.ExerisServerRequest;
import eu.exeris.spring.runtime.web.ExerisServerResponse;
import eu.exeris.spring.runtime.web.scope.ExerisRequestScope;
import eu.exeris.spring.runtime.web.scope.RequestScope;
import eu.exeris.spring.runtime.web.scope.RequestScopeBinder;
import eu.exeris.spring.runtime.web.scope.RequestScopeResolver;

import java.util.UUID;

class ExerisWebAutoConfigurationTest {

    @Test
    void pureMode_registersAnnotatedRoutes_andExposesDispatcherBean() {
        try (var context = createContext(Map.of("exeris.runtime.web.mode", "pure"))) {
            ExerisRouteRegistry routeRegistry = context.getBean(ExerisRouteRegistry.class);
            ExerisRequestHandler handler = context.getBean(HelloHandler.class);

            assertThat(routeRegistry.resolve(HttpMethod.GET, "/hello")).isSameAs(handler);
            assertThat(context.getBeanNamesForType(ExerisHttpDispatcher.class)).hasSize(1);
        }
    }

    @Test
    void pureMode_discoversTelemetryFallbackLazilyWhenProvidersExist() throws Exception {
        try (var context = createContext(
                Map.of("exeris.runtime.web.mode", "pure"),
                TelemetryHandlerConfig.class,
                TelemetryProviderConfig.class)) {
            ExerisHttpDispatcher dispatcher = context.getBean(ExerisHttpDispatcher.class);
            CountingTelemetryProvider provider = context.getBean(CountingTelemetryProvider.class);
            TelemetryAwareHandler handler = context.getBean(TelemetryAwareHandler.class);

            assertThat(provider.createSinksCalls()).isZero();

            dispatcher.handle(TestExchange.get(HttpMethod.GET, "/telemetry", anyHttpVersion()).proxy());
            dispatcher.handle(TestExchange.get(HttpMethod.GET, "/telemetry", anyHttpVersion()).proxy());

            assertThat(provider.createSinksCalls()).isEqualTo(1);
            assertThat(handler.telemetryBound()).isTrue();
        }
    }

    @Test
    void pureMode_leavesTelemetryUnboundWhenNoProvidersExist() throws Exception {
        try (var context = createContext(
                Map.of("exeris.runtime.web.mode", "pure"),
                TelemetryHandlerConfig.class)) {
            ExerisHttpDispatcher dispatcher = context.getBean(ExerisHttpDispatcher.class);
            TelemetryAwareHandler handler = context.getBean(TelemetryAwareHandler.class);

            dispatcher.handle(TestExchange.get(HttpMethod.GET, "/telemetry", anyHttpVersion()).proxy());

            assertThat(handler.telemetryBound()).isFalse();
        }
    }

    @Test
    void pureMode_isolatesTelemetryProviderFailures_andKeepsHealthyFallbackSinks() throws Exception {
        try (AnnotationConfigApplicationContext context = createContext(
                Map.of("exeris.runtime.web.mode", "pure"),
                TelemetryHandlerConfig.class,
                PartialFailureTelemetryProviderConfig.class)) {
            ExerisHttpDispatcher dispatcher = context.getBean(ExerisHttpDispatcher.class);
            TelemetryAwareHandler handler = context.getBean(TelemetryAwareHandler.class);
            CountingTelemetryProvider healthyProvider = context.getBean("healthyTelemetryProvider", CountingTelemetryProvider.class);
            FailingTelemetryProvider failingProvider = context.getBean(FailingTelemetryProvider.class);

            dispatcher.handle(TestExchange.get(HttpMethod.GET, "/telemetry", anyHttpVersion()).proxy());

            assertThat(handler.telemetryBound()).isTrue();
            assertThat(healthyProvider.createSinksCalls()).isEqualTo(1);
            assertThat(failingProvider.createSinksCalls()).isEqualTo(1);
        }
    }

    @Test
    void pureMode_closesLazilyCreatedFallbackTelemetrySinksOnContextClose() throws Exception {
        try (AnnotationConfigApplicationContext context = createContext(
                Map.of("exeris.runtime.web.mode", "pure"),
                TelemetryHandlerConfig.class,
                TelemetryProviderConfig.class)) {
            ExerisHttpDispatcher dispatcher = context.getBean(ExerisHttpDispatcher.class);
            CountingTelemetryProvider provider = context.getBean(CountingTelemetryProvider.class);

            dispatcher.handle(TestExchange.get(HttpMethod.GET, "/telemetry", anyHttpVersion()).proxy());

            assertThat(provider.createSinksCalls()).isEqualTo(1);
            assertThat(provider.closedSinkCount()).isZero();

            context.close();

            assertThat(provider.closedSinkCount()).isEqualTo(1);
        }
    }

    @Test
    void pureMode_allowsNamedFallbackTelemetrySupplierOverride() throws Exception {
        try (AnnotationConfigApplicationContext context = createContext(
                Map.of("exeris.runtime.web.mode", "pure"),
                TelemetryHandlerConfig.class,
                FallbackTelemetryOverrideConfig.class)) {
            ExerisHttpDispatcher dispatcher = context.getBean(ExerisHttpDispatcher.class);
            TelemetryAwareHandler handler = context.getBean(TelemetryAwareHandler.class);
            AtomicInteger supplierCalls = context.getBean("overrideFallbackSupplierCalls", AtomicInteger.class);

            dispatcher.handle(TestExchange.get(HttpMethod.GET, "/telemetry", anyHttpVersion()).proxy());
            dispatcher.handle(TestExchange.get(HttpMethod.GET, "/telemetry", anyHttpVersion()).proxy());

            assertThat(handler.telemetryBound()).isTrue();
            assertThat(supplierCalls).hasValue(1);
        }
    }

    // ---- Phase 3B-α: RequestScopeBinder three-state autoconfig matrix (ADR-029) ----

    /**
     * State 1: property disabled (default). Binder bean must be {@link RequestScopeBinder#noop()}'s
     * lambda — dispatch path is a zero-cost pass-through, request scope is unbound during handler.
     */
    @Test
    void pureMode_requestScopeBinder_defaultsToNoopWhenPropertyDisabled() throws Exception {
        try (var context = createContext(
                Map.of("exeris.runtime.web.mode", "pure"),
                ScopeAwareHandlerConfig.class)) {
            ExerisHttpDispatcher dispatcher = context.getBean(ExerisHttpDispatcher.class);
            ScopeAwareHandler handler = context.getBean(ScopeAwareHandler.class);

            assertThat(context.getBeanNamesForType(RequestScopeBinder.class)).hasSize(1);

            dispatcher.handle(TestExchange.get(HttpMethod.GET, "/scope", anyHttpVersion()).proxy());

            assertThat(handler.observedScopeBound())
                    .as("disabled property → ExerisRequestScope is unbound during handler invocation")
                    .isFalse();
        }
    }

    /**
     * State 2: property enabled but no {@link RequestScopeResolver} bean. Binder must fall back
     * to noop (logged INFO once); request scope still unbound during handler.
     */
    @Test
    void pureMode_requestScopeBinder_remainsNoopWhenEnabledButResolverAbsent() throws Exception {
        try (var context = createContext(
                Map.of(
                        "exeris.runtime.web.mode", "pure",
                        "exeris.runtime.context.scope.enabled", "true"),
                ScopeAwareHandlerConfig.class)) {
            ExerisHttpDispatcher dispatcher = context.getBean(ExerisHttpDispatcher.class);
            ScopeAwareHandler handler = context.getBean(ScopeAwareHandler.class);

            assertThat(context.getBeanNamesForType(RequestScopeResolver.class))
                    .as("no resolver bean registered for this state")
                    .isEmpty();
            assertThat(context.getBeanNamesForType(RequestScopeBinder.class)).hasSize(1);

            dispatcher.handle(TestExchange.get(HttpMethod.GET, "/scope", anyHttpVersion()).proxy());

            assertThat(handler.observedScopeBound())
                    .as("enabled property + missing resolver → scope still unbound (noop fallback)")
                    .isFalse();
        }
    }

    /**
     * State 3: property enabled + {@link RequestScopeResolver} bean present. Binder is the
     * resolving variant; request scope is bound during handler invocation with the values the
     * resolver produced from the request.
     */
    @Test
    void pureMode_requestScopeBinder_resolvesWhenEnabledAndResolverPresent() throws Exception {
        try (var context = createContext(
                Map.of(
                        "exeris.runtime.web.mode", "pure",
                        "exeris.runtime.context.scope.enabled", "true"),
                ScopeAwareHandlerConfig.class,
                FixedTenantResolverConfig.class)) {
            ExerisHttpDispatcher dispatcher = context.getBean(ExerisHttpDispatcher.class);
            ScopeAwareHandler handler = context.getBean(ScopeAwareHandler.class);
            FixedTenantResolverConfig.Resolver resolver = context.getBean(FixedTenantResolverConfig.Resolver.class);

            dispatcher.handle(TestExchange.get(HttpMethod.GET, "/scope", anyHttpVersion()).proxy());

            assertThat(handler.observedScopeBound())
                    .as("enabled property + resolver bean → scope bound during handler invocation")
                    .isTrue();
            assertThat(handler.observedTenant())
                    .as("handler sees the tenant produced by the resolver")
                    .isEqualTo(resolver.tenant());
            assertThat(resolver.invocations())
                    .as("resolver is called exactly once per dispatch")
                    .isEqualTo(1);
        }
    }

    @Configuration
    static class ScopeAwareHandlerConfig {

        @Bean
        @SuppressWarnings("unused")
        ScopeAwareHandler scopeAwareHandler() {
            return new ScopeAwareHandler();
        }
    }

    @Configuration
    static class FixedTenantResolverConfig {

        @Bean
        @SuppressWarnings("unused")
        Resolver fixedTenantResolver() {
            return new Resolver();
        }

        static final class Resolver implements RequestScopeResolver {
            private final UUID tenant = UUID.randomUUID();
            private final AtomicInteger invocations = new AtomicInteger();

            @Override
            public RequestScope resolve(ExerisServerRequest request) {
                invocations.incrementAndGet();
                return new RequestScope(tenant, "corr-fixed", null);
            }

            UUID tenant() {
                return tenant;
            }

            int invocations() {
                return invocations.get();
            }
        }
    }

    @ExerisRoute(method = HttpMethod.GET, path = "/scope")
    static class ScopeAwareHandler implements ExerisRequestHandler {

        private final AtomicReference<Boolean> scopeBound = new AtomicReference<>(false);
        private final AtomicReference<UUID> observedTenant = new AtomicReference<>();

        @Override
        public ExerisServerResponse handle(ExerisServerRequest request) {
            scopeBound.set(ExerisRequestScope.current().isPresent());
            observedTenant.set(ExerisRequestScope.tenantId().orElse(null));
            return ExerisServerResponse.ok().body("scope");
        }

        boolean observedScopeBound() {
            return Boolean.TRUE.equals(scopeBound.get());
        }

        UUID observedTenant() {
            return observedTenant.get();
        }
    }

    // ---- end Phase 3B-α matrix ----

    @SuppressWarnings("null")
    private AnnotationConfigApplicationContext createContext(Map<String, Object> properties, Class<?>... extraConfigs) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        Map<String, Object> safeProperties = Map.copyOf(java.util.Objects.requireNonNull(properties, "properties"));
        context.getEnvironment().getPropertySources().addFirst(
                new MapPropertySource("testProps", safeProperties));
        context.register(ExerisWebAutoConfiguration.class, ExerisCompatAutoConfiguration.class, TestConfig.class);
        if (extraConfigs != null && extraConfigs.length > 0) {
            context.register(extraConfigs);
        }
        context.refresh();
        return context;
    }

    @Configuration
    static class TestConfig {

        @Bean
        @SuppressWarnings("unused")
        HelloHandler helloHandler() {
            return new HelloHandler();
        }
    }

    @ExerisRoute(method = HttpMethod.GET, path = "/hello")
    static class HelloHandler implements ExerisRequestHandler {
        @Override
        public ExerisServerResponse handle(ExerisServerRequest request) {
            return ExerisServerResponse.ok().body("hello");
        }
    }

    @Configuration
    static class TelemetryHandlerConfig {

        @Bean
        @SuppressWarnings("unused")
        TelemetryAwareHandler telemetryAwareHandler() {
            return new TelemetryAwareHandler();
        }
    }

    @Configuration
    static class TelemetryProviderConfig {

        @Bean
        @SuppressWarnings("unused")
        CountingTelemetryProvider countingTelemetryProvider() {
            return new CountingTelemetryProvider();
        }
    }

    @Configuration
    static class PartialFailureTelemetryProviderConfig {

        @Bean("healthyTelemetryProvider")
        @SuppressWarnings("unused")
        CountingTelemetryProvider healthyTelemetryProvider() {
            return new CountingTelemetryProvider();
        }

        @Bean
        @SuppressWarnings("unused")
        FailingTelemetryProvider failingTelemetryProvider() {
            return new FailingTelemetryProvider();
        }
    }

    @Configuration
    static class FallbackTelemetryOverrideConfig {

        @Bean("overrideFallbackSupplierCalls")
        @SuppressWarnings("unused")
        AtomicInteger overrideFallbackSupplierCalls() {
            return new AtomicInteger();
        }

        @Bean(name = "exerisFallbackTelemetrySinks")
        @SuppressWarnings("unused")
        Supplier<List<TelemetrySink>> exerisFallbackTelemetrySinksOverride(AtomicInteger overrideFallbackSupplierCalls) {
            return () -> {
                overrideFallbackSupplierCalls.incrementAndGet();
                return List.of(new OverrideTelemetrySink());
            };
        }
    }

    @ExerisRoute(method = HttpMethod.GET, path = "/telemetry")
    static class TelemetryAwareHandler implements ExerisRequestHandler {

        private final AtomicReference<Boolean> telemetryBound = new AtomicReference<>(false);

        @Override
        public ExerisServerResponse handle(ExerisServerRequest request) {
            telemetryBound.set(KernelProviders.TELEMETRY_SINKS.isBound());
            return ExerisServerResponse.ok().body("telemetry");
        }

        boolean telemetryBound() {
            return Boolean.TRUE.equals(telemetryBound.get());
        }
    }

    static final class CountingTelemetryProvider implements TelemetryProvider {

        private final AtomicInteger createSinksCalls = new AtomicInteger();
        private final AtomicInteger closedSinkCount = new AtomicInteger();

        @Override
        public List<TelemetrySink> createSinks(TelemetryConfig config) {
            createSinksCalls.incrementAndGet();
            return List.of(new CountingTelemetrySink(closedSinkCount));
        }

        @Override
        public String providerName() {
            return "counting-test-provider";
        }

        @Override
        public int priority() {
            return 10;
        }

        int createSinksCalls() {
            return createSinksCalls.get();
        }

        int closedSinkCount() {
            return closedSinkCount.get();
        }
    }

    static final class FailingTelemetryProvider implements TelemetryProvider {

        private final AtomicInteger createSinksCalls = new AtomicInteger();

        @Override
        public List<TelemetrySink> createSinks(TelemetryConfig config) {
            createSinksCalls.incrementAndGet();
            throw new IllegalStateException("expected per-provider fallback failure");
        }

        @Override
        public String providerName() {
            return "failing-test-provider";
        }

        @Override
        public int priority() {
            return 5;
        }

        int createSinksCalls() {
            return createSinksCalls.get();
        }
    }

    private static final class CountingTelemetrySink implements TelemetrySink {

        private final AtomicInteger closedSinkCount;

        private CountingTelemetrySink(AtomicInteger closedSinkCount) {
            this.closedSinkCount = closedSinkCount;
        }

        @Override
        public void emit(KernelEvent event) {
            // No-op for provider wiring verification in tests.
        }

        @Override
        public void increment(String metric, long value) {
            // No-op for provider wiring verification in tests.
        }

        @Override
        public void gauge(String metric, long value) {
            // No-op for provider wiring verification in tests.
        }

        @Override
        public void latency(String metric, long value) {
            // No-op for provider wiring verification in tests.
        }

        @Override
        public String sinkName() {
            return "counting-test-sink";
        }

        @Override
        public void close() {
            closedSinkCount.incrementAndGet();
        }
    }

    private static final class OverrideTelemetrySink implements TelemetrySink {

        @Override
        public void emit(KernelEvent event) {
            // No-op for explicit fallback override verification.
        }

        @Override
        public void close() {
            // No-op for explicit fallback override verification.
        }

        @Override
        public void increment(String metric, long value) {
            // No-op for explicit fallback override verification.
        }

        @Override
        public void gauge(String metric, long value) {
            // No-op for explicit fallback override verification.
        }

        @Override
        public void latency(String metric, long value) {
            // No-op for explicit fallback override verification.
        }

        @Override
        public String sinkName() {
            return "override-test-sink";
        }
    }

    private static HttpVersion anyHttpVersion() {
        Object[] enumConstants = HttpVersion.class.getEnumConstants();
        if (enumConstants != null && enumConstants.length > 0) {
            return (HttpVersion) enumConstants[0];
        }

        for (var field : HttpVersion.class.getDeclaredFields()) {
            if (field.getType() == HttpVersion.class && java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                try {
                    field.setAccessible(true);
                    Object value = field.get(null);
                    if (value instanceof HttpVersion version) {
                        return version;
                    }
                } catch (IllegalAccessException _) {
                    // Ignore inaccessible constants while probing a usable test HttpVersion.
                }
            }
        }
        throw new IllegalStateException("Unable to obtain any HttpVersion constant for test exchange");
    }

    private static final class TestExchange {

        private final HttpExchange proxy;

        private TestExchange(HttpRequest request) {
            this.proxy = (HttpExchange) Proxy.newProxyInstance(
                    HttpExchange.class.getClassLoader(),
                    new Class<?>[]{HttpExchange.class},
                    (proxyInstance, method, args) -> switch (method.getName()) {
                        case "request" -> request;
                        case "respond" -> null;
                        case "toString" -> "TestExchange";
                        case "hashCode" -> System.identityHashCode(proxyInstance);
                        case "equals" -> proxyInstance == args[0];
                        default -> defaultValue(method.getReturnType());
                    }
            );
        }

        static TestExchange get(HttpMethod method, String path, HttpVersion version) {
            HttpRequest request = createHttpRequest(method, path, version);
            return new TestExchange(request);
        }

        HttpExchange proxy() {
            return proxy;
        }

        private static HttpRequest createHttpRequest(HttpMethod method, String path, HttpVersion version) {
            var constructors = HttpRequest.class.getDeclaredConstructors();
            for (var constructor : constructors) {
                try {
                    constructor.setAccessible(true);
                    Object[] args = buildConstructorArgs(constructor.getGenericParameterTypes(), method, path, version);
                    Object candidate = constructor.newInstance(args);
                    if (candidate instanceof HttpRequest request
                            && method.equals(request.method())
                            && path.equals(request.path())) {
                        return request;
                    }
                } catch (ReflectiveOperationException | IllegalArgumentException _) {
                    // Ignore constructor shapes that do not match this lightweight test request.
                }
            }
            throw new IllegalStateException("Unable to construct HttpRequest for test exchange");
        }

        private static Object[] buildConstructorArgs(Type[] parameterTypes,
                                                     HttpMethod method,
                                                     String path,
                                                     HttpVersion version) {
            Object[] args = new Object[parameterTypes.length];
            boolean pathAssigned = false;
            for (int i = 0; i < parameterTypes.length; i++) {
                Class<?> raw = rawClass(parameterTypes[i]);
                if (raw == HttpMethod.class) {
                    args[i] = method;
                } else if (raw == HttpVersion.class) {
                    args[i] = version;
                } else if (raw == String.class) {
                    if (!pathAssigned) {
                        args[i] = path;
                        pathAssigned = true;
                    } else {
                        args[i] = "";
                    }
                } else if (raw == Optional.class) {
                    args[i] = Optional.empty();
                } else if (raw == List.class) {
                    args[i] = List.of();
                } else {
                    args[i] = defaultValue(raw);
                }
            }
            return args;
        }

        private static Class<?> rawClass(Type type) {
            if (type instanceof Class<?> cls) {
                return cls;
            }
            if (type instanceof java.lang.reflect.ParameterizedType parameterizedType
                    && parameterizedType.getRawType() instanceof Class<?> raw) {
                return raw;
            }
            throw new IllegalArgumentException("Unsupported constructor parameter type: " + type);
        }

        private static Object defaultValue(Class<?> returnType) {
            if (!returnType.isPrimitive()) {
                return null;
            }
            if (returnType == boolean.class) {
                return false;
            }
            if (returnType == byte.class) {
                return (byte) 0;
            }
            if (returnType == short.class) {
                return (short) 0;
            }
            if (returnType == int.class) {
                return 0;
            }
            if (returnType == long.class) {
                return 0L;
            }
            if (returnType == float.class) {
                return 0f;
            }
            if (returnType == double.class) {
                return 0d;
            }
            if (returnType == char.class) {
                return '\0';
            }
            return null;
        }
    }
}