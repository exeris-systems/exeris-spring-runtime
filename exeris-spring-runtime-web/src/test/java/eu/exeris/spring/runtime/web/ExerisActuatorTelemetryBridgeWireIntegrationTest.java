/*
 * Copyright (C) 2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
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
import java.util.concurrent.atomic.AtomicBoolean;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;

import eu.exeris.kernel.community.testkit.http.EmbeddedHttpEngineFixture;
import eu.exeris.kernel.community.testkit.http.EmbeddedHttpEngineFixtures;
import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.telemetry.KernelEvent;
import eu.exeris.kernel.spi.telemetry.TelemetrySink;
import eu.exeris.spring.boot.autoconfigure.ExerisRuntimeAutoConfiguration;
import eu.exeris.spring.runtime.actuator.ExerisActuatorAutoConfiguration;
import eu.exeris.spring.runtime.actuator.ExerisActuatorTelemetryBridge;
import eu.exeris.spring.runtime.web.autoconfigure.ExerisWebAutoConfiguration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * Proves that an event emitted through {@code KernelProviders.TELEMETRY_SINKS} during a Pure Mode
 * request reaches the actuator module's Micrometer bridge, with the request served by a real kernel
 * HTTP engine over a socket and no test-side binding of the telemetry slot.
 */
class ExerisActuatorTelemetryBridgeWireIntegrationTest {

    private static final String EVENT_CODE = "EX-SPRING-ACTUATOR-WIRE-001";

    @Test
    void eventEmittedThroughTelemetrySinksDuringRequest_movesTheMicrometerGauge() throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(250))
                .version(HttpClient.Version.HTTP_1_1)
                .build();

        AnnotationConfigApplicationContext context = createContext();
        EmbeddedHttpEngineFixture fixture = EmbeddedHttpEngineFixtures.kernelBootstrapFixture();

        try {
            MeterRegistry registry = context.getBean(MeterRegistry.class);
            // Spring Boot's metrics auto-configuration binds every MeterBinder bean to the registry;
            // this context does not run it, so the binding is done here.
            context.getBean(ExerisActuatorTelemetryBridge.class).bindTo(registry);
            EmittingHandler handler = context.getBean(EmittingHandler.class);

            fixture.start(context.getBean(ExerisHttpDispatcher.class));
            HttpResponse<String> response =
                    awaitSuccessfulGet(client, fixture.boundPort(), "/actuator-telemetry", Duration.ofSeconds(8));

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(handler.sinksBoundObserved())
                    .as("TELEMETRY_SINKS must be bound while a Pure Mode handler runs")
                    .isTrue();
            Gauge info = registry.find("exeris.events.info").gauge();
            assertThat(info).isNotNull();
            assertThat(info.value())
                    .as("the INFO event emitted during the request must reach exeris.events.info")
                    .isEqualTo(1.0);
        } finally {
            try {
                fixture.close();
            } finally {
                context.close();
            }
        }
    }

    private static AnnotationConfigApplicationContext createContext() {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("testProps", Map.of(
                "exeris.runtime.auto-start", "false",
                "exeris.runtime.web.mode", "pure"
        )));
        context.register(ExerisRuntimeAutoConfiguration.class,
                ExerisWebAutoConfiguration.class,
                ExerisActuatorAutoConfiguration.class,
                TestConfig.class);
        context.refresh();
        return context;
    }

    private static HttpResponse<String> awaitSuccessfulGet(HttpClient client, int port, String path,
                                                           Duration timeout) throws Exception {
        Instant deadline = Instant.now().plus(timeout);
        Throwable lastFailure = null;
        while (Instant.now().isBefore(deadline)) {
            try {
                HttpResponse<String> response = client.send(
                        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                                .GET()
                                .timeout(Duration.ofMillis(750))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
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

    @Configuration
    static class TestConfig {

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }

        @Bean
        EmittingHandler emittingHandler() {
            return new EmittingHandler();
        }
    }

    /** Emits one INFO event through the kernel telemetry slot, as the slot's contract describes. */
    @ExerisRoute(method = HttpMethod.GET, path = "/actuator-telemetry")
    static class EmittingHandler implements ExerisRequestHandler {

        private final AtomicBoolean sinksBound = new AtomicBoolean();

        @Override
        public ExerisServerResponse handle(ExerisServerRequest request) {
            if (KernelProviders.TELEMETRY_SINKS.isBound()) {
                sinksBound.set(true);
                KernelEvent event = KernelEvent.info(EVENT_CODE, "actuator telemetry wire probe");
                for (TelemetrySink sink : KernelProviders.TELEMETRY_SINKS.get()) {
                    sink.emit(event);
                }
            }
            return ExerisServerResponse.ok();
        }

        boolean sinksBoundObserved() {
            return sinksBound.get();
        }
    }
}
