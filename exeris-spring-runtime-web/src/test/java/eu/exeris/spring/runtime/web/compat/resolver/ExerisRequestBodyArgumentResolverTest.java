/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.web.compat.resolver;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.method.support.ModelAndViewContainer;

import eu.exeris.kernel.spi.http.HttpHeader;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpRequest;
import eu.exeris.kernel.spi.http.HttpVersion;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.spring.runtime.web.ExerisServerRequest;
import eu.exeris.spring.runtime.web.compat.ExerisMvcServerHttpRequest;
import eu.exeris.spring.runtime.web.compat.ExerisNativeWebRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

class ExerisRequestBodyArgumentResolverTest {

    private final List<HttpMessageConverter<?>> converters = List.of(new MappingJackson2HttpMessageConverter());
    private final ExerisRequestBodyArgumentResolver resolver = new ExerisRequestBodyArgumentResolver(converters);

    @Test
    void missingContentType_fallsBackToJson() throws Exception {
        // Body is JSON but the request omits Content-Type. The resolver must try
        // OCTET_STREAM-aware converters first and then fall back to JSON, rather
        // than rejecting the request outright.
        ExerisNativeWebRequest webRequest = webRequest("\"hello\"".getBytes(StandardCharsets.UTF_8), List.of());

        Object result = resolver.resolveArgument(parameter("acceptString", 0),
                new ModelAndViewContainer(), webRequest, null);

        assertThat(result).isEqualTo("hello");
    }

    @Test
    void explicitJsonContentType_resolvesNormally() throws Exception {
        ExerisNativeWebRequest webRequest = webRequest("\"world\"".getBytes(StandardCharsets.UTF_8),
                List.of(new HttpHeader("Content-Type", "application/json")));

        Object result = resolver.resolveArgument(parameter("acceptString", 0),
                new ModelAndViewContainer(), webRequest, null);

        assertThat(result).isEqualTo("world");
    }

    @Test
    void validBody_passesValidation() throws Exception {
        ExerisRequestBodyArgumentResolver validating =
                new ExerisRequestBodyArgumentResolver(converters, newValidator());
        ExerisNativeWebRequest webRequest = webRequest(
                "{\"name\":\"ok\"}".getBytes(StandardCharsets.UTF_8),
                List.of(new HttpHeader("Content-Type", "application/json")));

        Object result = validating.resolveArgument(payloadParameter(),
                new ModelAndViewContainer(), webRequest, null);

        assertThat(result).isInstanceOf(Payload.class);
        assertThat(((Payload) result).name()).isEqualTo("ok");
    }

    @Test
    void invalidBody_withValidator_throwsMethodArgumentNotValidException() throws Exception {
        ExerisRequestBodyArgumentResolver validating =
                new ExerisRequestBodyArgumentResolver(converters, newValidator());
        ExerisNativeWebRequest webRequest = webRequest(
                "{\"name\":\"\"}".getBytes(StandardCharsets.UTF_8),
                List.of(new HttpHeader("Content-Type", "application/json")));

        assertThatThrownBy(() -> validating.resolveArgument(payloadParameter(),
                new ModelAndViewContainer(), webRequest, null))
                .isInstanceOf(MethodArgumentNotValidException.class)
                .satisfies(ex -> {
                    var bindingResult = ((MethodArgumentNotValidException) ex).getBindingResult();
                    assertThat(bindingResult.hasFieldErrors("name")).isTrue();
                });
    }

    @Test
    void invalidBody_withoutValidator_returnsBodyUnvalidated() throws Exception {
        // Resolver constructed without a validator must not pretend validation
        // happened — the silent-skip behaviour mirrors Spring's binder when no
        // validator is wired. This guards against accidental fail-open by the
        // resolver if a future change made the validator field non-optional.
        ExerisNativeWebRequest webRequest = webRequest(
                "{\"name\":\"\"}".getBytes(StandardCharsets.UTF_8),
                List.of(new HttpHeader("Content-Type", "application/json")));

        Object result = resolver.resolveArgument(payloadParameter(),
                new ModelAndViewContainer(), webRequest, null);

        assertThat(result).isInstanceOf(Payload.class);
        assertThat(((Payload) result).name()).isEmpty();
    }

    private static MethodParameter parameter(String methodName, int index) throws NoSuchMethodException {
        Method method = TestController.class.getDeclaredMethod(methodName, String.class);
        return new MethodParameter(method, index);
    }

    private static MethodParameter payloadParameter() throws NoSuchMethodException {
        Method method = TestController.class.getDeclaredMethod("acceptValidatedPayload", Payload.class);
        return new MethodParameter(method, 0);
    }

    private static LocalValidatorFactoryBean newValidator() {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        return validator;
    }

    public record Payload(@NotBlank String name) {}

    private static ExerisNativeWebRequest webRequest(byte[] bodyBytes, List<HttpHeader> headers) {
        HttpRequest request = new HttpRequest(HttpMethod.POST, "/echo", HttpVersion.HTTP_1_1,
                headers, new HeapTestBuffer(bodyBytes));
        return new ExerisNativeWebRequest(new ExerisMvcServerHttpRequest(ExerisServerRequest.wrap(request)));
    }

    @SuppressWarnings("unused")
    static final class TestController {
        @SuppressWarnings("unused")
        void acceptString(@RequestBody String message) {
            consume(message);
        }

        @SuppressWarnings("unused")
        void acceptValidatedPayload(@Valid @RequestBody Payload payload) {
            consume(payload);
        }

        private static void consume(Object ignored) {
            // no-op
        }
    }

    private static final class HeapTestBuffer implements LoanedBuffer {
        private final MemorySegment segment;

        HeapTestBuffer(byte[] bytes) {
            this.segment = MemorySegment.ofArray(bytes.clone());
        }

        @Override public MemorySegment segment() { return segment; }
        @Override public long size() { return segment.byteSize(); }
        @Override public long capacity() { return segment.byteSize(); }
        @Override public LoanedBuffer slice(long offset, long length) {
            return new HeapTestBuffer(segment.asSlice(offset, length).toArray(ValueLayout.JAVA_BYTE));
        }
        @Override public LoanedBuffer view() { return this; }
        @Override public LoanedBuffer peek(long offset, long length) { return this; }
        @Override public void retain() { }
        @Override public void close() { }
        @Override public int refCount() { return 1; }
        @Override public void setSize(long newSize) { }
        @Override public boolean isAlive() { return true; }
        @Override public void addCloseAction(Runnable action) { }
    }
}
