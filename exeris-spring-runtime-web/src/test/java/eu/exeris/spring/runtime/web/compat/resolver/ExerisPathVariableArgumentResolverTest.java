/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.web.compat.resolver;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.ModelAndViewContainer;

import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpRequest;
import eu.exeris.kernel.spi.http.HttpVersion;
import eu.exeris.spring.runtime.web.ExerisServerRequest;
import eu.exeris.spring.runtime.web.compat.ExerisHandlerMethodRegistry;
import eu.exeris.spring.runtime.web.compat.ExerisMvcServerHttpRequest;
import eu.exeris.spring.runtime.web.compat.ExerisNativeWebRequest;

/**
 * Locks in the path-variable resolver's delegation to {@link ExerisCompatTypeConverter}.
 * Companion to the @RequestParam/@RequestHeader resolver tests — proves a future
 * refactor cannot regress the path-variable side of the shared seam in isolation.
 */
class ExerisPathVariableArgumentResolverTest {

    private final ExerisPathVariableArgumentResolver resolver = new ExerisPathVariableArgumentResolver();

    @Test
    void uuidPathVariableResolved() throws Exception {
        ExerisNativeWebRequest webRequest = webRequestWithVars(
                Map.of("id", "550e8400-e29b-41d4-a716-446655440000"));

        assertThat(resolver.supportsParameter(parameter("acceptUuid", 0))).isTrue();
        assertThat(resolve("acceptUuid", 0, webRequest))
                .isEqualTo(UUID.fromString("550e8400-e29b-41d4-a716-446655440000"));
    }

    @Test
    void enumPathVariableResolvedViaConversionService() throws Exception {
        ExerisNativeWebRequest webRequest = webRequestWithVars(Map.of("status", "CONFIRMED"));

        assertThat(resolver.supportsParameter(parameter("acceptStatus", 0))).isTrue();
        assertThat(resolve("acceptStatus", 0, webRequest)).isEqualTo(Status.CONFIRMED);
    }

    @Test
    void rejectsMultiValuePathVariableType() throws Exception {
        // Pins the deliberate Collection/Map/array rejection — the
        // ExerisCompatTypeConverter rationale documents why we cannot let
        // StringToCollectionConverter silently accept these.
        assertThat(resolver.supportsParameter(parameter("listPathVar", 0))).isFalse();
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
            case "acceptUuid" -> new Class<?>[]{UUID.class};
            case "acceptStatus" -> new Class<?>[]{Status.class};
            case "listPathVar" -> new Class<?>[]{List.class};
            default -> throw new IllegalArgumentException("Unknown method: " + methodName);
        };
    }

    private static ExerisNativeWebRequest webRequestWithVars(Map<String, String> vars) {
        HttpRequest request = HttpRequest.noBody(HttpMethod.GET, "/items", HttpVersion.HTTP_1_1, List.of());
        ExerisNativeWebRequest webRequest =
                new ExerisNativeWebRequest(new ExerisMvcServerHttpRequest(ExerisServerRequest.wrap(request)));
        webRequest.setAttribute(ExerisHandlerMethodRegistry.PATH_VARIABLES_ATTRIBUTE,
                vars, NativeWebRequest.SCOPE_REQUEST);
        return webRequest;
    }

    @SuppressWarnings("unused")
    static final class TestController {
        @SuppressWarnings("unused")
        void acceptUuid(@PathVariable("id") UUID id) {
            consume(id);
        }

        @SuppressWarnings("unused")
        void acceptStatus(@PathVariable("status") Status status) {
            consume(status);
        }

        @SuppressWarnings("unused")
        void listPathVar(@PathVariable("ids") List<String> ids) {
            consume(ids);
        }

        private static void consume(Object... ignored) {
            // no-op
        }
    }

    enum Status { PENDING, CONFIRMED, CANCELLED }
}
