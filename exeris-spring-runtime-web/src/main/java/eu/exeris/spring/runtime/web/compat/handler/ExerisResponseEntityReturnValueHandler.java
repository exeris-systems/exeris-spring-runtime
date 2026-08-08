/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.web.compat.handler;

import eu.exeris.spring.runtime.web.compat.CompatibilityMode;

import java.util.ArrayList;
import java.util.List;

import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodReturnValueHandler;
import org.springframework.web.method.support.ModelAndViewContainer;

import eu.exeris.spring.runtime.web.compat.ExerisCompatAttributes;
import eu.exeris.spring.runtime.web.compat.ExerisMvcServerHttpResponse;

/**
 * Writes {@link ResponseEntity} return values to {@link ExerisMvcServerHttpResponse},
 * propagating status, headers, and body via registered {@link HttpMessageConverter}s.
 * No servlet types.
 */
@CompatibilityMode
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
        webRequest.getAttribute(
            ExerisCompatAttributes.SPRING_RESPONSE_ATTRIBUTE,
            NativeWebRequest.SCOPE_REQUEST);
        if (springResponse == null) {
            throw new IllegalStateException("No ExerisMvcServerHttpResponse in request attributes");
        }

        springResponse.setStatusCode(entity.getStatusCode());

        // forEach + put rather than putAll(entity.getHeaders()) for *binary* neutrality. The
        // putAll form compiles on both matrix profiles but binds to different descriptors:
        // Spring Framework 6 has HttpHeaders implement MultiValueMap, so it selects
        // putAll(Map); Spring Framework 7 drops that and selects the putAll(HttpHeaders)
        // overload. An SB3-compiled jar therefore calls putAll(Map) with an argument that is no
        // longer a Map on SB4, and every ResponseEntity return value dies with
        // IncompatibleClassChangeError. forEach and put(String, List) are identical on both
        // lines. See ADR-028 §1 and spring-boot-4-matrix.md item 3.
        HttpHeaders responseHeaders = springResponse.getHeaders();
        entity.getHeaders().forEach(responseHeaders::put);

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
