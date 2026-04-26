/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.web.compat;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.method.support.HandlerMethodArgumentResolverComposite;

import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpRequest;
import eu.exeris.kernel.spi.http.HttpVersion;
import eu.exeris.spring.runtime.web.ExerisServerRequest;

class ExerisExceptionHandlerResolverTest {

    @Test
    void resolve_infersHandledExceptionTypeFromMethodParameterWhenAnnotationValueIsEmpty() throws Exception {
        try (var context = new AnnotationConfigApplicationContext(LocalInferenceConfig.class)) {
            LocalInferenceController controller = context.getBean(LocalInferenceController.class);
            ExerisExceptionHandlerResolver resolver = context.getBean(ExerisExceptionHandlerResolver.class);

            try (ExerisMvcServerHttpResponse response = new ExerisMvcServerHttpResponse()) {
                resolver.resolve(new IllegalStateException("boom"),
                        new HandlerMethod(controller, method(LocalInferenceController.class, "fail")),
                        request(),
                        response);

                assertThat(response.capturedStatusCode().value()).isEqualTo(409);
                assertThat(new String(response.capturedBody(), StandardCharsets.UTF_8)).contains("inferred-boom");
            }
        }
    }

    @Test
    void resolve_prefersMostSpecificLocalExceptionHandler() throws Exception {
        try (var context = new AnnotationConfigApplicationContext(LocalSpecificityConfig.class)) {
            LocalSpecificityController controller = context.getBean(LocalSpecificityController.class);
            ExerisExceptionHandlerResolver resolver = context.getBean(ExerisExceptionHandlerResolver.class);

            try (ExerisMvcServerHttpResponse response = new ExerisMvcServerHttpResponse()) {
                resolver.resolve(new IllegalArgumentException("bad-input"),
                        new HandlerMethod(controller, method(LocalSpecificityController.class, "fail")),
                        request(),
                        response);

                assertThat(response.capturedStatusCode().value()).isEqualTo(400);
                assertThat(new String(response.capturedBody(), StandardCharsets.UTF_8)).contains("specific-bad-input");
            }
        }
    }

    @Test
    void resolve_honorsControllerAdviceOrderBeforeLowerPrioritySpecificity() throws Exception {
        try (var context = new AnnotationConfigApplicationContext(OrderedAdviceConfig.class)) {
            OrderedAdviceController controller = context.getBean(OrderedAdviceController.class);
            ExerisExceptionHandlerResolver resolver = context.getBean(ExerisExceptionHandlerResolver.class);

            try (ExerisMvcServerHttpResponse response = new ExerisMvcServerHttpResponse()) {
                resolver.resolve(new IllegalArgumentException("ordered"),
                        new HandlerMethod(controller, method(OrderedAdviceController.class, "fail")),
                        request(),
                        response);

                assertThat(response.capturedStatusCode().value()).isEqualTo(409);
                assertThat(new String(response.capturedBody(), StandardCharsets.UTF_8)).contains("ordered-ordered");
            }
        }
    }

    private static ExerisNativeWebRequest request() {
        HttpRequest request = HttpRequest.noBody(HttpMethod.GET, "/errors", HttpVersion.HTTP_1_1, List.of());
        return new ExerisNativeWebRequest(new ExerisMvcServerHttpRequest(ExerisServerRequest.wrap(request)));
    }

    private static Method method(Class<?> type, String name) {
        try {
            return type.getDeclaredMethod(name);
        } catch (NoSuchMethodException ex) {
            throw new AssertionError("Method not found: " + type.getName() + "#" + name, ex);
        }
    }

    @Configuration
    static class LocalInferenceConfig {

        @Bean
        LocalInferenceController localInferenceController() {
            return new LocalInferenceController();
        }

        @Bean
        ExerisExceptionHandlerResolver exerisExceptionHandlerResolver() {
            return new ExerisExceptionHandlerResolver(new HandlerMethodArgumentResolverComposite(), converters());
        }
    }

    @Configuration
    static class LocalSpecificityConfig {

        @Bean
        LocalSpecificityController localSpecificityController() {
            return new LocalSpecificityController();
        }

        @Bean
        ExerisExceptionHandlerResolver exerisExceptionHandlerResolver() {
            return new ExerisExceptionHandlerResolver(new HandlerMethodArgumentResolverComposite(), converters());
        }
    }

    @Configuration
    static class OrderedAdviceConfig {

        @Bean
        OrderedAdviceController orderedAdviceController() {
            return new OrderedAdviceController();
        }

        @Bean
        OrderedRuntimeAdvice orderedRuntimeAdvice() {
            return new OrderedRuntimeAdvice();
        }

        @Bean
        LateSpecificAdvice lateSpecificAdvice() {
            return new LateSpecificAdvice();
        }

        @Bean
        ExerisExceptionHandlerResolver exerisExceptionHandlerResolver() {
            return new ExerisExceptionHandlerResolver(new HandlerMethodArgumentResolverComposite(), converters());
        }
    }

    private static List<HttpMessageConverter<?>> converters() {
        return List.of(new MappingJackson2HttpMessageConverter());
    }

    @RestController
    static class LocalInferenceController {

        @GetMapping("/inferred")
        String fail() {
            throw new IllegalStateException("boom");
        }

        @ExceptionHandler
        @ResponseStatus(HttpStatus.CONFLICT)
        String handle(IllegalStateException ex) {
            return "inferred-" + ex.getMessage();
        }
    }

    @RestController
    static class LocalSpecificityController {

        @GetMapping("/specific")
        String fail() {
            throw new IllegalArgumentException("bad-input");
        }

        @ExceptionHandler(RuntimeException.class)
        @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
        String generic(RuntimeException ex) {
            return "generic-" + ex.getMessage();
        }

        @ExceptionHandler(IllegalArgumentException.class)
        @ResponseStatus(HttpStatus.BAD_REQUEST)
        String specific(IllegalArgumentException ex) {
            return "specific-" + ex.getMessage();
        }
    }

    @RestController
    static class OrderedAdviceController {

        @GetMapping("/ordered")
        String fail() {
            throw new IllegalArgumentException("ordered");
        }
    }

    @ControllerAdvice
    @Order(0)
    static class OrderedRuntimeAdvice {

        @ExceptionHandler(RuntimeException.class)
        @ResponseStatus(HttpStatus.CONFLICT)
        String ordered(RuntimeException ex) {
            return "ordered-" + ex.getMessage();
        }
    }

    @ControllerAdvice
    @Order(10)
    static class LateSpecificAdvice {

        @ExceptionHandler(IllegalArgumentException.class)
        @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
        String specific(IllegalArgumentException ex) {
            return "late-specific-" + ex.getMessage();
        }
    }
}
