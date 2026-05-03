/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.web.compat.resolver;

import org.springframework.core.MethodParameter;
import org.springframework.util.ClassUtils;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ValueConstants;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Resolves {@code @RequestHeader} parameters from the inbound Spring {@code HttpHeaders}.
 * Supports {@link String}, primitive/boxed scalar types, {@link java.util.UUID},
 * {@link java.time.LocalDate}, and {@link java.time.LocalDateTime} via
 * {@link ExerisRequestParamArgumentResolver#convert(String, Class)}.
 * No servlet types.
 */
public final class ExerisRequestHeaderArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        if (!parameter.hasParameterAnnotation(RequestHeader.class)) {
            return false;
        }
        Class<?> type = parameter.getParameterType();
        return (ClassUtils.isPrimitiveOrWrapper(type)
                && !Void.TYPE.equals(type)
                && !Void.class.equals(type))
                || String.class.equals(type)
                || java.util.UUID.class.equals(type)
                || java.time.LocalDate.class.equals(type)
                || java.time.LocalDateTime.class.equals(type);
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
        if (name.isEmpty()) {
            name = parameter.getParameterName();
        }
        if (name == null || name.isEmpty()) {
            throw new IllegalStateException(
                    "@RequestHeader on parameter index " + parameter.getParameterIndex()
                    + " of " + parameter.getMethod()
                    + " has no name: compile with -parameters or use @RequestHeader(\"name\")");
        }

        Class<?> targetType = parameter.getParameterType();
        String value = webRequest.getHeader(name);

        if (value == null) {
            if (annotation.required() && annotation.defaultValue().equals(ValueConstants.DEFAULT_NONE)) {
                throw new IllegalArgumentException("Required header '" + name + "' is not present");
            }
            if (!annotation.defaultValue().equals(ValueConstants.DEFAULT_NONE)) {
                return ExerisRequestParamArgumentResolver.convert(annotation.defaultValue(), targetType);
            }
            return null;
        }

        return ExerisRequestParamArgumentResolver.convert(value, targetType);
    }
}
