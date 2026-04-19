/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.web.compat.handler;

import eu.exeris.spring.runtime.web.compat.ExerisMvcServerHttpResponse;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodReturnValueHandler;
import org.springframework.web.method.support.ModelAndViewContainer;

import java.util.ArrayList;
import java.util.List;

/**
 * Writes {@link ResponseEntity} return values to {@link ExerisMvcServerHttpResponse},
 * propagating status, headers, and body via registered {@link HttpMessageConverter}s.
 * No servlet types.
 */
public final class ExerisResponseEntityReturnValueHandler implements HandlerMethodReturnValueHandler {

    private final List<HttpMessageConverter<?>> converters;

    public ExerisResponseEntityReturnValueHandler(List<HttpMessageConverter<?>> converters) {
        this.converters = List.copyOf(converters);
    }

    @Override
    public boolean supportsReturnType(MethodParameter returnType) {
        return ResponseEntity.class.isAssignableFrom(returnType.getParameterType());
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void handleReturnValue(Object returnValue,
                                  MethodParameter returnType,
                                  ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest) throws Exception {
        mavContainer.setRequestHandled(true);

        if (!(returnValue instanceof ResponseEntity<?> entity)) {
            return;
        }

        ExerisMvcServerHttpResponse springResponse = (ExerisMvcServerHttpResponse)
                webRequest.getAttribute("__exerisSpringResponse", NativeWebRequest.SCOPE_REQUEST);
        if (springResponse == null) {
            throw new IllegalStateException("No ExerisMvcServerHttpResponse in request attributes");
        }

        springResponse.setStatusCode(entity.getStatusCode());
        springResponse.getHeaders().putAll(entity.getHeaders());

        Object body = entity.getBody();
        if (body == null) {
            return;
        }

        Class<?> valueType = body.getClass();
        MediaType contentType = springResponse.getHeaders().getContentType();
        if (contentType == null) {
            contentType = MediaType.APPLICATION_JSON;
        }

        List<MediaType> supportedTypes = new ArrayList<>();
        for (HttpMessageConverter<?> converter : converters) {
            if (converter.canWrite(valueType, contentType)) {
                ((HttpMessageConverter) converter).write(body, contentType, springResponse);
                return;
            }
            supportedTypes.addAll(converter.getSupportedMediaTypes(valueType));
        }

        throw new IllegalArgumentException(
                "No HttpMessageConverter found to write ResponseEntity body of type '" + valueType.getName()
                + "'. Supported media types: " + supportedTypes);
    }
}
