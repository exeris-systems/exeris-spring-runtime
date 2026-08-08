/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.web.autoconfigure;

import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.ConversionService;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.validation.SmartValidator;
import org.springframework.web.method.support.HandlerMethodArgumentResolverComposite;
import org.springframework.web.method.support.HandlerMethodReturnValueHandlerComposite;

import eu.exeris.spring.boot.autoconfigure.ExerisRuntimeLifecycle;
import eu.exeris.spring.runtime.web.ExerisErrorMapper;
import eu.exeris.spring.runtime.web.ExerisErrorStatusResolver;
import eu.exeris.spring.boot.autoconfigure.compat.CompatibilityMode;
import eu.exeris.spring.runtime.web.compat.ExerisCompatJsonConverterFactory;
import eu.exeris.spring.runtime.web.compat.security.SecurityFilterChainDetector;
import eu.exeris.spring.runtime.web.compat.security.UnenforcedSecurityFilterChainCheck;
import eu.exeris.spring.runtime.web.compat.ExerisCompatDispatcher;
import eu.exeris.spring.runtime.web.compat.ExerisExceptionHandlerResolver;
import eu.exeris.spring.runtime.web.compat.ExerisHandlerMethodRegistry;
import eu.exeris.spring.runtime.web.compat.ExerisSpringMvcBridge;
import eu.exeris.spring.runtime.web.compat.context.ExerisThreadLocalBridge;
import eu.exeris.spring.runtime.web.scope.KernelProviderBinder;
import eu.exeris.spring.runtime.web.compat.filter.ExerisSecurityContextFilter;
import eu.exeris.spring.runtime.web.compat.handler.ExerisResponseBodyReturnValueHandler;
import eu.exeris.spring.runtime.web.compat.handler.ExerisResponseEntityReturnValueHandler;
import eu.exeris.spring.runtime.web.compat.resolver.ExerisAuthenticationArgumentResolver;
import eu.exeris.spring.runtime.web.compat.resolver.ExerisPathVariableArgumentResolver;
import eu.exeris.spring.runtime.web.compat.resolver.ExerisRequestBodyArgumentResolver;
import eu.exeris.spring.runtime.web.compat.resolver.ExerisRequestHeaderArgumentResolver;
import eu.exeris.spring.runtime.web.compat.resolver.ExerisRequestParamArgumentResolver;

/**
 * Compatibility-mode wiring for the Spring MVC compatibility bridge.
 * Active only when {@code exeris.runtime.web.mode=compatibility}.
 *
 * <p>Does NOT declare {@code RequestMappingHandlerMapping} or
 * {@code RequestMappingHandlerAdapter} — those are from spring-webmvc and are banned.
 */
@AutoConfiguration
@ConditionalOnClass(ExerisCompatDispatcher.class)
@ConditionalOnProperty(prefix = "exeris.runtime.web", name = "mode", havingValue = "compatibility")
@CompatibilityMode
public class ExerisCompatAutoConfiguration {

    // 1. Message converter.
    //
    // Declared as HttpMessageConverter<?> rather than MappingJackson2HttpMessageConverter because the
    // two Spring Boot lines ship different Jackson majors: SB3 has Jackson 2, SB4 has Jackson 3 and
    // only Jackson 2's *annotations*. The Jackson 2 converter class still compiles on both lines but
    // cannot be constructed under SB4 — see ExerisCompatJsonConverterFactory. Consumers take
    // List<HttpMessageConverter<?>>, so the narrower declared type bought nothing anyway.
    //
    // The @ConditionalOnMissingBean still names the Jackson 2 converter, which is exactly what it
    // meant before: "stand down if the application declared its own JSON converter". On SB4 an
    // application-declared Jackson 3 converter does not suppress ours; both land in the list, which is
    // additive and picks by media type, so the effect is redundancy rather than conflict.
    @Bean
    @Conditional(OnAnyJacksonPresentCondition.class)
    @ConditionalOnMissingBean(MappingJackson2HttpMessageConverter.class)
    public HttpMessageConverter<?> exerisCompatJacksonConverter() {
        return ExerisCompatJsonConverterFactory.create(getClass().getClassLoader())
                .orElseThrow(() -> new IllegalStateException(
                        "A Jackson databind is on the classpath but no matching Spring JSON converter "
                                + "could be constructed. Declare an HttpMessageConverter bean explicitly."));
    }

