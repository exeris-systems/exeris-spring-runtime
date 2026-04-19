/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.web.compat;

import eu.exeris.kernel.spi.http.HttpExchange;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpRequest;
import eu.exeris.kernel.spi.http.HttpResponse;
import eu.exeris.kernel.spi.http.HttpStatus;
import eu.exeris.kernel.spi.http.HttpVersion;
import eu.exeris.spring.runtime.web.ExerisErrorMapper;
import eu.exeris.spring.runtime.web.compat.context.ExerisThreadLocalBridge;
import eu.exeris.spring.runtime.web.compat.handler.ExerisResponseBodyReturnValueHandler;
import eu.exeris.spring.runtime.web.compat.handler.ExerisResponseEntityReturnValueHandler;
import eu.exeris.spring.runtime.web.compat.resolver.ExerisPathVariableArgumentResolver;
import eu.exeris.spring.runtime.web.compat.resolver.ExerisRequestBodyArgumentResolver;
import eu.exeris.spring.runtime.web.compat.resolver.ExerisRequestHeaderArgumentResolver;
import eu.exeris.spring.runtime.web.compat.resolver.ExerisRequestParamArgumentResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.support.HandlerMethodArgumentResolverComposite;
import org.springframework.web.method.support.HandlerMethodReturnValueHandlerComposite;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ExerisCompatDispatcherTest {

    private ExerisCompatDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        MappingJackson2HttpMessageConverter converter = new MappingJackson2HttpMessageConverter();
        List<org.springframework.http.converter.HttpMessageConverter<?>> converters = List.of(converter);

        var ctx = new AnnotationConfigApplicationContext(TestConfig.class);

        ExerisHandlerMethodRegistry registry = ctx.getBean(ExerisHandlerMethodRegistry.class);

        HandlerMethodArgumentResolverComposite argResolvers = new HandlerMethodArgumentResolverComposite();
        argResolvers.addResolvers(
                new ExerisRequestBodyArgumentResolver(converters),
                new ExerisRequestParamArgumentResolver(),
                new ExerisPathVariableArgumentResolver(),
                new ExerisRequestHeaderArgumentResolver()
        );

        HandlerMethodReturnValueHandlerComposite returnHandlers = new HandlerMethodReturnValueHandlerComposite();
        returnHandlers.addHandlers(List.of(
                new ExerisResponseEntityReturnValueHandler(converters),
                new ExerisResponseBodyReturnValueHandler(converters)
        ));

        ExerisExceptionHandlerResolver exceptionResolver = ctx.getBean(ExerisExceptionHandlerResolver.class);

        ExerisSpringMvcBridge bridge = new ExerisSpringMvcBridge(
                registry, argResolvers, returnHandlers, exceptionResolver, new ExerisThreadLocalBridge());

        dispatcher = new ExerisCompatDispatcher(bridge, new ExerisErrorMapper());
    }

    @Test
    void handle_routeNotFound_returns404() throws Exception {
        TestExchange exchange = TestExchange.get(HttpMethod.GET, "/nonexistent", anyHttpVersion());
        dispatcher.handle(exchange.proxy());
        assertThat(statusCodeOf(exchange.response())).isEqualTo(404);
    }

    @Test
    void handle_matchedRoute_returns200() throws Exception {
        TestExchange exchange = TestExchange.get(HttpMethod.GET, "/hello", anyHttpVersion());
        dispatcher.handle(exchange.proxy());
        assertThat(statusCodeOf(exchange.response())).isEqualTo(200);
    }

    private static int statusCodeOf(HttpResponse response) {
        return readStatus(response).code();
    }

    private static HttpStatus readStatus(HttpResponse response) {
        try {
            Method accessor = response.getClass().getMethod("status");
            return (HttpStatus) accessor.invoke(response);
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError("Unable to access HttpResponse status", ex);
        }
    }

    private static String readHeader(HttpResponse response, String headerName) {
        try {
            Method accessor = response.getClass().getMethod("headers");
            @SuppressWarnings("unchecked")
            List<Object> headers = (List<Object>) accessor.invoke(response);
            for (Object header : headers) {
                Method nameMethod = header.getClass().getMethod("name");
                Method valueMethod = header.getClass().getMethod("value");
                String name = (String) nameMethod.invoke(header);
                if (headerName.equalsIgnoreCase(name)) {
                    return (String) valueMethod.invoke(header);
                }
            }
            return null;
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError("Unable to access HttpResponse headers", ex);
        }
    }

    private static HttpVersion anyHttpVersion() {
        Object[] enumConstants = HttpVersion.class.getEnumConstants();
        if (enumConstants != null && enumConstants.length > 0) {
            return (HttpVersion) enumConstants[0];
        }

        for (var field : HttpVersion.class.getDeclaredFields()) {
            if (field.getType() == HttpVersion.class && java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                try {
                    field.setAccessible(true);
                    Object value = field.get(null);
                    if (value instanceof HttpVersion version) {
                        return version;
                    }
                } catch (IllegalAccessException ignored) {
                }
            }
        }
        throw new IllegalStateException("Unable to obtain any HttpVersion constant for test exchange");
    }

    @Configuration
    static class TestConfig {
        @Bean
        HelloController helloController() {
            return new HelloController();
        }

        @Bean
        ExerisHandlerMethodRegistry exerisHandlerMethodRegistry() {
            return new ExerisHandlerMethodRegistry();
        }

        @Bean
        ExerisExceptionHandlerResolver exerisExceptionHandlerResolver() {
            HandlerMethodArgumentResolverComposite c = new HandlerMethodArgumentResolverComposite();
            List<org.springframework.http.converter.HttpMessageConverter<?>> converters =
                    List.of(new MappingJackson2HttpMessageConverter());
            return new ExerisExceptionHandlerResolver(c, converters);
        }
    }

    @RestController
    static class HelloController {
        @GetMapping("/hello")
        String hello() {
            return "hello";
        }
    }

    private static final class TestExchange {

        private final HttpExchange proxy;
        private final AtomicReference<HttpResponse> response = new AtomicReference<>();

        private TestExchange(HttpRequest request) {
            this.proxy = (HttpExchange) Proxy.newProxyInstance(
                    HttpExchange.class.getClassLoader(),
                    new Class<?>[]{HttpExchange.class},
                    (proxyInstance, method, args) -> switch (method.getName()) {
                        case "request" -> request;
                        case "respond" -> {
                            response.set((HttpResponse) args[0]);
                            yield null;
                        }
                        case "toString" -> "TestExchange";
                        case "hashCode" -> System.identityHashCode(proxyInstance);
                        case "equals" -> proxyInstance == args[0];
                        default -> defaultValue(method.getReturnType());
                    }
            );
        }

        static TestExchange get(HttpMethod method, String path, HttpVersion version) {
            HttpRequest request = createHttpRequest(method, path, version);
            return new TestExchange(request);
        }

        HttpExchange proxy() {
            return proxy;
        }

        HttpResponse response() {
            return response.get();
        }

        private static HttpRequest createHttpRequest(HttpMethod method, String path, HttpVersion version) {
            var constructors = HttpRequest.class.getDeclaredConstructors();
            for (var constructor : constructors) {
                try {
                    constructor.setAccessible(true);
                    Object[] args = buildConstructorArgs(constructor.getGenericParameterTypes(), method, path, version);
                    Object candidate = constructor.newInstance(args);
                    if (candidate instanceof HttpRequest request
                            && method.equals(request.method())
                            && path.equals(request.path())) {
                        return request;
                    }
                } catch (ReflectiveOperationException | IllegalArgumentException ignored) {
                }
            }
            throw new IllegalStateException("Unable to construct HttpRequest for compatibility dispatcher test");
        }

        private static Object[] buildConstructorArgs(Type[] parameterTypes,
                                                     HttpMethod method,
                                                     String path,
                                                     HttpVersion version) {
            Object[] args = new Object[parameterTypes.length];
            boolean pathAssigned = false;
            for (int i = 0; i < parameterTypes.length; i++) {
                Class<?> raw = rawClass(parameterTypes[i]);
                if (raw == HttpMethod.class) {
                    args[i] = method;
                } else if (raw == HttpVersion.class) {
                    args[i] = version;
                } else if (raw == String.class) {
                    if (!pathAssigned) {
                        args[i] = path;
                        pathAssigned = true;
                    } else {
                        args[i] = "";
                    }
                } else if (raw == Optional.class) {
                    args[i] = Optional.empty();
                } else if (raw == List.class) {
                    args[i] = List.of();
                } else {
                    args[i] = defaultValue(raw);
                }
            }
            return args;
        }

        private static Class<?> rawClass(Type type) {
            if (type instanceof Class<?> cls) {
                return cls;
            }
            if (type instanceof java.lang.reflect.ParameterizedType parameterizedType
                    && parameterizedType.getRawType() instanceof Class<?> raw) {
                return raw;
            }
            throw new IllegalArgumentException("Unsupported constructor parameter type: " + type);
        }

        private static Object defaultValue(Class<?> returnType) {
            if (!returnType.isPrimitive()) {
                return null;
            }
            if (returnType == boolean.class) {
                return false;
            }
            if (returnType == byte.class) {
                return (byte) 0;
            }
            if (returnType == short.class) {
                return (short) 0;
            }
            if (returnType == int.class) {
                return 0;
            }
            if (returnType == long.class) {
                return 0L;
            }
            if (returnType == float.class) {
                return 0f;
            }
            if (returnType == double.class) {
                return 0d;
            }
            if (returnType == char.class) {
                return '\0';
            }
            return null;
        }
    }
}
