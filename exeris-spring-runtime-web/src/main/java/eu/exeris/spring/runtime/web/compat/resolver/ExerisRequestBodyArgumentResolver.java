/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.web.compat.resolver;

import java.util.ArrayList;
import java.util.List;

import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import eu.exeris.spring.runtime.web.compat.ExerisMvcServerHttpRequest;

/**
 * Resolves {@code @RequestBody} parameters by reading the request body via a registered
 * {@link HttpMessageConverter}. Backed by {@link ExerisMvcServerHttpRequest}.
 * No servlet types.
 */
public final class ExerisRequestBodyArgumentResolver implements HandlerMethodArgumentResolver {

    private final List<HttpMessageConverter<?>> converters;

    public ExerisRequestBodyArgumentResolver(List<HttpMessageConverter<?>> converters) {
        this.converters = List.copyOf(converters);
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        Class<?> parameterType = parameter.getParameterType();
        return parameter.hasParameterAnnotation(RequestBody.class)
                && parameterType != void.class
                && parameterType != Void.class;
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public Object resolveArgument(MethodParameter parameter,
                                  ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest,
                                  org.springframework.web.bind.support.WebDataBinderFactory binderFactory) throws Exception {

        ExerisMvcServerHttpRequest springRequest = webRequest.getNativeRequest(ExerisMvcServerHttpRequest.class);
        if (springRequest == null) {
            throw new IllegalStateException("Expected ExerisMvcServerHttpRequest in NativeWebRequest for @RequestBody");
        }

        MediaType contentType = springRequest.getHeaders().getContentType();
        if (contentType == null) {
            // Don't assume JSON for missing Content-Type.
            // Try all converters; only use JSON if no other converter matches.
            contentType = MediaType.APPLICATION_OCTET_STREAM;
        }

        Class<?> targetType = parameter.getParameterType();

        List<MediaType> supportedTypes = new ArrayList<>();
        for (HttpMessageConverter<?> converter : converters) {
            if (converter.canRead(targetType, contentType)) {
                return ((HttpMessageConverter) converter).read(targetType, springRequest);
            }
            supportedTypes.addAll(converter.getSupportedMediaTypes(targetType));
        }

        throw new IllegalArgumentException(
                "No HttpMessageConverter found for content type '" + contentType
                + "'. Supported: " + supportedTypes);
    }
}
