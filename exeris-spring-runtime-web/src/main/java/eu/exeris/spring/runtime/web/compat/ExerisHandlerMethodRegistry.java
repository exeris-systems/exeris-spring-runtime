/*
 * Copyright (C) 2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
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
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.core.MethodIntrospector;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.server.PathContainer;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Controller;
import org.springframework.util.ClassUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.spring.boot.autoconfigure.compat.CompatibilityMode;

/**
 * Compatibility-mode registry that scans {@code @Controller}/{@code @RestController} beans
 * and resolves incoming requests to a {@link HandlerMethod} using Spring's
 * {@link PathPattern} matching — no {@code spring-webmvc}, no servlet types.
 *
 * <p>{@link #PATH_VARIABLES_ATTRIBUTE} is stored on the {@link ExerisNativeWebRequest}
 * so that {@link eu.exeris.spring.runtime.web.compat.resolver.ExerisPathVariableArgumentResolver}
 * can retrieve extracted URI variables.
 *
 * <p>Controllers are collected once every singleton is instantiated, not while this registry is
 * being created: the registry sits in the {@code ExerisRuntimeLifecycle} → dispatcher → bridge chain,
 * so collecting eagerly would turn any controller that depends on a bean depending on the lifecycle
 * into an unresolvable creation cycle. Collection completes before any {@code SmartLifecycle} starts,
 * so before the kernel accepts a request.
 */
@CompatibilityMode
public final class ExerisHandlerMethodRegistry
        implements ApplicationContextAware, InitializingBean, SmartInitializingSingleton {

    /**
     * Creates an empty registry. Controller routes are discovered in {@link #afterSingletonsInstantiated()}.
     */
    public ExerisHandlerMethodRegistry() {
        // No construction logic beyond the superclass constructor.
        super();
    }

    /**
     * Request attribute key under which a {@code Map<String, String>} of extracted
     * path variables is stored.  Must NOT use
     * {@code HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE} (spring-webmvc banned).
     */
    public static final String PATH_VARIABLES_ATTRIBUTE = "eu.exeris.compat.pathVariables";

    private ApplicationContext applicationContext;
    // Null until afterSingletonsInstantiated() collects the controllers; written once.
    private volatile List<RouteEntry> routes;
    // Set by afterPropertiesSet(). Lets code that never calls afterSingletonsInstantiated() collect on first use.
    private volatile boolean collectOnFirstUse;

    private record RouteEntry(PathPattern pattern, Set<HttpMethod> methods, HandlerMethod handlerMethod) {}

    /**
     * A resolved handler paired with the extracted URI variables.
     *
     * @param handlerMethod the controller method the request routes to
     * @param pathVariables the URI template variables extracted from the request path, by name
     */
    public record ResolvedHandler(HandlerMethod handlerMethod, Map<String, String> pathVariables) {}

    @Override
    public void setApplicationContext(@NonNull ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    /**
     * Does not collect. A container calls this while the registry is still being created, inside the
     * {@code ExerisRuntimeLifecycle} → dispatcher chain, where collecting would make any controller that
     * depends on that chain an unresolvable creation cycle. A container collects in
     * {@link #afterSingletonsInstantiated()} instead. Code that drives the registry outside a container
     * and never calls that method gets the controllers collected on first use.
     */
    @Override
    public void afterPropertiesSet() {
        collectOnFirstUse = true;
    }

    @Override
    public void afterSingletonsInstantiated() {
        PathPatternParser parser = PathPatternParser.defaultInstance;
        List<RouteEntry> collected = new ArrayList<>();
        for (String name : applicationContext.getBeanDefinitionNames()) {
            Class<?> rawType = applicationContext.getType(name, false);
            if (rawType == null) {
                continue;
            }
            Class<?> beanType = ClassUtils.getUserClass(rawType);
            if (AnnotatedElementUtils.findMergedAnnotation(beanType, Controller.class) == null) {
                continue;
            }
            Object bean = applicationContext.getBean(name);

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
                        collected.add(new RouteEntry(parser.parse(combined), httpMethods, handlerMethod));
                    }
                    if (patterns.length == 0) {
                        String combined = combinePatterns(classPrefix, "");
                        collected.add(new RouteEntry(parser.parse(combined), httpMethods, handlerMethod));
                    }
                }
            }
        }
        this.routes = List.copyOf(collected);
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

        record MatchCandidate(RouteEntry entry, PathPattern.PathMatchInfo info, int methodRank) {}

        List<RouteEntry> table = routes;
        if (table == null && collectOnFirstUse) {
            synchronized (this) {
                if (routes == null) {
                    afterSingletonsInstantiated();
                }
            }
            table = routes;
        }
        if (table == null) {
            throw new IllegalStateException("The handler method registry was consulted before the "
                    + "application context finished instantiating its singletons");
        }
        List<MatchCandidate> matches = new ArrayList<>();
        for (RouteEntry entry : table) {
            int methodRank = methodMatchRank(method, entry.methods());
            if (methodRank < 0) {
                continue;
            }
            PathPattern.PathMatchInfo info = entry.pattern().matchAndExtract(pc);
            if (info != null) {
                matches.add(new MatchCandidate(entry, info, methodRank));
            }
        }

        if (matches.isEmpty()) {
            return Optional.empty();
        }

        matches.sort((left, right) -> {
            int byPattern = PathPattern.SPECIFICITY_COMPARATOR.compare(left.entry().pattern(), right.entry().pattern());
            if (byPattern != 0) {
                return byPattern;
            }
            int byMethodRank = Integer.compare(left.methodRank(), right.methodRank());
            if (byMethodRank != 0) {
                return byMethodRank;
            }
            return left.entry().handlerMethod().getMethod().toGenericString()
                    .compareTo(right.entry().handlerMethod().getMethod().toGenericString());
        });

        MatchCandidate best = matches.get(0);
        return Optional.of(new ResolvedHandler(best.entry().handlerMethod(), best.info().getUriVariables()));
    }

    private static int methodMatchRank(HttpMethod requestMethod, Set<HttpMethod> mappedMethods) {
        if (mappedMethods.isEmpty()) {
            return 2;
        }
        if (mappedMethods.contains(requestMethod)) {
            return 0;
        }
        if (requestMethod == HttpMethod.HEAD && mappedMethods.contains(HttpMethod.GET)) {
            return 1;
        }
        return -1;
    }
}
