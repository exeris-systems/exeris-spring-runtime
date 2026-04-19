/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.web.compat;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.core.MethodIntrospector;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.server.PathContainer;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import eu.exeris.kernel.spi.http.HttpMethod;

/**
 * Compatibility-mode registry that scans {@code @Controller}/{@code @RestController} beans
 * and resolves incoming requests to a {@link HandlerMethod} using Spring's
 * {@link PathPattern} matching — no {@code spring-webmvc}, no servlet types.
 *
 * <p>{@link #PATH_VARIABLES_ATTRIBUTE} is stored on the {@link ExerisNativeWebRequest}
 * so that {@link eu.exeris.spring.runtime.web.compat.resolver.ExerisPathVariableArgumentResolver}
 * can retrieve extracted URI variables.
 */
public final class ExerisHandlerMethodRegistry implements ApplicationContextAware, InitializingBean {

    /**
     * Request attribute key under which a {@code Map<String, String>} of extracted
     * path variables is stored.  Must NOT use
     * {@code HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE} (spring-webmvc banned).
     */
    public static final String PATH_VARIABLES_ATTRIBUTE = "eu.exeris.compat.pathVariables";

    private ApplicationContext applicationContext;
    private final List<RouteEntry> routes = new ArrayList<>();

    private record RouteEntry(PathPattern pattern, Set<HttpMethod> methods, HandlerMethod handlerMethod) {}

    /**
     * A resolved handler paired with the extracted URI variables.
     */
    public record ResolvedHandler(HandlerMethod handlerMethod, Map<String, String> pathVariables) {}

    @Override
    public void setApplicationContext(@NonNull ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    @Override
    public void afterPropertiesSet() {
        PathPatternParser parser = PathPatternParser.defaultInstance;
        for (String name : applicationContext.getBeanDefinitionNames()) {
            Object bean;
            try {
                bean = applicationContext.getBean(name);
            } catch (Exception ignored) {
                continue;
            }
            Class<?> beanType = bean.getClass();
            if (AnnotatedElementUtils.findMergedAnnotation(beanType, Controller.class) == null) {
                continue;
            }

            RequestMapping classMapping = AnnotatedElementUtils.findMergedAnnotation(beanType, RequestMapping.class);
            String[] classPrefixes;
            if (classMapping != null) {
                String[] cp = classMapping.path().length > 0 ? classMapping.path() : classMapping.value();
                classPrefixes = cp.length > 0 ? cp : new String[]{""};
            } else {
                classPrefixes = new String[]{""};
            }

            Map<Method, RequestMapping> methods = MethodIntrospector.selectMethods(beanType,
                    (Method m) -> AnnotatedElementUtils.findMergedAnnotation(m, RequestMapping.class));

            for (Map.Entry<Method, RequestMapping> entry : methods.entrySet()) {
                Method method = entry.getKey();
                RequestMapping mapping = entry.getValue();

                String[] patterns = mapping.path().length > 0 ? mapping.path() : mapping.value();
                RequestMethod[] springMethods = mapping.method();

                Set<HttpMethod> httpMethods;
                if (springMethods.length == 0) {
                    httpMethods = Set.of(); // empty = all methods
                } else {
                    httpMethods = EnumSet.noneOf(HttpMethod.class);
                    for (RequestMethod rm : springMethods) {
                        try {
                            httpMethods.add(HttpMethod.valueOf(rm.name()));
                        } catch (IllegalArgumentException ignored) {
                            // unsupported kernel method variant — skip
                        }
                    }
                }

                HandlerMethod handlerMethod = new HandlerMethod(bean, method);
                for (String classPrefix : classPrefixes) {
                    for (String methodPath : patterns) {
                        String combined = combinePatterns(classPrefix, methodPath);
                        routes.add(new RouteEntry(parser.parse(combined), httpMethods, handlerMethod));
                    }
                    if (patterns.length == 0) {
                        String combined = combinePatterns(classPrefix, "");
                        routes.add(new RouteEntry(parser.parse(combined), httpMethods, handlerMethod));
                    }
                }
            }
        }
    }

    private static String combinePatterns(String classPrefix, String methodPath) {
        if (classPrefix.isEmpty()) return methodPath.isEmpty() ? "/" : methodPath;
        if (methodPath.isEmpty()) return classPrefix;
        boolean classEnds = classPrefix.endsWith("/");
        boolean methodStarts = methodPath.startsWith("/");
        if (classEnds && methodStarts) return classPrefix + methodPath.substring(1);
        if (!classEnds && !methodStarts) return classPrefix + "/" + methodPath;
        return classPrefix + methodPath;
    }

    /**
     * Resolves the best matching handler for the given method and raw path.
     *
     * @param method  the Exeris kernel HTTP method
     * @param rawPath the raw request path (may include query string — stripped automatically)
     * @return the resolved handler, or empty if no route matches
     */
    public Optional<ResolvedHandler> resolve(HttpMethod method, String rawPath) {
        // Strip query string if present
        String path = rawPath;
        int q = path.indexOf('?');
        if (q >= 0) {
            path = path.substring(0, q);
        }

        PathContainer pc = PathContainer.parsePath(path, PathContainer.Options.HTTP_PATH);

        for (RouteEntry entry : routes) {
            if (!entry.methods().isEmpty() && !entry.methods().contains(method)) {
                continue;
            }
            PathPattern.PathMatchInfo info = entry.pattern().matchAndExtract(pc);
            if (info != null) {
                return Optional.of(new ResolvedHandler(entry.handlerMethod(), info.getUriVariables()));
            }
        }
        return Optional.empty();
    }
}
