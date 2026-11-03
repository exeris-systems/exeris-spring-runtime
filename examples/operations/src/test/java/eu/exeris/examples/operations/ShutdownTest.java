/*
 * Copyright (C) 2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.examples.operations;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import eu.exeris.spring.boot.autoconfigure.ExerisRuntimeLifecycle;
import eu.exeris.spring.runtime.actuator.ExerisRuntimeHealthIndicator;

import static org.assertj.core.api.Assertions.assertThat;

/** Closing the Spring context stops the kernel, promptly, and frees the port. */
class ShutdownTest {

    @Test
    void closingTheContextStopsTheKernelPromptly() throws Exception {
        int port = Sockets.freePort();
        ConfigurableApplicationContext context = new SpringApplicationBuilder(OperationsApplication.class)
                .run("--exeris.runtime.network.port=" + port);
        try {
            assertStopsPromptly(context, port);
        } finally {
            context.close();
        }
    }

    private static void assertStopsPromptly(ConfigurableApplicationContext context, int port) throws Exception {
        ExerisRuntimeLifecycle lifecycle = context.getBean(ExerisRuntimeLifecycle.class);
        ExerisRuntimeHealthIndicator indicator = context.getBean(ExerisRuntimeHealthIndicator.class);
        Sockets.awaitAccepting(port);

        // An HTTP/1.1 client keeps its connection open and idle in its pool across the stop.
        HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/greeting")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);

        long started = System.nanoTime();
        context.close();
        Duration stop = Duration.ofNanos(System.nanoTime() - started);

        assertThat(stop).isLessThan(Duration.ofSeconds(5));
        assertThat(lifecycle.isRunning()).isFalse();
        assertThat(indicator.health().status()).isEqualTo("DOWN");
        assertThat(Sockets.accepting(port)).isFalse();
    }
}
