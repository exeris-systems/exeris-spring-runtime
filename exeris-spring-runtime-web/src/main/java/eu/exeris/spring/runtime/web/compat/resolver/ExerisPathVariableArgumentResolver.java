/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.web.compat.resolver;

import eu.exeris.spring.runtime.web.compat.ExerisHandlerMethodRegistry;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import java.util.Map;

/**
 * Resolves {@code @PathVariable} parameters from the path-variable map stored on
 * the {@link NativeWebRequest} under
 * {@link ExerisHandlerMethodRegistry#PATH_VARIABLES_ATTRIBUTE}.
 * No servlet types.
 */
public final class ExerisPathVariableArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(PathVariable.class);
    }

    @Override
    @SuppressWarnings("unchecked")
    public Object resolveArgument(MethodParameter parameter,
                                  ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest,
                                  org.springframework.web.bind.support.WebDataBinderFactory binderFactory) {
        PathVariable annotation = parameter.getParameterAnnotation(PathVariable.class);
        if (annotation == null) {
            throw new IllegalStateException("@PathVariable annotation not found on parameter: " + parameter);
        }

        String name = annotation.name().isEmpty() ? annotation.value() : annotation.name();
        if (name.isEmpty()) {
            name = parameter.getParameterName();
        }
        if (name == null || name.isEmpty()) {
            throw new IllegalStateException(
                    "@PathVariable on parameter index " + parameter.getParameterIndex()
                    + " of " + parameter.getMethod()
                    + " has no name: compile with -parameters or use @PathVariable(\"name\")");
        }

        Object vars = webRequest.getAttribute(ExerisHandlerMethodRegistry.PATH_VARIABLES_ATTRIBUTE,
                NativeWebRequest.SCOPE_REQUEST);
        if (!(vars instanceof Map)) {
            throw new IllegalStateException("No path variables in request — expected Map at attribute '"
                    + ExerisHandlerMethodRegistry.PATH_VARIABLES_ATTRIBUTE + "'");
        }

        Map<String, String> pathVars = (Map<String, String>) vars;
        String raw = pathVars.get(name);
        if (raw == null && annotation.required()) {
            throw new IllegalArgumentException("Missing path variable: " + name);
        }

        return raw != null ? ExerisRequestParamArgumentResolver.convert(raw, parameter.getParameterType()) : null;
    }
}
