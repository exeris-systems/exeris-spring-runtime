/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.web.compat.resolver;

import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.method.support.ModelAndViewContainer;

import eu.exeris.kernel.spi.http.HttpHeader;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpRequest;
import eu.exeris.kernel.spi.http.HttpVersion;
import eu.exeris.spring.runtime.web.ExerisServerRequest;
import eu.exeris.spring.runtime.web.compat.ExerisMvcServerHttpRequest;
import eu.exeris.spring.runtime.web.compat.ExerisNativeWebRequest;

class ExerisRequestHeaderArgumentResolverTest {

    private final ExerisRequestHeaderArgumentResolver resolver = new ExerisRequestHeaderArgumentResolver();

    @Test
    void supportsAndConvertsScalarAndTypedHeaderValues() throws Exception {
        ExerisNativeWebRequest webRequest = webRequest(List.of(
                new HttpHeader("X-Token", "abc"),
                new HttpHeader("X-Count", "42"),
                new HttpHeader("X-Long", "9876543210"),
                new HttpHeader("X-Trace", "550e8400-e29b-41d4-a716-446655440000")
        ));

        assertThat(resolver.supportsParameter(parameter("typedHeaders", 0))).isTrue();
        assertThat(resolver.supportsParameter(parameter("typedHeaders", 1))).isTrue();
        assertThat(resolver.supportsParameter(parameter("typedHeaders", 2))).isTrue();
        assertThat(resolver.supportsParameter(parameter("typedHeaders", 3))).isTrue();

        assertThat(resolve("typedHeaders", 0, webRequest)).isEqualTo("abc");
        assertThat(resolve("typedHeaders", 1, webRequest)).isEqualTo(42);
        assertThat(resolve("typedHeaders", 2, webRequest)).isEqualTo(9876543210L);
        assertThat(resolve("typedHeaders", 3, webRequest))
                .isEqualTo(UUID.fromString("550e8400-e29b-41d4-a716-446655440000"));
    }

    @Test
    void rejectsUnsupportedHeaderType() throws Exception {
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
            case "typedHeaders" -> new Class<?>[]{String.class, int.class, long.class, UUID.class};
            case "unsupportedType" -> new Class<?>[]{List.class};
            default -> throw new IllegalArgumentException("Unknown method: " + methodName);
        };
    }

    private static ExerisNativeWebRequest webRequest(List<HttpHeader> headers) {
        HttpRequest request = HttpRequest.noBody(HttpMethod.GET, "/headers", HttpVersion.HTTP_1_1, headers);
        return new ExerisNativeWebRequest(new ExerisMvcServerHttpRequest(ExerisServerRequest.wrap(request)));
    }

    @SuppressWarnings("unused")
    static final class TestController {
        @SuppressWarnings("unused")
        void typedHeaders(@RequestHeader("X-Token") String token,
                          @RequestHeader("X-Count") int count,
                          @RequestHeader("X-Long") long longValue,
                          @RequestHeader("X-Trace") UUID trace) {
            consume(token, count, longValue, trace);
        }

        @SuppressWarnings("unused")
        void unsupportedType(@RequestHeader("X-List") List<String> values) {
            consume(values);
        }

        private static void consume(Object... ignored) {
            // no-op
        }
    }
}
