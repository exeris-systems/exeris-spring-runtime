/*
 * Copyright (C) 2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.examples.operations;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boots the example and reads its operational surface over a real socket. The context is closed
 * after the class, because one Exeris runtime per JVM is supported and other test classes start
 * their own.
 */
@SpringBootTest
@DirtiesContext
@ExtendWith(OutputCaptureExtension.class)
class OperationsApplicationTest {

    private static final int PORT = Sockets.freePort();

    private final HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    @Autowired
    private ApplicationContext context;

    @Autowired
    private HealthEndpoint healthEndpoint;

    @Autowired
    private MeterRegistry meterRegistry;

    @DynamicPropertySource
    static void port(DynamicPropertyRegistry registry) {
        registry.add("exeris.runtime.network.port", () -> PORT);
    }

    @BeforeEach
    void awaitListener() throws InterruptedException {
        Sockets.awaitAccepting(PORT);
    }

    @Test
    void aggregateHealthIsUpOnTheKernelListener() throws Exception {
        HttpResponse<String> response = get("/actuator/health");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type")).hasValue("application/json");
        assertThat(response.body()).isEqualTo("{\"status\":\"UP\"}");
    }

    @Test
    void probeGroupsAreUp() throws Exception {
        assertThat(get("/actuator/health/liveness").statusCode()).isEqualTo(200);
        assertThat(get("/actuator/health/readiness").statusCode()).isEqualTo(200);
    }

    @Test
    void readinessIncludesTheExerisRuntimeIndicator() {
        assertThat(healthEndpoint.healthForPath("readiness", "exerisRuntimeSpringBoot"))
                .isNotNull()
                .satisfies(component -> assertThat(component.getStatus()).isEqualTo(Status.UP));
    }

    @Test
    void theKernelsOwnHealthRoutesAreNotServed() throws Exception {
        // The runtime binds its dispatcher as the kernel's HTTP handler, so the kernel's
        // built-in /health routes are not installed.
        assertThat(get("/health").statusCode()).isEqualTo(404);
        assertThat(get("/health/ready").statusCode()).isEqualTo(404);
    }

    @Test
    void neo4jIsNotAutoConfigured() {
        assertThat(context.getBeanNamesForType(org.neo4j.driver.Driver.class)).isEmpty();
        assertThat(healthEndpoint.healthForPath("neo4j")).isNull();
    }

    @Test
    void applicationMetricsAndTheExerisBinderAreInTheRegistry() throws Exception {
        double before = meterRegistry.get("greetings.served").counter().count();
        assertThat(get("/greeting").statusCode()).isEqualTo(200);

        assertThat(meterRegistry.get("greetings.served").counter().count()).isEqualTo(before + 1);
        assertThat(meterRegistry.find("exeris.events.info").gauge()).isNotNull();
        assertThat(meterRegistry.find("exeris.events.error").gauge()).isNotNull();
    }

    @Test
    void anEventEmittedByAPureModeHandlerMovesTheExerisEventGauge() throws Exception {
        double warnBefore = gauge("exeris.events.warn");
        double infoBefore = gauge("exeris.events.info");

        assertThat(get("/quote").statusCode()).isEqualTo(200);

        assertThat(gauge("exeris.events.warn")).isEqualTo(warnBefore + 1);
        assertThat(gauge("exeris.events.info")).isEqualTo(infoBefore);
    }

    @Test
    void aRequestWhoseHandlerEmitsNothingLeavesTheExerisEventGaugesAlone() throws Exception {
        // The kernel accepts, parses and answers the request, and records its own events in JFR;
        // none of them reaches the bridge.
        double[] before = eventGauges();

        assertThat(get("/greeting").statusCode()).isEqualTo(200);

        assertThat(eventGauges()).containsExactly(before);
    }

    @Test
    void kernelLogsReachSpringBootLogging(CapturedOutput output) {
        assertThat(output.getAll()).contains("SubsystemOrchestrator").contains("STARTED in");
    }

    private double gauge(String name) {
        return meterRegistry.get(name).gauge().value();
    }

    private double[] eventGauges() {
        return new double[] {
                gauge("exeris.events.info"), gauge("exeris.events.warn"),
                gauge("exeris.events.error"), gauge("exeris.events.fatal")};
    }

    private HttpResponse<String> get(String path) throws Exception {
        return client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + PORT + path)).build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
