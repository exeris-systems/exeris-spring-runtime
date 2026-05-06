/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.web.compat.resolver;

import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.core.MethodParameter;
import org.springframework.core.convert.ConversionService;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ValueConstants;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Resolves {@code @RequestParam} annotated parameters from the request query string.
 * Conversion is delegated to a {@link ConversionService} — the same seam Spring MVC
 * uses — so enum coercion, user-registered {@code Converter<String, T>} beans and
 * formatter-driven types ({@code LocalDate}, {@code LocalDateTime}, {@code UUID})
 * all work out of the box.
 *
 * <p>Multi-value parameter types ({@link java.util.Collection}, {@link java.util.Map},
 * arrays) are rejected — see {@link ExerisCompatTypeConverter} for the rationale.
 * No servlet types.
 */
public final class ExerisRequestParamArgumentResolver implements HandlerMethodArgumentResolver {

    private final ExerisCompatTypeConverter typeConverter;

    public ExerisRequestParamArgumentResolver() {
        this(ApplicationConversionService.getSharedInstance());
    }

    public ExerisRequestParamArgumentResolver(ConversionService conversionService) {
        this.typeConverter = new ExerisCompatTypeConverter(conversionService);
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        if (!parameter.hasParameterAnnotation(RequestParam.class)) {
            return false;
        }
        return typeConverter.isSupportedTargetType(parameter.getParameterType());
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
        Class<?> targetType = parameter.getParameterType();

        if (raw == null) {
            if (annotation.required() && annotation.defaultValue().equals(ValueConstants.DEFAULT_NONE)) {
                throw new IllegalArgumentException("Required request parameter '" + name + "' is not present");
            }
            if (!annotation.defaultValue().equals(ValueConstants.DEFAULT_NONE)) {
                return typeConverter.convert(annotation.defaultValue(), targetType);
            }
            if (targetType.isPrimitive()) {
                return primitiveDefault(targetType);
            }
            return null;
        }

        return typeConverter.convert(raw, targetType);
    }

    private static Object primitiveDefault(Class<?> type) {
        if (boolean.class.equals(type)) return false;
        if (char.class.equals(type)) return '\0';
        if (byte.class.equals(type)) return (byte) 0;
        if (short.class.equals(type)) return (short) 0;
        if (int.class.equals(type)) return 0;
        if (long.class.equals(type)) return 0L;
        if (float.class.equals(type)) return 0.0f;
        if (double.class.equals(type)) return 0.0d;
        throw new IllegalArgumentException("Not a primitive type: " + type);
    }
}
