/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.web.compat.resolver;

import org.springframework.core.MethodParameter;
import org.springframework.util.ClassUtils;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ValueConstants;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Resolves {@code @RequestParam} annotated parameters from the request query string.
 * Supports {@link String} and boxed / primitive numeric/boolean types.
 * No servlet types.
 */
public final class ExerisRequestParamArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        if (!parameter.hasParameterAnnotation(RequestParam.class)) {
            return false;
        }
        Class<?> type = parameter.getParameterType();
        return ClassUtils.isPrimitiveOrWrapper(type)
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
        RequestParam annotation = parameter.getParameterAnnotation(RequestParam.class);
        if (annotation == null) {
            throw new IllegalStateException("@RequestParam annotation not found on parameter: " + parameter);
        }

        String name = annotation.name().isEmpty() ? annotation.value() : annotation.name();
        if (name.isEmpty()) {
            name = parameter.getParameterName();
        }
        if (name == null || name.isEmpty()) {
            throw new IllegalStateException(
                    "@RequestParam on parameter index " + parameter.getParameterIndex()
                    + " of " + parameter.getMethod()
                    + " has no name: compile with -parameters or use @RequestParam(\"name\")");
        }

        String raw = webRequest.getParameter(name);

        if (raw == null) {
            if (annotation.required() && annotation.defaultValue().equals(ValueConstants.DEFAULT_NONE)) {
                throw new IllegalArgumentException("Required request parameter '" + name + "' is not present");
            }
            if (!annotation.defaultValue().equals(ValueConstants.DEFAULT_NONE)) {
                return convert(annotation.defaultValue(), parameter.getParameterType());
            }
            return null;
        }

        return convert(raw, parameter.getParameterType());
    }

    static Object convert(String raw, Class<?> type) {
        if (String.class.equals(type)) return raw;
        try {
            if (Integer.class.equals(type) || int.class.equals(type)) return Integer.parseInt(raw);
            if (Long.class.equals(type) || long.class.equals(type)) return Long.parseLong(raw);
            if (Boolean.class.equals(type) || boolean.class.equals(type)) return Boolean.parseBoolean(raw);
            if (Double.class.equals(type) || double.class.equals(type)) return Double.parseDouble(raw);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Cannot convert '" + raw + "' to " + type.getSimpleName(), ex);
        }
        if (java.util.UUID.class.equals(type)) {
            try { return java.util.UUID.fromString(raw); }
            catch (IllegalArgumentException ex) { throw new IllegalArgumentException("Invalid UUID: " + raw, ex); }
        }
        if (java.time.LocalDateTime.class.equals(type)) return java.time.LocalDateTime.parse(raw, java.time.format.DateTimeFormatter.ISO_DATE_TIME);
        if (java.time.LocalDate.class.equals(type)) return java.time.LocalDate.parse(raw, java.time.format.DateTimeFormatter.ISO_DATE);
        throw new IllegalArgumentException("Unsupported parameter type: " + type.getName());
    }
}
