/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.web.compat.handler;

import eu.exeris.spring.runtime.web.compat.ExerisMvcServerHttpResponse;
import org.springframework.core.MethodParameter;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodReturnValueHandler;
import org.springframework.web.method.support.ModelAndViewContainer;

import java.util.ArrayList;
import java.util.List;

/**
 * Writes {@code @ResponseBody} return values to {@link ExerisMvcServerHttpResponse}
 * using registered {@link HttpMessageConverter}s.
 * No servlet types.
 */
public final class ExerisResponseBodyReturnValueHandler implements HandlerMethodReturnValueHandler {

    private final List<HttpMessageConverter<?>> converters;

    public ExerisResponseBodyReturnValueHandler(List<HttpMessageConverter<?>> converters) {
        this.converters = List.copyOf(converters);
    }

    @Override
    public boolean supportsReturnType(MethodParameter returnType) {
        return AnnotatedElementUtils.hasAnnotation(returnType.getContainingClass(), ResponseBody.class)
                || returnType.hasMethodAnnotation(ResponseBody.class);
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void handleReturnValue(Object returnValue,
                                  MethodParameter returnType,
                                  ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest) throws Exception {
        mavContainer.setRequestHandled(true);

        if (returnValue == null) {
            return;
        }

        ExerisMvcServerHttpResponse springResponse = (ExerisMvcServerHttpResponse)
                webRequest.getAttribute("__exerisSpringResponse", NativeWebRequest.SCOPE_REQUEST);
        if (springResponse == null) {
            throw new IllegalStateException("No ExerisMvcServerHttpResponse in request attributes");
        }

        Class<?> valueType = returnValue.getClass();
        MediaType contentType = springResponse.getHeaders().getContentType();
        if (contentType == null) {
            contentType = MediaType.APPLICATION_JSON;
        }

        List<MediaType> supportedTypes = new ArrayList<>();
        for (HttpMessageConverter<?> converter : converters) {
            if (converter.canWrite(valueType, contentType)) {
                ((HttpMessageConverter) converter).write(returnValue, contentType, springResponse);
                return;
            }
            supportedTypes.addAll(converter.getSupportedMediaTypes(valueType));
        }

        throw new IllegalArgumentException(
                "No HttpMessageConverter found to write value of type '" + valueType.getName()
                + "'. Supported media types: " + supportedTypes);
    }
}
