/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.web.compat.resolver;

import java.lang.reflect.Method;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.method.support.ModelAndViewContainer;

import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpRequest;
import eu.exeris.kernel.spi.http.HttpVersion;
import eu.exeris.spring.runtime.web.ExerisServerRequest;
import eu.exeris.spring.runtime.web.compat.ExerisMvcServerHttpRequest;
import eu.exeris.spring.runtime.web.compat.ExerisNativeWebRequest;

class ExerisRequestParamArgumentResolverTest {

    private final ExerisRequestParamArgumentResolver resolver = new ExerisRequestParamArgumentResolver();

    @Test
    void supportsAndConvertsExtendedPrimitiveAndTypedValues() throws Exception {
        ExerisNativeWebRequest webRequest = webRequest(
                "/params?f=1.5&s=7&b=2&c=Z&uuid=550e8400-e29b-41d4-a716-446655440000"
                        + "&date=2026-04-19&dateTime=2026-04-19T10:15:30");

        assertThat(resolver.supportsParameter(parameter("extendedTypes", 0))).isTrue();
        assertThat(resolver.supportsParameter(parameter("extendedTypes", 1))).isTrue();
        assertThat(resolver.supportsParameter(parameter("extendedTypes", 2))).isTrue();
        assertThat(resolver.supportsParameter(parameter("extendedTypes", 3))).isTrue();

        assertThat(resolve("extendedTypes", 0, webRequest)).isEqualTo(1.5f);
        assertThat(resolve("extendedTypes", 1, webRequest)).isEqualTo((short) 7);
        assertThat(resolve("extendedTypes", 2, webRequest)).isEqualTo((byte) 2);
        assertThat(resolve("extendedTypes", 3, webRequest)).isEqualTo('Z');
        assertThat(resolve("extendedTypes", 4, webRequest))
                .isEqualTo(UUID.fromString("550e8400-e29b-41d4-a716-446655440000"));
        assertThat(resolve("extendedTypes", 5, webRequest)).isEqualTo(LocalDate.of(2026, 4, 19));
        assertThat(resolve("extendedTypes", 6, webRequest))
                .isEqualTo(LocalDateTime.of(2026, 4, 19, 10, 15, 30));
    }

    @Test
    void rejectsUnsupportedRequestParamTypes() throws Exception {
        assertThat(resolver.supportsParameter(parameter("unsupportedType", 0))).isFalse();
    }

    private Object resolve(String methodName, int parameterIndex, ExerisNativeWebRequest webRequest) throws Exception {
        return resolver.resolveArgument(
                parameter(methodName, parameterIndex),
                new ModelAndViewContainer(),
                webRequest,
                null);
    }

    private MethodParameter parameter(String methodName, int parameterIndex) throws Exception {
        Method method = TestController.class.getDeclaredMethod(methodName, methodSignature(methodName));
        return new MethodParameter(method, parameterIndex);
    }

    private static Class<?>[] methodSignature(String methodName) {
        return switch (methodName) {
            case "extendedTypes" -> new Class<?>[]{
                    float.class,
                    short.class,
                    byte.class,
                    char.class,
                    UUID.class,
                    LocalDate.class,
                    LocalDateTime.class
            };
            case "unsupportedType" -> new Class<?>[]{List.class};
            default -> throw new IllegalArgumentException("Unknown method: " + methodName);
        };
    }

    private static ExerisNativeWebRequest webRequest(String path) {
        HttpRequest request = HttpRequest.noBody(HttpMethod.GET, path, HttpVersion.HTTP_1_1, List.of());
        return new ExerisNativeWebRequest(new ExerisMvcServerHttpRequest(ExerisServerRequest.wrap(request)));
    }

    @SuppressWarnings("unused")
    static final class TestController {

        @SuppressWarnings("unused")
        void extendedTypes(@RequestParam("f") float f,
                           @RequestParam("s") short s,
                           @RequestParam("b") byte b,
                           @RequestParam("c") char c,
                           @RequestParam("uuid") UUID uuid,
                           @RequestParam("date") LocalDate date,
                           @RequestParam("dateTime") LocalDateTime dateTime) {
        }

        @SuppressWarnings("unused")
        void unsupportedType(@RequestParam("value") List<String> values) {
        }
    }
}
