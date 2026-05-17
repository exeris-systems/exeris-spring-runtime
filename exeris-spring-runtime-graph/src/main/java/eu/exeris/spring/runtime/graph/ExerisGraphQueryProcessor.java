/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.graph;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.aop.support.annotation.AnnotationMatchingPointcut;
import org.springframework.beans.factory.config.BeanPostProcessor;

import eu.exeris.kernel.spi.graph.model.GraphTraversal;
import eu.exeris.kernel.spi.memory.LoanedBuffer;

/**
 * {@code BeanPostProcessor} that validates {@link ExerisGraphQuery}-annotated methods at
 * post-processing time (per ADR-030 obligation 4 — fail-fast before context refresh completes)
 * and installs a Spring AOP proxy that routes annotated method calls through
 * {@link ExerisGraphTemplate}.
 *
 * <h2>Validation rules (merge-blocking; failures throw {@link IllegalStateException})</h2>
 *
 * <ol>
 *   <li>Method must be {@code public}. Annotating a non-public method fails fast at
 *       post-processing time with a message naming the bean + method.</li>
 *   <li>Return type must be one of:
 *       <ul>
 *         <li>{@code List<UUID>} → routes to {@link ExerisGraphTemplate#traverseBfs}.</li>
 *         <li>{@link LoanedBuffer} → routes to {@link ExerisGraphTemplate#streamBfsJson}
 *             (caller owns the buffer per the template's ownership contract).</li>
 *       </ul>
 *       Any other return type fails fast.</li>
 *   <li>Method must declare exactly one parameter of type {@link GraphTraversal}. Phase 4C
 *       Step 3 does not support method-name-based parameter binding (deferred until the kernel
 *       SPI exposes a parser for the {@link ExerisGraphQuery#value()} MATCH-DSL string).</li>
 * </ol>
 *
 * <p>The processor is registered as a {@code @Bean} by {@link ExerisGraphAutoConfiguration} so
 * application code does not need to do anything beyond annotating a method on a Spring bean.
 *
 * @since 0.7.0
 */
public final class ExerisGraphQueryProcessor implements BeanPostProcessor {

    private final ExerisGraphTemplate template;

    public ExerisGraphQueryProcessor(ExerisGraphTemplate template) {
        this.template = Objects.requireNonNull(template, "template must not be null");
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        Class<?> targetClass = bean.getClass();
        boolean hasAnyAnnotated = false;
        for (Method method : targetClass.getMethods()) {
            if (method.isAnnotationPresent(ExerisGraphQuery.class)) {
                validate(method, beanName);
                hasAnyAnnotated = true;
            }
        }
        if (!hasAnyAnnotated) {
            return bean;
        }
        return wrap(bean, beanName);
    }

    private static void validate(Method method, String beanName) {
        String beanRef = beanName + "#" + method.getName();
        if (!Modifier.isPublic(method.getModifiers())) {
            throw new IllegalStateException(
                    "@ExerisGraphQuery method " + beanRef + " must be public; "
                            + "non-public methods cannot be proxied through Spring AOP.");
        }
        Class<?> returnType = method.getReturnType();
        if (returnType != List.class && returnType != LoanedBuffer.class) {
            throw new IllegalStateException(
                    "@ExerisGraphQuery method " + beanRef + " returns "
                            + returnType.getName() + "; only List<UUID> "
                            + "(routes to ExerisGraphTemplate.traverseBfs) or LoanedBuffer "
                            + "(routes to ExerisGraphTemplate.streamBfsJson) are supported "
                            + "in Phase 4C Step 3. See ADR-030 obligation 4.");
        }
        if (method.getParameterCount() != 1 || method.getParameterTypes()[0] != GraphTraversal.class) {
            throw new IllegalStateException(
                    "@ExerisGraphQuery method " + beanRef + " must declare exactly one parameter "
                            + "of type eu.exeris.kernel.spi.graph.model.GraphTraversal; method-name-"
                            + "based parameter binding is not supported in Phase 4C Step 3 "
                            + "(deferred until the kernel SPI exposes a MATCH-DSL parser for "
                            + "@ExerisGraphQuery.value()).");
        }
    }

    private Object wrap(Object bean, String beanName) {
        ProxyFactory factory = new ProxyFactory(bean);
        factory.setProxyTargetClass(true);
        factory.addAdvisor(new DefaultPointcutAdvisor(
                AnnotationMatchingPointcut.forMethodAnnotation(ExerisGraphQuery.class),
                new GraphQueryInterceptor(template, beanName)));
        return factory.getProxy(bean.getClass().getClassLoader());
    }

    private static final class GraphQueryInterceptor implements MethodInterceptor {

        private final ExerisGraphTemplate template;
        private final String beanName;

        GraphQueryInterceptor(ExerisGraphTemplate template, String beanName) {
            this.template = template;
            this.beanName = beanName;
        }

        @Override
        public Object invoke(MethodInvocation invocation) {
            Method method = invocation.getMethod();
            Object[] args = invocation.getArguments();
            GraphTraversal traversal = (GraphTraversal) args[0];
            if (traversal == null) {
                throw new IllegalArgumentException(
                        "@ExerisGraphQuery " + beanName + "#" + method.getName()
                                + " called with null GraphTraversal argument.");
            }
            Class<?> returnType = method.getReturnType();
            if (returnType == List.class) {
                List<UUID> result = template.traverseBfs(traversal);
                return result;
            }
            // returnType is LoanedBuffer (validated at post-processing).
            return template.streamBfsJson(traversal);
        }
    }
}
