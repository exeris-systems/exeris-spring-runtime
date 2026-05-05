/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.web.compat.resolver;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.List;

import org.springframework.core.Conventions;
import org.springframework.core.MethodParameter;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.lang.Nullable;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.SmartValidator;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import eu.exeris.spring.runtime.web.compat.ExerisMvcServerHttpRequest;

/**
 * Resolves {@code @RequestBody} parameters by reading the request body via a registered
 * {@link HttpMessageConverter}. Backed by {@link ExerisMvcServerHttpRequest}.
 * No servlet types.
 *
 * <p>If a {@link SmartValidator} is wired and the parameter is annotated with
 * {@code @Valid} (any annotation whose simple name starts with {@code "Valid"}) or
 * {@link Validated}, the deserialised body is validated and a
 * {@link MethodArgumentNotValidException} is thrown on constraint violations — the
 * same surface Spring MVC presents, so existing
 * {@code @ExceptionHandler(MethodArgumentNotValidException.class)} advice keeps
 * working unchanged.
 */
public final class ExerisRequestBodyArgumentResolver implements HandlerMethodArgumentResolver {

    private final List<HttpMessageConverter<?>> converters;
    @Nullable
    private final SmartValidator validator;

    public ExerisRequestBodyArgumentResolver(List<HttpMessageConverter<?>> converters) {
        this(converters, null);
    }

    public ExerisRequestBodyArgumentResolver(List<HttpMessageConverter<?>> converters,
                                              @Nullable SmartValidator validator) {
        this.converters = List.copyOf(converters);
        this.validator = validator;
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        Class<?> parameterType = parameter.getParameterType();
        return parameter.hasParameterAnnotation(RequestBody.class)
                && parameterType != void.class
                && parameterType != Void.class;
    }

    @Override
    public Object resolveArgument(MethodParameter parameter,
                                  ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest,
                                  org.springframework.web.bind.support.WebDataBinderFactory binderFactory) throws Exception {

        ExerisMvcServerHttpRequest springRequest = webRequest.getNativeRequest(ExerisMvcServerHttpRequest.class);
        if (springRequest == null) {
            throw new IllegalStateException("Expected ExerisMvcServerHttpRequest in NativeWebRequest for @RequestBody");
        }

        MediaType declaredContentType = springRequest.getHeaders().getContentType();
        Class<?> targetType = parameter.getParameterType();

        // When Content-Type is absent, first try OCTET_STREAM-aware converters
        // (e.g. byte[]/InputStream readers). Only if none match do we fall back
        // to APPLICATION_JSON — this preserves the documented "don't assume JSON
        // by default" semantics while still letting JSON-accepting handlers read
        // bodies that were posted without a Content-Type header.
        MediaType primaryContentType = declaredContentType != null
                ? declaredContentType
                : MediaType.APPLICATION_OCTET_STREAM;

        Object body = readBody(springRequest, parameter, targetType, primaryContentType, declaredContentType);
        validateIfApplicable(body, parameter);
        return body;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Object readBody(ExerisMvcServerHttpRequest springRequest,
                             MethodParameter parameter,
                             Class<?> targetType,
                             MediaType primaryContentType,
                             @Nullable MediaType declaredContentType) throws Exception {
        List<MediaType> supportedTypes = new ArrayList<>();
        for (HttpMessageConverter<?> converter : converters) {
            if (converter.canRead(targetType, primaryContentType)) {
                return ((HttpMessageConverter) converter).read(targetType, springRequest);
            }
            supportedTypes.addAll(converter.getSupportedMediaTypes(targetType));
        }

        if (declaredContentType == null) {
            for (HttpMessageConverter<?> converter : converters) {
                if (converter.canRead(targetType, MediaType.APPLICATION_JSON)) {
                    return ((HttpMessageConverter) converter).read(targetType, springRequest);
                }
            }
        }

        throw new IllegalArgumentException(
                "No HttpMessageConverter found for content type '" + primaryContentType
                + "'. Supported: " + supportedTypes);
    }

    private void validateIfApplicable(@Nullable Object body, MethodParameter parameter)
            throws MethodArgumentNotValidException {
        SmartValidator activeValidator = this.validator;
        if (body == null || activeValidator == null) {
            return;
        }
        Object[] hints = resolveValidationHints(parameter);
        if (hints == null) {
            return;
        }
        String name = Conventions.getVariableNameForParameter(parameter);
        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(body, name);
        activeValidator.validate(body, bindingResult, hints);
        if (bindingResult.hasErrors()) {
            throw new MethodArgumentNotValidException(parameter, bindingResult);
        }
    }

    @Nullable
    private static Object[] resolveValidationHints(MethodParameter parameter) {
        for (Annotation ann : parameter.getParameterAnnotations()) {
            Validated validated = AnnotationUtils.getAnnotation(ann, Validated.class);
            if (validated != null) {
                return validated.value();
            }
            if (ann.annotationType().getSimpleName().startsWith("Valid")) {
                Object value = AnnotationUtils.getValue(ann);
                return (value instanceof Class<?>[] groups) ? groups : new Object[0];
            }
        }
        return null;
    }
}
