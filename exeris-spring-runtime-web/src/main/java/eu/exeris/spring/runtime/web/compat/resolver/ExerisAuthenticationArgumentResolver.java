/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a compatible product is prohibited.
 */
package eu.exeris.spring.runtime.web.compat.resolver;

import org.springframework.core.MethodParameter;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Compatibility-mode argument resolver for {@link Authentication} method parameters.
 *
 * <p>Reads the current {@link Authentication} from Spring Security's
 * {@link SecurityContextHolder}, which is populated by
 * {@link eu.exeris.spring.runtime.web.compat.filter.ExerisSecurityContextFilter}
 * before handler invocation and cleared deterministically in {@code finally}.
 *
 * <p>Returns {@code null} when no authentication is present (anonymous request).
 *
 * <p><strong>Compatibility Mode only.</strong> Not used in the Pure Mode request path.
 */
public final class ExerisAuthenticationArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return Authentication.class.isAssignableFrom(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(MethodParameter parameter,
                                  ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest,
                                  WebDataBinderFactory binderFactory) {
        return SecurityContextHolder.getContext().getAuthentication();
    }
}
