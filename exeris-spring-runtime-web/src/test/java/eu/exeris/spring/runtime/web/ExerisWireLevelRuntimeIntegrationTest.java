/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.web;

import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.spring.boot.autoconfigure.ExerisRuntimeAutoConfiguration;
import eu.exeris.spring.boot.autoconfigure.ExerisRuntimeLifecycle;
import eu.exeris.spring.runtime.web.autoconfigure.ExerisWebAutoConfiguration;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

class ExerisWireLevelRuntimeIntegrationTest {

    /**
     * Blocked on kernel bootstrap seam for deterministic embeddable wire-level tests.
     *
     * <p>Current blocker in this workspace:
     * {@code ExerisRuntimeLifecycle.start()} calls {@code HttpKernelProviders.httpServerEngine()},
     * but at runtime this fails with {@code NoSuchElementException: ScopedValue not bound}
     * during the bootstrap callback path. The test therefore cannot reliably obtain a concrete
     * server engine handle and proceed to readiness/assertion steps.
     *
     * <p>Required kernel seam/API to enable this test:
     * a stable embeddable bootstrap fixture (or equivalent public API) that exposes a started
     * {@code HttpServerEngine} handle in test scope, plus deterministic bound-port visibility.
     */
    @Test
    @Disabled("Blocked by kernel boot seam: HttpKernelProviders HTTP_SERVER_ENGINE ScopedValue is not bound during lifecycle bootstrap callback")
    void pureMode_bindsPort_routesRequest_and_stopsRuntimeOnContextClose() throws Exception {
        int port = reserveEphemeralPort();
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(250))
                .build();

        AnnotationConfigApplicationContext context = createContext(port);
        WireHelloHandler handler = context.getBean(WireHelloHandler.class);
        ExerisRuntimeLifecycle lifecycle = context.getBean(ExerisRuntimeLifecycle.class);

        assertThat(lifecycle.isRunning()).isTrue();

        HttpResponse<String> response = awaitSuccessfulGet(client, port, "/wire-hello", Duration.ofSeconds(8));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("wire-hello");
        assertThat(handler.invocations()).isGreaterThanOrEqualTo(1);

        context.close();

        assertThat(lifecycle.isRunning()).isFalse();
        assertEventuallyUnavailable(client, port, "/wire-hello", Duration.ofSeconds(5));
    }

    private static AnnotationConfigApplicationContext createContext(int port) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("testProps", Map.of(
                "exeris.runtime.enabled", "true",
                "exeris.runtime.auto-start", "true",
                "exeris.runtime.web.mode", "pure",
                "exeris.runtime.network.port", String.valueOf(port)
        )));
        context.register(ExerisRuntimeAutoConfiguration.class, ExerisWebAutoConfiguration.class, TestConfig.class);
        context.refresh();
        return context;
    }

    private static HttpResponse<String> awaitSuccessfulGet(HttpClient client,
                                                            int port,
                                                            String path,
                                                            Duration timeout) throws Exception {
        Instant deadline = Instant.now().plus(timeout);
        Throwable lastFailure = null;

        while (Instant.now().isBefore(deadline)) {
            try {
                HttpResponse<String> response = client.send(request(port, path), HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    return response;
                }
                lastFailure = new IllegalStateException("Unexpected status: " + response.statusCode());
            } catch (IOException ex) {
                lastFailure = ex;
            }
            Thread.sleep(100);
        }

        fail("Exeris runtime did not become HTTP-ready on localhost:" + port, lastFailure);
        throw new IllegalStateException("unreachable");
    }

    private static void assertEventuallyUnavailable(HttpClient client,
                                                    int port,
                                                    String path,
                                                    Duration timeout) throws Exception {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            try {
                HttpResponse<String> response = client.send(request(port, path), HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() >= 500) {
                    return;
                }
            } catch (IOException ex) {
                return;
            }
            Thread.sleep(100);
        }
        fail("Exeris runtime remained reachable after shutdown on localhost:" + port);
    }

    private static HttpRequest request(int port, String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .GET()
                .timeout(Duration.ofMillis(750))
                .build();
    }

    private static int reserveEphemeralPort() throws IOException {
        try (ServerSocket serverSocket = new ServerSocket()) {
            serverSocket.bind(new InetSocketAddress("127.0.0.1", 0));
            return serverSocket.getLocalPort();
        }
    }

    @Configuration
    static class TestConfig {

        @Bean
        WireHelloHandler wireHelloHandler() {
            return new WireHelloHandler();
        }
    }

    @ExerisRoute(method = HttpMethod.GET, path = "/wire-hello")
    static class WireHelloHandler implements ExerisRequestHandler {

        private final AtomicInteger invocations = new AtomicInteger();

        @Override
        public ExerisServerResponse handle(ExerisServerRequest request) {
            invocations.incrementAndGet();
            return ExerisServerResponse.ok().body("wire-hello");
        }

        int invocations() {
            return invocations.get();
        }
    }
}