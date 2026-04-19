/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.web.compat;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.core.MethodIntrospector;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.lang.NonNull;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.method.support.HandlerMethodArgumentResolverComposite;
import org.springframework.web.method.support.InvocableHandlerMethod;
import org.springframework.web.method.support.ModelAndViewContainer;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Compatibility-mode exception handler resolver. Checks controller-local
 * {@code @ExceptionHandler} methods first, then {@code @ControllerAdvice} beans.
 * No servlet types, no spring-webmvc.
 */
public final class ExerisExceptionHandlerResolver implements ApplicationContextAware, InitializingBean {

    private record ControllerAdviceEntry(Object bean, Method method) {}

    private final Map<Class<? extends Throwable>, List<ControllerAdviceEntry>> adviceHandlers = new HashMap<>();
    private final HandlerMethodArgumentResolverComposite argumentResolvers;
    private final List<HttpMessageConverter<?>> converters;
    private ApplicationContext applicationContext;

    public ExerisExceptionHandlerResolver(HandlerMethodArgumentResolverComposite argumentResolvers,
                                          List<HttpMessageConverter<?>> converters) {
        this.argumentResolvers = argumentResolvers;
        this.converters = List.copyOf(converters);
    }

    @Override
    public void setApplicationContext(@NonNull ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void afterPropertiesSet() {
        Map<String, Object> adviceBeans = applicationContext.getBeansWithAnnotation(ControllerAdvice.class);
        for (Object bean : adviceBeans.values()) {
            Map<Method, ExceptionHandler> methods = MethodIntrospector.selectMethods(bean.getClass(),
                    (Method m) -> AnnotatedElementUtils.findMergedAnnotation(m, ExceptionHandler.class));
            for (Map.Entry<Method, ExceptionHandler> entry : methods.entrySet()) {
                for (Class<? extends Throwable> exType : entry.getValue().value()) {
                    adviceHandlers.computeIfAbsent(exType, k -> new ArrayList<>())
                            .add(new ControllerAdviceEntry(bean, entry.getKey()));
                }
            }
        }
    }

    /**
     * Attempts to handle {@code ex} using:
     * <ol>
     *   <li>Controller-local {@code @ExceptionHandler} on {@code originalHandler}'s bean type.</li>
     *   <li>{@code @ControllerAdvice} beans registered at startup.</li>
     * </ol>
     * If nothing matches, rethrows as {@link RuntimeException}.
     */
    @SuppressWarnings("unchecked")
    public void resolve(Exception ex,
                        HandlerMethod originalHandler,
                        NativeWebRequest webRequest,
                        ExerisMvcServerHttpResponse springResponse) throws Exception {

        // 1. Controller-local @ExceptionHandler
        Map<Method, ExceptionHandler> localMethods = MethodIntrospector.selectMethods(
                originalHandler.getBeanType(),
                (Method m) -> AnnotatedElementUtils.findMergedAnnotation(m, ExceptionHandler.class));
        for (Map.Entry<Method, ExceptionHandler> entry : localMethods.entrySet()) {
            for (Class<? extends Throwable> exType : entry.getValue().value()) {
                if (exType.isAssignableFrom(ex.getClass())) {
                    invoke(originalHandler.getBean(), entry.getKey(), ex, webRequest, springResponse);
                    return;
                }
            }
        }

        // 2. @ControllerAdvice handlers — find most specific exception type match
        Class<? extends Throwable> bestMatch = null;
        ControllerAdviceEntry bestEntry = null;
        for (Map.Entry<Class<? extends Throwable>, List<ControllerAdviceEntry>> entry : adviceHandlers.entrySet()) {
            if (entry.getKey().isAssignableFrom(ex.getClass())) {
                if (bestMatch == null || bestMatch.isAssignableFrom(entry.getKey())) {
                    bestMatch = entry.getKey();
                    bestEntry = entry.getValue().get(0);
                }
            }
        }
        if (bestEntry != null) {
            invoke(bestEntry.bean(), bestEntry.method(), ex, webRequest, springResponse);
            return;
        }

        // 3. Nothing matched
        if (ex instanceof RuntimeException re) throw re;
        throw new RuntimeException(ex);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void invoke(Object bean, Method method, Exception ex,
                        NativeWebRequest webRequest,
                        ExerisMvcServerHttpResponse springResponse) throws Exception {
        InvocableHandlerMethod ihm = new InvocableHandlerMethod(bean, method);
        ihm.setHandlerMethodArgumentResolvers(argumentResolvers);

        ModelAndViewContainer mavContainer = new ModelAndViewContainer();
        webRequest.setAttribute("__exerisSpringResponse", springResponse, NativeWebRequest.SCOPE_REQUEST);

        Object returnValue = ihm.invokeForRequest(webRequest, mavContainer, ex);

        // Apply @ResponseStatus if present on the exception handler method
        org.springframework.web.bind.annotation.ResponseStatus responseStatus =
                AnnotatedElementUtils.findMergedAnnotation(method, org.springframework.web.bind.annotation.ResponseStatus.class);
        if (responseStatus != null) {
            springResponse.setStatusCode(responseStatus.code());
        }

        // Write return value if not already handled by a return value handler
        if (!mavContainer.isRequestHandled() && returnValue != null) {
            Class<?> valueType = returnValue.getClass();
            MediaType contentType = springResponse.getHeaders().getContentType();
            if (contentType == null) contentType = MediaType.APPLICATION_JSON;

            for (HttpMessageConverter converter : converters) {
                if (converter.canWrite(valueType, contentType)) {
                    converter.write(returnValue, contentType, springResponse);
                    return;
                }
            }
        }
    }
}
