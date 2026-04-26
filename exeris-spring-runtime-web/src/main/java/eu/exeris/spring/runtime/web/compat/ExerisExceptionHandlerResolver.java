/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.web.compat;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.core.ExceptionDepthComparator;
import org.springframework.core.MethodIntrospector;
import org.springframework.core.Ordered;
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

/**
 * Compatibility-mode exception handler resolver. Checks controller-local
 * {@code @ExceptionHandler} methods first, then {@code @ControllerAdvice} beans.
 * No servlet types, no spring-webmvc.
 */
public final class ExerisExceptionHandlerResolver implements ApplicationContextAware, InitializingBean {

    private record ExceptionHandlerMethod(Object bean,
                                          Class<?> beanType,
                                          Method method,
                                          List<Class<? extends Throwable>> handledTypes,
                                          int order) {
    }

    private final HandlerMethodArgumentResolverComposite argumentResolvers;
    private final List<HttpMessageConverter<?>> converters;
    private List<ExceptionHandlerMethod> adviceHandlers = List.of();
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
    public void afterPropertiesSet() {
        if (applicationContext == null) {
            this.adviceHandlers = List.of();
            return;
        }

        List<ExceptionHandlerMethod> discovered = new ArrayList<>();
        Map<String, Object> adviceBeans = applicationContext.getBeansWithAnnotation(ControllerAdvice.class);
        for (Map.Entry<String, Object> adviceEntry : adviceBeans.entrySet()) {
            Object bean = adviceEntry.getValue();
            Class<?> beanType = applicationContext.getType(adviceEntry.getKey());
            if (beanType == null) {
                beanType = bean.getClass();
            }
            discovered.addAll(discoverExceptionHandlers(bean, beanType, resolveOrder(bean, beanType)));
        }

        discovered.sort(Comparator.comparingInt(ExceptionHandlerMethod::order)
                .thenComparing(handler -> handler.beanType().getName())
                .thenComparing(handler -> handler.method().toGenericString()));
        this.adviceHandlers = List.copyOf(discovered);
    }

    /**
     * Attempts to handle {@code ex} using:
     * <ol>
     *   <li>Controller-local {@code @ExceptionHandler} on {@code originalHandler}'s bean type.</li>
     *   <li>{@code @ControllerAdvice} beans registered at startup.</li>
     * </ol>
     * If nothing matches, rethrows as {@link RuntimeException}.
     */
    public void resolve(Exception ex,
                        HandlerMethod originalHandler,
                        NativeWebRequest webRequest,
                        ExerisMvcServerHttpResponse springResponse) throws Exception {

        ExceptionHandlerMethod localHandler = findBestMatch(
                ex,
                discoverExceptionHandlers(originalHandler.getBean(), originalHandler.getBeanType(), Ordered.HIGHEST_PRECEDENCE),
                false);
        if (localHandler != null) {
            invoke(localHandler.bean(), localHandler.method(), ex, webRequest, springResponse);
            return;
        }

        ExceptionHandlerMethod adviceHandler = findBestMatch(ex, adviceHandlers, true);
        if (adviceHandler != null) {
            invoke(adviceHandler.bean(), adviceHandler.method(), ex, webRequest, springResponse);
            return;
        }

        if (ex instanceof RuntimeException re) {
            throw re;
        }
        throw new RuntimeException(ex);
    }

    private List<ExceptionHandlerMethod> discoverExceptionHandlers(Object bean, Class<?> beanType, int order) {
        Map<Method, ExceptionHandler> methods = MethodIntrospector.selectMethods(
                beanType,
                (Method method) -> AnnotatedElementUtils.findMergedAnnotation(method, ExceptionHandler.class));

        List<ExceptionHandlerMethod> handlers = new ArrayList<>();
        for (Map.Entry<Method, ExceptionHandler> entry : methods.entrySet()) {
            List<Class<? extends Throwable>> handledTypes = resolveHandledTypes(entry.getKey(), entry.getValue());
            if (!handledTypes.isEmpty()) {
                handlers.add(new ExceptionHandlerMethod(bean, beanType, entry.getKey(), handledTypes, order));
            }
        }
        return handlers;
    }

    private static int resolveOrder(Object bean, Class<?> beanType) {
        if (bean instanceof Ordered ordered) {
            return ordered.getOrder();
        }
        org.springframework.core.annotation.Order order =
                AnnotatedElementUtils.findMergedAnnotation(beanType, org.springframework.core.annotation.Order.class);
        return order != null ? order.value() : Ordered.LOWEST_PRECEDENCE;
    }

    @SuppressWarnings("unchecked")
    private static List<Class<? extends Throwable>> resolveHandledTypes(Method method, ExceptionHandler annotation) {
        LinkedHashSet<Class<? extends Throwable>> handledTypes = new LinkedHashSet<>();
        for (Class<? extends Throwable> declaredType : annotation.value()) {
            handledTypes.add(declaredType);
        }
        if (handledTypes.isEmpty()) {
            for (Class<?> parameterType : method.getParameterTypes()) {
                if (Throwable.class.isAssignableFrom(parameterType)) {
                    handledTypes.add((Class<? extends Throwable>) parameterType);
                }
            }
        }
        return List.copyOf(handledTypes);
    }

    private static ExceptionHandlerMethod findBestMatch(Exception ex,
                                                        List<ExceptionHandlerMethod> candidates,
                                                        boolean respectOrder) {
        ExceptionDepthComparator depthComparator = new ExceptionDepthComparator(ex);

        return candidates.stream()
                .filter(candidate -> closestHandledType(candidate, ex, depthComparator) != null)
                .min((left, right) -> {
                    if (respectOrder) {
                        int byOrder = Integer.compare(left.order(), right.order());
                        if (byOrder != 0) {
                            return byOrder;
                        }
                    }

                    Class<? extends Throwable> leftType = closestHandledType(left, ex, depthComparator);
                    Class<? extends Throwable> rightType = closestHandledType(right, ex, depthComparator);
                    int bySpecificity = depthComparator.compare(leftType, rightType);
                    if (bySpecificity != 0) {
                        return bySpecificity;
                    }

                    int byBeanType = left.beanType().getName().compareTo(right.beanType().getName());
                    if (byBeanType != 0) {
                        return byBeanType;
                    }
                    return left.method().toGenericString().compareTo(right.method().toGenericString());
                })
                .orElse(null);
    }

    private static Class<? extends Throwable> closestHandledType(ExceptionHandlerMethod candidate,
                                                                 Exception ex,
                                                                 ExceptionDepthComparator depthComparator) {
        return candidate.handledTypes().stream()
                .filter(type -> type.isAssignableFrom(ex.getClass()))
                .min(depthComparator)
                .orElse(null);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void invoke(Object bean, Method method, Exception ex,
                        NativeWebRequest webRequest,
                        ExerisMvcServerHttpResponse springResponse) throws Exception {
        InvocableHandlerMethod ihm = new InvocableHandlerMethod(bean, method);
        ihm.setHandlerMethodArgumentResolvers(argumentResolvers);

        ModelAndViewContainer mavContainer = new ModelAndViewContainer();
        webRequest.setAttribute(
            ExerisCompatAttributes.SPRING_RESPONSE_ATTRIBUTE,
            springResponse,
            NativeWebRequest.SCOPE_REQUEST);

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
