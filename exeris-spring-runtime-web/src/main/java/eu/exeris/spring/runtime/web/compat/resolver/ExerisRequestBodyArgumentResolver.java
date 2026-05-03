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
}
