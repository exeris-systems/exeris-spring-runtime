/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.web;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;

import eu.exeris.kernel.community.testkit.http.EmbeddedHttpEngineFixture;
import eu.exeris.kernel.community.testkit.http.EmbeddedHttpEngineFixtures;
import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpStatus;
import eu.exeris.kernel.spi.telemetry.KernelEvent;
import eu.exeris.spring.runtime.web.autoconfigure.ExerisWebAutoConfiguration;
import eu.exeris.spring.runtime.web.test.RecordingTelemetryProvider;

class ExerisWireLevelRuntimeIntegrationTest {

    @Test
    void pureMode_bindsPort_routesRequest_and_cleansUpAfterFixtureAndContextClose() throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(250))
                .version(HttpClient.Version.HTTP_1_1)
                .build();

        AnnotationConfigApplicationContext context = createContext();
        EmbeddedHttpEngineFixture fixture = EmbeddedHttpEngineFixtures.kernelBootstrapFixture();
        int port = -1;

        try {
            ExerisHttpDispatcher dispatcher = context.getBean(ExerisHttpDispatcher.class);
            WireHelloHandler handler = context.getBean(WireHelloHandler.class);

            fixture.start(dispatcher);
            port = fixture.boundPort();

            HttpResponse<String> response = awaitSuccessfulGet(client, port, "/wire-hello", Duration.ofSeconds(8));

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(handler.invocations()).isGreaterThanOrEqualTo(1);
            assertThat(fixture.isRunning()).isTrue();
        } finally {
            fixture.close();
            context.close();
        }

        if (port != -1) {
            assertEventuallyUnavailable(client, port, "/wire-hello", Duration.ofSeconds(5));
        }
    }

    private static AnnotationConfigApplicationContext createContext() {
        return createContextWith(TestConfig.class);
    }

    private static AnnotationConfigApplicationContext createContextWith(Class<?>... additionalConfigs) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("testProps", Map.of(
                "exeris.runtime.web.mode", "pure"
        )));
        context.register(ExerisWebAutoConfiguration.class);
        context.register(additionalConfigs);
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

    private static HttpResponse<String> awaitResponseWithStatus(HttpClient client,
                                                                   int port,
                                                                   String path,
                                                                   int expectedStatus,
                                                                   Duration timeout) throws Exception {
        Instant deadline = Instant.now().plus(timeout);
        Throwable lastFailure = null;

        while (Instant.now().isBefore(deadline)) {
            try {
                HttpResponse<String> response = client.send(request(port, path), HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == expectedStatus) {
                    return response;
                }
                lastFailure = new IllegalStateException("Unexpected status: " + response.statusCode());
            } catch (IOException ex) {
                lastFailure = ex;
            }
            Thread.sleep(100);
        }

        fail("Did not receive expected status " + expectedStatus + " from localhost:" + port, lastFailure);
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

    @Test
    void pureMode_bodyResponse_returnsCorrectPayloadAndHeaders() throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(250))
                .version(HttpClient.Version.HTTP_1_1)
                .build();

        AnnotationConfigApplicationContext context = createContextWith(WireBodyTestConfig.class);
        EmbeddedHttpEngineFixture fixture = EmbeddedHttpEngineFixtures.kernelBootstrapFixture();

        try {
            ExerisHttpDispatcher dispatcher = context.getBean(ExerisHttpDispatcher.class);
            fixture.start(dispatcher);
            int port = fixture.boundPort();

            HttpResponse<String> response = awaitSuccessfulGet(client, port, "/wire-body", Duration.ofSeconds(8));

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).isEqualTo("wire-hello");
            assertThat(response.headers().firstValue("Content-Length")).hasValue("10");
            assertThat(response.headers().firstValue("Content-Type")).isPresent()
                    .hasValueSatisfying(ct -> assertThat(ct).contains("text/plain"));
        } finally {
            fixture.close();
            context.close();
        }
    }

    @Test
    void pureMode_missingRoute_returns404_wireLevel() throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(250))
                .version(HttpClient.Version.HTTP_1_1)
                .build();

        AnnotationConfigApplicationContext context = createContextWith(TestConfig.class);
        EmbeddedHttpEngineFixture fixture = EmbeddedHttpEngineFixtures.kernelBootstrapFixture();

        try {
            ExerisHttpDispatcher dispatcher = context.getBean(ExerisHttpDispatcher.class);
            fixture.start(dispatcher);
            int port = fixture.boundPort();

            HttpResponse<String> response = awaitResponseWithStatus(client, port, "/nonexistent", 404, Duration.ofSeconds(8));

            assertThat(response.statusCode()).isEqualTo(404);
        } finally {
            fixture.close();
            context.close();
        }
    }

    @Test
    void pureMode_customStatus_bodyResponse_returns201WithPayload() throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(250))
                .version(HttpClient.Version.HTTP_1_1)
                .build();

        AnnotationConfigApplicationContext context = createContextWith(WireCreatedBodyTestConfig.class);
        EmbeddedHttpEngineFixture fixture = EmbeddedHttpEngineFixtures.kernelBootstrapFixture();

        try {
            ExerisHttpDispatcher dispatcher = context.getBean(ExerisHttpDispatcher.class);
            fixture.start(dispatcher);
            int port = fixture.boundPort();

            HttpResponse<String> response = awaitResponseWithStatus(client, port, "/wire-created", 201, Duration.ofSeconds(8));

            assertThat(response.statusCode()).isEqualTo(201);
            assertThat(response.body()).isEqualTo("wire-created");
            assertThat(response.headers().firstValue("Content-Length")).hasValue("12");
        } finally {
            fixture.close();
            context.close();
        }
    }

    @Test
    void pureMode_shutdownDrainsInFlightRequest_beforeIngressBecomesUnavailable() throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(250))
                .version(HttpClient.Version.HTTP_1_1)
                .build();

        AnnotationConfigApplicationContext context = createContextWith(WireDrainTestConfig.class);
        EmbeddedHttpEngineFixture fixture = EmbeddedHttpEngineFixtures.kernelBootstrapFixture();
        int port = -1;

        CountDownLatch closeStarted = new CountDownLatch(1);
        CompletableFuture<Void> closeFuture = null;
        CompletableFuture<HttpResponse<String>> requestFuture = null;

        try {
            ExerisHttpDispatcher dispatcher = context.getBean(ExerisHttpDispatcher.class);
            WireDrainHandler handler = context.getBean(WireDrainHandler.class);

            fixture.start(dispatcher);
            port = fixture.boundPort();

            requestFuture = client.sendAsync(
                    request(port, "/wire-drain", Duration.ofSeconds(5)),
                    HttpResponse.BodyHandlers.ofString()
            );

            assertThat(handler.awaitEntered(Duration.ofSeconds(5))).isTrue();

            closeFuture = CompletableFuture.runAsync(() -> {
                closeStarted.countDown();
                fixture.close();
            });

            assertThat(closeStarted.await(2, TimeUnit.SECONDS)).isTrue();

            handler.release();

            HttpResponse<String> response = requestFuture.get(5, TimeUnit.SECONDS);
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).isEqualTo("drained");

            // The kernel fixture may spend up to 10s draining in-flight work before close returns.
            closeFuture.get(15, TimeUnit.SECONDS);
        } finally {
            if (closeFuture != null) {
                closeFuture.join();
            } else {
                fixture.close();
            }
            if (port != -1) {
                assertEventuallyUnavailable(client, port, "/wire-drain", Duration.ofSeconds(5));
            }
            context.close();
        }
    }

    @Test
    void pureMode_wireRequest_providesTelemetryScopeEvidence() throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(250))
                .version(HttpClient.Version.HTTP_1_1)
                .build();

        AnnotationConfigApplicationContext context = createContextWith(WireTelemetryEvidenceTestConfig.class);
        EmbeddedHttpEngineFixture fixture = EmbeddedHttpEngineFixtures.kernelBootstrapFixture();

        try {
            ExerisHttpDispatcher dispatcher = context.getBean(ExerisHttpDispatcher.class);
            WireTelemetryEvidenceHandler handler = context.getBean(WireTelemetryEvidenceHandler.class);

            RecordingTelemetryProvider.clearEvents();

            fixture.start(dispatcher);
            int port = fixture.boundPort();

            HttpResponse<String> response = awaitSuccessfulGet(client, port, "/wire-telemetry", Duration.ofSeconds(8));

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).isEqualTo("telemetry-ok");
            assertThat(handler.invocations()).isGreaterThanOrEqualTo(1);
            assertThat(handler.missingScopeFailure()).isNull();
            assertThat(RecordingTelemetryProvider.recordedEvents())
                    .anyMatch(event -> "EX-SPRING-WIRE-001".equals(event.code()));
            assertThat(handler.telemetryProviderBoundObserved()).isNotNull();
            assertThat(handler.telemetrySinksBoundObserved()).isNotNull();
            assertThat(handler.telemetryProbeMode()).isEqualTo("direct-kernel-providers");
        } finally {
            fixture.close();
            context.close();
        }
    }

    @Configuration
    static class TestConfig {

        @Bean
        WireHelloHandler wireHelloHandler() {
            return new WireHelloHandler();
        }
    }

    @Configuration
    static class WireBodyTestConfig {

        @Bean
        WireBodyHandler wireBodyHandler() {
            return new WireBodyHandler();
        }
    }

    @Configuration
    static class WireCreatedBodyTestConfig {

        @Bean
        WireCreatedBodyHandler wireCreatedBodyHandler() {
            return new WireCreatedBodyHandler();
        }
    }

    @Configuration
    static class WireDrainTestConfig {

        @Bean
        WireDrainHandler wireDrainHandler() {
            return new WireDrainHandler();
        }
    }

    @Configuration
    static class WireTelemetryEvidenceTestConfig {

        @Bean
        RecordingTelemetryProvider recordingTelemetryProvider() {
            return new RecordingTelemetryProvider();
        }

        @Bean
        WireTelemetryEvidenceHandler wireTelemetryEvidenceHandler() {
            return new WireTelemetryEvidenceHandler();
        }
    }

    @ExerisRoute(method = HttpMethod.GET, path = "/wire-hello")
    static class WireHelloHandler implements ExerisRequestHandler {

        private final AtomicInteger invocations = new AtomicInteger();

        @Override
        public ExerisServerResponse handle(ExerisServerRequest request) {
            invocations.incrementAndGet();
            return ExerisServerResponse.ok();
        }

        int invocations() {
            return invocations.get();
        }
    }

    @ExerisRoute(method = HttpMethod.GET, path = "/wire-body")
    static class WireBodyHandler implements ExerisRequestHandler {

        @Override
        public ExerisServerResponse handle(ExerisServerRequest request) {
            return ExerisServerResponse.ok().body("wire-hello");
        }
    }

    @ExerisRoute(method = HttpMethod.GET, path = "/wire-created")
    static class WireCreatedBodyHandler implements ExerisRequestHandler {

        @Override
        public ExerisServerResponse handle(ExerisServerRequest request) {
            return ExerisServerResponse.status(HttpStatus.CREATED).body("wire-created");
        }
    }

    @ExerisRoute(method = HttpMethod.GET, path = "/wire-drain")
    static class WireDrainHandler implements ExerisRequestHandler {

        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        @Override
        public ExerisServerResponse handle(ExerisServerRequest request) {
            entered.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Timed out waiting for wire-drain release signal");
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for wire-drain release signal", ex);
            }
            return ExerisServerResponse.ok().body("drained");
        }

        boolean awaitEntered(Duration timeout) throws InterruptedException {
            return entered.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }

        void release() {
            release.countDown();
        }
    }

    @ExerisRoute(method = HttpMethod.GET, path = "/wire-telemetry")
    static class WireTelemetryEvidenceHandler implements ExerisRequestHandler {

        private final AtomicInteger invocations = new AtomicInteger();
        private final AtomicReference<Throwable> missingScopeFailure = new AtomicReference<>();
        private final AtomicReference<Boolean> telemetryProviderBoundObserved = new AtomicReference<>();
        private final AtomicReference<Boolean> telemetrySinksBoundObserved = new AtomicReference<>();
        private final AtomicReference<String> telemetryProbeMode = new AtomicReference<>();

        @Override
        public ExerisServerResponse handle(ExerisServerRequest request) {
            invocations.incrementAndGet();
            try {
                telemetryProviderBoundObserved.set(KernelProviders.TELEMETRY_PROVIDER.isBound());
                telemetrySinksBoundObserved.set(KernelProviders.TELEMETRY_SINKS.isBound());
                telemetryProbeMode.set("direct-kernel-providers");

                if (KernelProviders.TELEMETRY_SINKS.isBound()) {
                    for (var sink : KernelProviders.TELEMETRY_SINKS.get()) {
                        sink.emit(KernelEvent.info("EX-SPRING-WIRE-001", "wire-telemetry-handler"));
                    }
                } else {
                    missingScopeFailure.compareAndSet(
                            null,
                            new IllegalStateException(
                                    "Telemetry sinks are not bound in KernelProviders during wire telemetry evidence request"
                            )
                    );
                }
            } catch (Throwable failure) {
                missingScopeFailure.compareAndSet(null, failure);
            }
            return ExerisServerResponse.ok().body("telemetry-ok");
        }

        int invocations() {
            return invocations.get();
        }

        Throwable missingScopeFailure() {
            return missingScopeFailure.get();
        }

        Boolean telemetryProviderBoundObserved() {
            return telemetryProviderBoundObserved.get();
        }

        Boolean telemetrySinksBoundObserved() {
            return telemetrySinksBoundObserved.get();
        }

        String telemetryProbeMode() {
            return telemetryProbeMode.get();
        }
    }

    private static HttpRequest request(int port, String path, Duration timeout) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .GET()
                .timeout(timeout)
                .build();
    }
}