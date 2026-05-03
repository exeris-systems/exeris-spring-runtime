/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.web.autoconfigure;

import java.util.List;

import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.validation.SmartValidator;
import org.springframework.web.method.support.HandlerMethodArgumentResolverComposite;
import org.springframework.web.method.support.HandlerMethodReturnValueHandlerComposite;

import eu.exeris.spring.runtime.web.ExerisErrorMapper;
import eu.exeris.spring.runtime.web.compat.ExerisCompatDispatcher;
import eu.exeris.spring.runtime.web.compat.ExerisExceptionHandlerResolver;
import eu.exeris.spring.runtime.web.compat.ExerisHandlerMethodRegistry;
import eu.exeris.spring.runtime.web.compat.ExerisSpringMvcBridge;
import eu.exeris.spring.runtime.web.compat.context.ExerisThreadLocalBridge;
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
public class ExerisCompatAutoConfiguration {

    // 1. Message converter
    @Bean
    @ConditionalOnMissingBean(MappingJackson2HttpMessageConverter.class)
    public MappingJackson2HttpMessageConverter exerisCompatJacksonConverter() {
        return new MappingJackson2HttpMessageConverter();
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
    public ExerisRequestParamArgumentResolver exerisRequestParamArgumentResolver() {
        return new ExerisRequestParamArgumentResolver();
    }

    @Bean
    @ConditionalOnMissingBean
    public ExerisPathVariableArgumentResolver exerisPathVariableArgumentResolver() {
        return new ExerisPathVariableArgumentResolver();
    }

    @Bean
    @ConditionalOnMissingBean
    public ExerisRequestHeaderArgumentResolver exerisRequestHeaderArgumentResolver() {
        return new ExerisRequestHeaderArgumentResolver();
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

    // 14. Error mapper
    @Bean
    @ConditionalOnMissingBean
    public ExerisErrorMapper exerisCompatErrorMapper() {
        return new ExerisErrorMapper();
    }

    // 15. Security context filter — active only when JwtDecoder is on classpath
    //     and no full SecurityFilterChain is present.
    @Configuration
    @ConditionalOnClass(name = "org.springframework.security.oauth2.jwt.JwtDecoder")
    static class SecurityFilterConfiguration {

        @Bean
        @ConditionalOnBean(type = "org.springframework.security.oauth2.jwt.JwtDecoder")
        @ConditionalOnMissingBean(ExerisSecurityContextFilter.class)
        @Conditional(NoSecurityFilterChainCondition.class)
        public ExerisSecurityContextFilter exerisSecurityContextFilter(
                org.springframework.security.oauth2.jwt.JwtDecoder jwtDecoder) {
            return new ExerisSecurityContextFilter(jwtDecoder);
        }

        /**
         * Keeps the compatibility fallback filter disabled when a full Spring Security
         * chain is already present, without loading servlet-only types into the compat path.
         */
        static final class NoSecurityFilterChainCondition implements Condition {

            private static final String SECURITY_FILTER_CHAIN_TYPE = "org.springframework.security.web.SecurityFilterChain";
            private static final String DEFAULT_CHAIN_BEAN_NAME = "springSecurityFilterChain";

            @Override
            public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
                if (context.getBeanFactory() == null) {
                    return true;
                }
                if (context.getBeanFactory().containsBean(DEFAULT_CHAIN_BEAN_NAME)) {
                    return false;
                }

                for (String beanName : context.getBeanFactory().getBeanDefinitionNames()) {
                    var definition = context.getBeanFactory().getBeanDefinition(beanName);
                    if (SECURITY_FILTER_CHAIN_TYPE.equals(definition.getBeanClassName())) {
                        return false;
                    }
                    Object objectType = definition.getAttribute(FactoryBean.OBJECT_TYPE_ATTRIBUTE);
                    if (objectType instanceof Class<?> objectClass
                            && SECURITY_FILTER_CHAIN_TYPE.equals(objectClass.getName())) {
                        return false;
                    }
                    if (objectType instanceof String typeName
                            && SECURITY_FILTER_CHAIN_TYPE.equals(typeName)) {
                        return false;
                    }
                    try {
                        Class<?> beanType = context.getBeanFactory().getType(beanName, false);
                        if (beanType != null && SECURITY_FILTER_CHAIN_TYPE.equals(beanType.getName())) {
                            return false;
                        }
                    } catch (Throwable ex) {
                        // Intentionally ignore type-introspection failures from servlet-only security classes.
                    }
                }
                return true;
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

    // 16. Compat dispatcher
    @Bean
    @ConditionalOnMissingBean
    public ExerisCompatDispatcher exerisCompatDispatcher(
            ExerisSpringMvcBridge bridge,
            ExerisErrorMapper errorMapper,
            @Autowired(required = false) ExerisSecurityContextFilter securityFilter) {
        return new ExerisCompatDispatcher(bridge, errorMapper, securityFilter);
    }
}

