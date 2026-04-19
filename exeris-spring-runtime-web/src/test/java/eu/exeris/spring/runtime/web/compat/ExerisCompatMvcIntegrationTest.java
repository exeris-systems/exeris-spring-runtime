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
import eu.exeris.spring.runtime.web.autoconfigure.ExerisCompatAutoConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Module-level integration test for the Phase 2 Compatibility Mode bridge.
 *
 * <p>Wires a real Spring {@link AnnotationConfigApplicationContext} with
 * {@link ExerisCompatAutoConfiguration} and verifies end-to-end dispatch from
 * an Exeris {@link HttpExchange} through the compat path to a Spring
 * {@code @RestController}.
 *
 * <p>No servlet API, no spring-webmvc, no DispatcherServlet.
 */
class ExerisCompatMvcIntegrationTest {

    private AnnotationConfigApplicationContext context;
    private ExerisCompatDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(
                new MapPropertySource("testProps", Map.of("exeris.runtime.web.mode", "compatibility")));
        context.register(ExerisCompatAutoConfiguration.class);
        context.register(TestControllersConfig.class);
        context.refresh();
        dispatcher = context.getBean(ExerisCompatDispatcher.class);
    }

    @AfterEach
    void tearDown() {
        context.close();
    }

    // ── Scenario 1: simple GET ──────────────────────────────────────────────

    @Test
    void compatMode_getHello_returns200WithBody() throws Exception {
        TestExchange exchange = TestExchange.get(HttpMethod.GET, "/compat-hello", anyHttpVersion());
        dispatcher.handle(exchange.proxy());
        assertThat(statusOf(exchange.response())).isEqualTo(200);
        assertThat(bodyOf(exchange.response())).contains("compat-hello");
    }

    // ── Scenario 2: @RequestParam ──────────────────────────────────────────

    @Test
    void compatMode_requestParam_passedToHandler() throws Exception {
        TestExchange exchange = TestExchange.get(HttpMethod.GET, "/compat-greet?name=World", anyHttpVersion());
        dispatcher.handle(exchange.proxy());
        assertThat(statusOf(exchange.response())).isEqualTo(200);
        assertThat(bodyOf(exchange.response())).contains("World");
    }

    // ── Scenario 3: @PathVariable ──────────────────────────────────────────

    @Test
    void compatMode_pathVariable_extracted() throws Exception {
        TestExchange exchange = TestExchange.get(HttpMethod.GET, "/compat-items/42", anyHttpVersion());
        dispatcher.handle(exchange.proxy());
        assertThat(statusOf(exchange.response())).isEqualTo(200);
        assertThat(bodyOf(exchange.response())).contains("42");
    }

    // ── Scenario 4: @RequestHeader ─────────────────────────────────────────

    @Test
    void compatMode_requestHeader_read() throws Exception {
        TestExchange exchange = TestExchange.getWithHeader(
                HttpMethod.GET, "/compat-header", "X-Compat-Token", "token-abc", anyHttpVersion());
        dispatcher.handle(exchange.proxy());
        assertThat(statusOf(exchange.response())).isEqualTo(200);
        assertThat(bodyOf(exchange.response())).contains("token-abc");
    }

    // ── Scenario 5: @ExceptionHandler on controller ────────────────────────

    @Test
    void compatMode_controllerExceptionHandler_mapsException() throws Exception {
        TestExchange exchange = TestExchange.get(HttpMethod.GET, "/compat-throw", anyHttpVersion());
        dispatcher.handle(exchange.proxy());
        assertThat(statusOf(exchange.response())).isEqualTo(400);
    }

    // ── Scenario 6: @ControllerAdvice ─────────────────────────────────────

    @Test
    void compatMode_controllerAdvice_handlesException() throws Exception {
        TestExchange exchange = TestExchange.get(HttpMethod.GET, "/compat-throw-global", anyHttpVersion());
        dispatcher.handle(exchange.proxy());
        assertThat(statusOf(exchange.response())).isEqualTo(422);
    }

    // ── Scenario 7: missing route → 404 ───────────────────────────────────

    @Test
    void compatMode_missingRoute_returns404() throws Exception {
        TestExchange exchange = TestExchange.get(HttpMethod.GET, "/no-such-route", anyHttpVersion());
        dispatcher.handle(exchange.proxy());
        assertThat(statusOf(exchange.response())).isEqualTo(404);
    }

    // ── Scenario 8: ResponseEntity with custom status ─────────────────────

    @Test
    void compatMode_responseEntity_propagatesStatus() throws Exception {
        TestExchange exchange = TestExchange.get(HttpMethod.GET, "/compat-created", anyHttpVersion());
        dispatcher.handle(exchange.proxy());
        assertThat(statusOf(exchange.response())).isEqualTo(201);
    }

    // ── Scenario 9: POST + @RequestBody ───────────────────────────────────

    @Test
    void compatMode_postWithRequestBody_echoesBody() throws Exception {
        // JSON string literal "ping" — Jackson deserialises to Java String "ping"
        byte[] bodyBytes = "\"ping\"".getBytes(StandardCharsets.UTF_8);
        TestExchange exchange = TestExchange.post(HttpMethod.POST, "/compat-echo", bodyBytes, anyHttpVersion());
        dispatcher.handle(exchange.proxy());
        assertThat(statusOf(exchange.response())).isEqualTo(200);
        assertThat(bodyOf(exchange.response())).contains("ping");
    }

    // ── Scenario 10: class-level @RequestMapping prefix combined with method ──

    @Test
    void compatMode_classLevelPrefix_routeResolved() throws Exception {
        TestExchange exchange = TestExchange.get(HttpMethod.GET, "/compat-prefix/hello", anyHttpVersion());
        dispatcher.handle(exchange.proxy());
        assertThat(statusOf(exchange.response())).isEqualTo(200);
        assertThat(bodyOf(exchange.response())).contains("prefix-hello");
    }

    // ── Scenario 11: class-level prefix + @PathVariable UUID ──────────────────

    @Test
    void compatMode_classLevelPrefixWithUuidPathVariable_resolved() throws Exception {
        TestExchange exchange = TestExchange.get(HttpMethod.GET, "/compat-prefix/items/550e8400-e29b-41d4-a716-446655440000", anyHttpVersion());
        dispatcher.handle(exchange.proxy());
        assertThat(statusOf(exchange.response())).isEqualTo(200);
        assertThat(bodyOf(exchange.response())).contains("550e8400-e29b-41d4-a716-446655440000");
    }

    // ─────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────

    private static int statusOf(HttpResponse response) {
        try {
            Method m = response.getClass().getMethod("status");
            return ((HttpStatus) m.invoke(response)).code();
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError("Cannot read HttpResponse status", ex);
        }
    }

    private static String bodyOf(HttpResponse response) {
        try {
            Method m = response.getClass().getMethod("body");
            eu.exeris.kernel.spi.memory.LoanedBuffer buffer =
                    (eu.exeris.kernel.spi.memory.LoanedBuffer) m.invoke(response);
            if (buffer == null) {
                return "";
            }
            java.lang.foreign.MemorySegment segment = buffer.segment();
            return new String(segment.toArray(java.lang.foreign.ValueLayout.JAVA_BYTE), StandardCharsets.UTF_8);
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError("Cannot read HttpResponse body", ex);
        }
    }

    private static HttpVersion anyHttpVersion() {
        for (var field : HttpVersion.class.getDeclaredFields()) {
            if (field.getType() == HttpVersion.class
                    && java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
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
        throw new IllegalStateException("Unable to obtain any HttpVersion constant");
    }

    // ─────────────────────────────────────────────────────────────────────
    // Test infrastructure — Spring config and controllers
    // ─────────────────────────────────────────────────────────────────────

    @Configuration
    static class TestControllersConfig {
        @Bean
        CompatTestController compatTestController() {
            return new CompatTestController();
        }

        @Bean
        CompatGlobalAdvice compatGlobalAdvice() {
            return new CompatGlobalAdvice();
        }

        @Bean
        CompatPrefixTestController compatPrefixTestController() {
            return new CompatPrefixTestController();
        }
    }

    @RestController
    static class CompatTestController {

        @GetMapping("/compat-hello")
        String hello() {
            return "compat-hello";
        }

        @GetMapping("/compat-greet")
        String greet(@RequestParam("name") String name) {
            return "Hello, " + name;
        }

        @GetMapping("/compat-items/{id}")
        String item(@PathVariable("id") String id) {
            return "item-" + id;
        }

        @GetMapping("/compat-header")
        String header(@RequestHeader("X-Compat-Token") String token) {
            return "token=" + token;
        }

        @GetMapping("/compat-throw")
        String throwLocal() {
            throw new IllegalArgumentException("bad-local");
        }

        @GetMapping("/compat-throw-global")
        String throwGlobal() {
            throw new UnsupportedOperationException("bad-global");
        }

        @GetMapping("/compat-created")
        ResponseEntity<String> created() {
            return ResponseEntity.status(201).body("created");
        }

        @PostMapping("/compat-echo")
        String echo(@RequestBody String message) {
            return message;
        }

        @ExceptionHandler(IllegalArgumentException.class)
        @ResponseStatus(org.springframework.http.HttpStatus.BAD_REQUEST)
        String handleLocalException(IllegalArgumentException ex) {
            return "bad-request: " + ex.getMessage();
        }
    }

    @ControllerAdvice
    static class CompatGlobalAdvice {
        @ExceptionHandler(UnsupportedOperationException.class)
        @ResponseStatus(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY)
        String handleGlobalException(UnsupportedOperationException ex) {
            return "unprocessable: " + ex.getMessage();
        }
    }

    @RestController
    @RequestMapping("/compat-prefix")
    static class CompatPrefixTestController {

        @GetMapping("/hello")
        String hello() {
            return "prefix-hello";
        }

        @GetMapping("/items/{id}")
        String item(@PathVariable("id") java.util.UUID id) {
            return "uuid-item-" + id;
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // TestExchange — JDK Proxy-based HttpExchange stub (no servlet API)
    // ─────────────────────────────────────────────────────────────────────

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
                    });
        }

        static TestExchange get(HttpMethod method, String path, HttpVersion version) {
            return new TestExchange(buildRequest(method, path, version, List.of()));
        }

        static TestExchange getWithHeader(HttpMethod method, String path,
                                          String headerName, String headerValue,
                                          HttpVersion version) {
            eu.exeris.kernel.spi.http.HttpHeader header = buildHeader(headerName, headerValue);
            return new TestExchange(buildRequest(method, path, version, List.of(header)));
        }

        static TestExchange post(HttpMethod method, String path, byte[] bodyBytes, HttpVersion version) {
            List<eu.exeris.kernel.spi.http.HttpHeader> headers = List.of(
                    buildHeader("Content-Type", "application/json"),
                    buildHeader("Content-Length", String.valueOf(bodyBytes.length))
            );
            return new TestExchange(buildRequestWithBody(method, path, version, headers, bodyBytes));
        }

        HttpExchange proxy() {
            return proxy;
        }

        HttpResponse response() {
            return response.get();
        }

        private static eu.exeris.kernel.spi.http.HttpHeader buildHeader(String name, String value) {
            try {
                var constructors = eu.exeris.kernel.spi.http.HttpHeader.class.getDeclaredConstructors();
                for (var c : constructors) {
                    c.setAccessible(true);
                    if (c.getParameterCount() == 2) {
                        Object candidate = c.newInstance(name, value);
                        if (candidate instanceof eu.exeris.kernel.spi.http.HttpHeader h) {
                            return h;
                        }
                    }
                }
            } catch (ReflectiveOperationException ex) {
                throw new IllegalStateException("Cannot build HttpHeader", ex);
            }
            throw new IllegalStateException("No 2-arg HttpHeader constructor found");
        }

        private static HttpRequest buildRequest(HttpMethod method, String path,
                                                HttpVersion version,
                                                List<eu.exeris.kernel.spi.http.HttpHeader> headers) {
            var constructors = HttpRequest.class.getDeclaredConstructors();
            for (var constructor : constructors) {
                try {
                    constructor.setAccessible(true);
                    Object[] args = buildArgs(constructor.getGenericParameterTypes(),
                            method, path, version, headers);
                    Object candidate = constructor.newInstance(args);
                    if (candidate instanceof HttpRequest req
                            && method.equals(req.method())
                            && (path.equals(req.path()) || path.startsWith(req.path()))) {
                        return req;
                    }
                } catch (ReflectiveOperationException | IllegalArgumentException ignored) {
                }
            }
            // path may contain query string — retry with just the path portion
            String basePath = path.contains("?") ? path.substring(0, path.indexOf('?')) : path;
            for (var constructor : constructors) {
                try {
                    constructor.setAccessible(true);
                    Object[] args = buildArgs(constructor.getGenericParameterTypes(),
                            method, basePath, version, headers);
                    Object candidate = constructor.newInstance(args);
                    if (candidate instanceof HttpRequest req
                            && method.equals(req.method())) {
                        return req;
                    }
                } catch (ReflectiveOperationException | IllegalArgumentException ignored) {
                }
            }
            throw new IllegalStateException("Unable to construct HttpRequest for path=" + path);
        }

        private static HttpRequest buildRequestWithBody(HttpMethod method, String path,
                                                        HttpVersion version,
                                                        List<eu.exeris.kernel.spi.http.HttpHeader> headers,
                                                        byte[] bodyBytes) {
            eu.exeris.kernel.spi.memory.LoanedBuffer body = new HeapTestBuffer(bodyBytes);
            var constructors = HttpRequest.class.getDeclaredConstructors();
            for (var constructor : constructors) {
                try {
                    constructor.setAccessible(true);
                    Type[] types = constructor.getGenericParameterTypes();
                    Object[] args = new Object[types.length];
                    boolean pathAssigned = false;
                    for (int i = 0; i < types.length; i++) {
                        Class<?> raw = rawClass(types[i]);
                        if (raw == HttpMethod.class) {
                            args[i] = method;
                        } else if (raw == HttpVersion.class) {
                            args[i] = version;
                        } else if (raw == String.class) {
                            if (!pathAssigned) { args[i] = path; pathAssigned = true; }
                            else args[i] = "";
                        } else if (raw == List.class) {
                            args[i] = headers;
                        } else if (eu.exeris.kernel.spi.memory.LoanedBuffer.class.isAssignableFrom(raw)) {
                            args[i] = body;
                        } else if (raw == Optional.class) {
                            args[i] = Optional.empty();
                        } else {
                            args[i] = defaultValue(raw);
                        }
                    }
                    Object candidate = constructor.newInstance(args);
                    if (candidate instanceof HttpRequest req && method.equals(req.method())) {
                        return req;
                    }
                } catch (ReflectiveOperationException | IllegalArgumentException ignored) {
                }
            }
            throw new IllegalStateException("Unable to construct HttpRequest with body for path=" + path);
        }

        private static Object[] buildArgs(Type[] parameterTypes,
                                          HttpMethod method,
                                          String path,
                                          HttpVersion version,
                                          List<eu.exeris.kernel.spi.http.HttpHeader> headers) {
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
                    args[i] = headers.isEmpty() ? List.of() : headers;
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
            if (type instanceof java.lang.reflect.ParameterizedType pt
                    && pt.getRawType() instanceof Class<?> raw) {
                return raw;
            }
            throw new IllegalArgumentException("Unsupported type: " + type);
        }

        private static Object defaultValue(Class<?> type) {
            if (!type.isPrimitive()) return null;
            if (type == boolean.class) return false;
            if (type == int.class) return 0;
            if (type == long.class) return 0L;
            if (type == double.class) return 0d;
            if (type == float.class) return 0f;
            if (type == byte.class) return (byte) 0;
            if (type == short.class) return (short) 0;
            if (type == char.class) return '\0';
            return null;
        }

        /**
         * Minimal heap-backed {@link eu.exeris.kernel.spi.memory.LoanedBuffer} for test body stubs.
         * All lifecycle operations are no-ops; segment is a heap-backed MemorySegment.
         */
        private static final class HeapTestBuffer implements eu.exeris.kernel.spi.memory.LoanedBuffer {

            private final java.lang.foreign.MemorySegment segment;

            HeapTestBuffer(byte[] bytes) {
                this.segment = java.lang.foreign.MemorySegment.ofArray(bytes.clone());
            }

            @Override public java.lang.foreign.MemorySegment segment() { return segment; }
            @Override public long size() { return segment.byteSize(); }
            @Override public long capacity() { return segment.byteSize(); }
            @Override public eu.exeris.kernel.spi.memory.LoanedBuffer slice(long o, long l) {
                return new HeapTestBuffer(segment.asSlice(o, l).toArray(java.lang.foreign.ValueLayout.JAVA_BYTE));
            }
            @Override public eu.exeris.kernel.spi.memory.LoanedBuffer view() { return this; }
            @Override public eu.exeris.kernel.spi.memory.LoanedBuffer peek(long o, long l) { return this; }
            @Override public void retain() {}
            @Override public void close() {}
            @Override public int refCount() { return 1; }
            @Override public void setSize(long newSize) {}
            @Override public boolean isAlive() { return true; }
            @Override public void addCloseAction(Runnable action) {}
        }
    }
}