    /**
     * Matches when some Jackson databind is present, on either major.
     *
     * <p>Not {@code @ConditionalOnClass}: the class to look for differs per Spring Boot line, and a
     * single annotation cannot express "either of these two".
     */
    static final class OnAnyJacksonPresentCondition implements Condition {

        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            ClassLoader classLoader = context.getClassLoader() != null
                    ? context.getClassLoader()
                    : ExerisCompatAutoConfiguration.class.getClassLoader();
            return ExerisCompatJsonConverterFactory.isAvailable(classLoader);
        }
    }

    // 2. Handler registry
    @Bean
    @ConditionalOnMissingBean
    public ExerisHandlerMethodRegistry exerisHandlerMethodRegistry() {
        return new ExerisHandlerMethodRegistry();
    }

    // 3-6. Argument resolvers
    @Bean
    @ConditionalOnMissingBean
    public ExerisRequestBodyArgumentResolver exerisRequestBodyArgumentResolver(
            List<HttpMessageConverter<?>> converters,
            @Autowired(required = false) SmartValidator validator) {
        return new ExerisRequestBodyArgumentResolver(converters, validator);
    }

    @Bean
    @ConditionalOnMissingBean
    public ExerisRequestParamArgumentResolver exerisRequestParamArgumentResolver(
            ObjectProvider<ConversionService> conversionServiceProvider) {
        return new ExerisRequestParamArgumentResolver(resolveConversionService(conversionServiceProvider));
    }

    @Bean
    @ConditionalOnMissingBean
    public ExerisPathVariableArgumentResolver exerisPathVariableArgumentResolver(
            ObjectProvider<ConversionService> conversionServiceProvider) {
        return new ExerisPathVariableArgumentResolver(resolveConversionService(conversionServiceProvider));
    }

    @Bean
    @ConditionalOnMissingBean
    public ExerisRequestHeaderArgumentResolver exerisRequestHeaderArgumentResolver(
            ObjectProvider<ConversionService> conversionServiceProvider) {
        return new ExerisRequestHeaderArgumentResolver(resolveConversionService(conversionServiceProvider));
    }

    /**
     * Reuses the application's primary {@link ConversionService} bean if present so that
     * user-registered {@code Converter<String, T>} beans are honoured. Falls back to
     * {@link ApplicationConversionService#getSharedInstance()} — the same service
     * Spring Boot uses for property binding — to keep enum / date-time / UUID parity
     * with Spring MVC even in stripped-down compatibility deployments.
     *
     * <p><b>Caveat:</b> the shared instance is a process-wide mutable singleton —
     * any library that calls {@code addConverter(...)} on it will be observable from
     * every compat resolver using this fallback. Applications that need isolation
     * should declare their own {@link ConversionService} bean; the {@code getIfUnique}
     * branch picks it up in preference to the shared instance.
     */
    private static ConversionService resolveConversionService(ObjectProvider<ConversionService> provider) {
        ConversionService unique = provider.getIfUnique();
        return unique != null ? unique : ApplicationConversionService.getSharedInstance();
    }

    // 7. Composite argument resolver
    @Bean
    @ConditionalOnMissingBean
    public HandlerMethodArgumentResolverComposite exerisArgumentResolverComposite(
            ExerisRequestBodyArgumentResolver bodyResolver,
            ExerisRequestParamArgumentResolver paramResolver,
            ExerisPathVariableArgumentResolver pathVarResolver,
            ExerisRequestHeaderArgumentResolver headerResolver,
            @Autowired(required = false) ExerisAuthenticationArgumentResolver authResolver) {
        HandlerMethodArgumentResolverComposite composite = new HandlerMethodArgumentResolverComposite();
        composite.addResolvers(bodyResolver, paramResolver, pathVarResolver, headerResolver);
        if (authResolver != null) {
            composite.addResolvers(authResolver);
        }
        return composite;
    }

    // 8-9. Return value handlers
    @Bean
    @ConditionalOnMissingBean
    public ExerisResponseBodyReturnValueHandler exerisResponseBodyReturnValueHandler(
            List<HttpMessageConverter<?>> converters) {
        return new ExerisResponseBodyReturnValueHandler(converters);
    }

    @Bean
    @ConditionalOnMissingBean
    public ExerisResponseEntityReturnValueHandler exerisResponseEntityReturnValueHandler(
            List<HttpMessageConverter<?>> converters) {
        return new ExerisResponseEntityReturnValueHandler(converters);
    }

    // 10. Composite return value handler (entity first, then body)
    @Bean
    @ConditionalOnMissingBean
    public HandlerMethodReturnValueHandlerComposite exerisReturnValueHandlerComposite(
            ExerisResponseEntityReturnValueHandler entityHandler,
            ExerisResponseBodyReturnValueHandler bodyHandler) {
        HandlerMethodReturnValueHandlerComposite composite = new HandlerMethodReturnValueHandlerComposite();
        composite.addHandlers(List.of(entityHandler, bodyHandler));
        return composite;
    }

    // 11. Exception handler resolver
    @Bean
    @ConditionalOnMissingBean
    public ExerisExceptionHandlerResolver exerisExceptionHandlerResolver(
            HandlerMethodArgumentResolverComposite argumentResolvers,
            List<HttpMessageConverter<?>> converters) {
        return new ExerisExceptionHandlerResolver(argumentResolvers, converters);
    }

    // 12. ThreadLocal bridge
    @Bean
    @ConditionalOnMissingBean
    public ExerisThreadLocalBridge exerisThreadLocalBridge() {
        return new ExerisThreadLocalBridge();
    }

    // 13. MVC bridge
    @Bean
    @ConditionalOnMissingBean
    public ExerisSpringMvcBridge exerisSpringMvcBridge(
            ExerisHandlerMethodRegistry registry,
            HandlerMethodArgumentResolverComposite argumentResolvers,
            HandlerMethodReturnValueHandlerComposite returnValueHandlers,
            ExerisExceptionHandlerResolver exceptionHandlerResolver,
            ExerisThreadLocalBridge threadLocalBridge) {
        return new ExerisSpringMvcBridge(registry, argumentResolvers, returnValueHandlers,
                exceptionHandlerResolver, threadLocalBridge);
    }

    // 14. Error mapper. Resolvers turn specific exceptions into specific statuses before the 500
    //     fallback — notably Spring Security's authentication/authorization failures, which were
    //     otherwise reported as server faults.
    @Bean
    @ConditionalOnMissingBean
    public ExerisErrorMapper exerisCompatErrorMapper(
            ObjectProvider<ExerisErrorStatusResolver> statusResolvers) {
        return new ExerisErrorMapper(statusResolvers.orderedStream().toList());
    }

    // 14b. Fail-fast on a SecurityFilterChain this runtime cannot execute. Registered as a static
    //      @Bean so it is instantiated as a BeanFactoryPostProcessor rather than as an ordinary
    //      singleton — the check must run before anything binds a port.
    @Bean
    public static UnenforcedSecurityFilterChainCheck exerisUnenforcedSecurityFilterChainCheck() {
        return new UnenforcedSecurityFilterChainCheck();
    }

    // 15a. The compatibility JwtDecoder that this filter depends on (ADR-041) lives in a SEPARATE
    //      auto-configuration, ExerisCompatJwtDecoderAutoConfiguration, ordered @AutoConfiguration(
    //      before = ExerisCompatAutoConfiguration.class). That ordering is what lets the
    //      @ConditionalOnBean(JwtDecoder) below observe the re-activated decoder under
    //      web-application-type=none — @ConditionalOnBean does not reliably see a bean from a sibling
    //      nested @Configuration of the same auto-config, so it must not be nested here.

    // 15b. Security context filter — active only when JwtDecoder is on classpath
    //      and no full SecurityFilterChain is present.
    @Configuration
    @ConditionalOnClass(name = "org.springframework.security.oauth2.jwt.JwtDecoder")
    static class SecurityFilterConfiguration {

        @Bean
        @ConditionalOnBean(type = "org.springframework.security.oauth2.jwt.JwtDecoder")
        @ConditionalOnMissingBean(ExerisSecurityContextFilter.class)
        @Conditional(NoSecurityFilterChainCondition.class)
        public ExerisSecurityContextFilter exerisSecurityContextFilter(
                org.springframework.security.oauth2.jwt.JwtDecoder jwtDecoder,
                ObjectProvider<org.springframework.core.convert.converter.Converter<
                        org.springframework.security.oauth2.jwt.Jwt,
                        ? extends org.springframework.security.authentication.AbstractAuthenticationToken>>
                        jwtAuthenticationConverter,
                Environment environment) {
            // Honour an application-registered Converter<Jwt, ? extends AbstractAuthenticationToken>
            // (e.g. a JwtAuthenticationConverter mapping realm_access.roles or a custom prefix);
            // fall back to the scope-only default when none is present. Same compat pattern as the
            // other "honour the user bean, don't hardcode the default" wirings in this module.
            // If a brownfield app registers TWO such converter beans, getIfAvailable(Supplier)
            // throws NoUniqueBeanDefinitionException at start-up — intentional fail-fast: the
            // authority mapping is ambiguous and the app must mark one @Primary (mirrors how
            // Spring Security's own resource server resolves the converter).
            //
            // reject-invalid-token defaults to true: a presented-but-invalid credential is answered
            // with 401 instead of being downgraded to anonymous. The property exists for a migration
            // that provably depends on the old permissive behaviour; it is fail-open and the class
            // Javadoc says so.
            boolean rejectInvalidToken = environment.getProperty(
                    REJECT_INVALID_TOKEN_PROPERTY, Boolean.class, Boolean.TRUE);
            return new ExerisSecurityContextFilter(
                    jwtDecoder,
                    jwtAuthenticationConverter.getIfAvailable(
                            org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter::new),
                    rejectInvalidToken);
        }

        /** Opts out of 401-on-invalid-token, restoring pre-0.7.0 continue-as-anonymous behaviour. */
        static final String REJECT_INVALID_TOKEN_PROPERTY =
                "exeris.runtime.web.compat.security.reject-invalid-token";

        /**
         * Keeps the compatibility fallback filter disabled when a full Spring Security
         * chain is already present, without loading servlet-only types into the compat path.
         *
         * <p>Standing the filter down is only half the story: the chain will not run either.
         * {@link UnenforcedSecurityFilterChainCheck} fails startup for exactly that case, and both
         * share {@link SecurityFilterChainDetector} so they cannot disagree about whether a chain
         * is present.
         */
        static final class NoSecurityFilterChainCondition implements Condition {

            @Override
            public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
                return SecurityFilterChainDetector.detect(context.getBeanFactory()).isEmpty();
            }
        }
    }

    // 3b. Authentication argument resolver — active only when spring-security-core is present
    @Configuration
    @ConditionalOnClass(name = "org.springframework.security.core.Authentication")
    static class AuthenticationResolverConfiguration {

        @Bean
        @ConditionalOnMissingBean
        public ExerisAuthenticationArgumentResolver exerisAuthenticationArgumentResolver() {
            return new ExerisAuthenticationArgumentResolver();
        }
    }

    /**
     * Builds the {@link KernelProviderBinder} the compat dispatcher uses to re-bind kernel
     * provider {@code ScopedValue} slots (persistence engine, memory allocator) onto the request
     * handler thread, so JPA/Hibernate → {@code ExerisDataSource} and the response codec resolve
     * them. The externally-supplied {@code HttpHandler} runs on the transport carrier thread,
     * which does not inherit the kernel bootstrap scope.
     *
     * <p>The {@link ExerisRuntimeLifecycle} bean is resolved <em>lazily</em>, per request, via the
     * {@link ObjectProvider} — NOT at bean construction — to avoid a construction-time cycle
     * ({@code ExerisRuntimeLifecycle} → {@code HttpHandler} dispatcher → this binder). When no
     * lifecycle is present the suppliers yield empty and the binder is a zero-cost pass-through.
     */
    @Bean
    @ConditionalOnMissingBean
    public KernelProviderBinder exerisKernelProviderBinder(
            ObjectProvider<ExerisRuntimeLifecycle> lifecycleProvider) {
        return KernelProviderBinder.capturing(
                () -> Optional.ofNullable(lifecycleProvider.getIfAvailable())
                        .flatMap(ExerisRuntimeLifecycle::getPersistenceEngine),
                () -> Optional.ofNullable(lifecycleProvider.getIfAvailable())
                        .flatMap(ExerisRuntimeLifecycle::getMemoryAllocator));
    }

    // 16. Compat dispatcher
    @Bean
    @ConditionalOnMissingBean
    public ExerisCompatDispatcher exerisCompatDispatcher(
            ExerisSpringMvcBridge bridge,
            ExerisErrorMapper errorMapper,
            @Autowired(required = false) ExerisSecurityContextFilter securityFilter,
            KernelProviderBinder kernelProviderBinder) {
        return new ExerisCompatDispatcher(bridge, errorMapper, securityFilter, kernelProviderBinder);
    }
}

