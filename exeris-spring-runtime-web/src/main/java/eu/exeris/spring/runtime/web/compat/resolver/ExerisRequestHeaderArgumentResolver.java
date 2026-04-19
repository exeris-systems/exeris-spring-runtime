/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.web.compat.resolver;

import org.springframework.core.MethodParameter;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ValueConstants;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Resolves {@code @RequestHeader} parameters of type {@link String} from the inbound
 * Spring {@code HttpHeaders}. No servlet types.
 */
public final class ExerisRequestHeaderArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(RequestHeader.class)
                && String.class.equals(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(MethodParameter parameter,
                                  ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest,
                                  org.springframework.web.bind.support.WebDataBinderFactory binderFactory) {
        RequestHeader annotation = parameter.getParameterAnnotation(RequestHeader.class);
        if (annotation == null) {
            throw new IllegalStateException("@RequestHeader annotation not found on parameter: " + parameter);
        }

        String name = annotation.name().isEmpty() ? annotation.value() : annotation.name();

        String value = webRequest.getHeader(name);
        if (value == null) {
            if (annotation.required() && annotation.defaultValue().equals(ValueConstants.DEFAULT_NONE)) {
                throw new IllegalArgumentException("Required header '" + name + "' is not present");
            }
            return annotation.defaultValue().equals(ValueConstants.DEFAULT_NONE) ? null : annotation.defaultValue();
        }

        return value;
    }
}
