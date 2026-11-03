/*
 * Copyright (C) 2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.examples.compat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/** Boots the example as a consumer would and calls every endpoint over a real socket. */
@SpringBootTest
class CompatMvcApplicationTest {

    private static final int PORT = freePort();

    private final HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    @DynamicPropertySource
    static void port(DynamicPropertyRegistry registry) {
        registry.add("exeris.runtime.network.port", () -> PORT);
    }

    @BeforeEach
    void awaitListener() throws InterruptedException {
        awaitAccepting(PORT);
    }

    @Test
    void postCreatesAnOrderAndGetReadsItBack() throws Exception {
        HttpResponse<String> created = post("{\"product\":\"book\",\"quantity\":2}");

        assertThat(created.statusCode()).isEqualTo(201);
        String location = created.headers().firstValue("Location").orElseThrow();
        assertThat(location).matches("/orders/\\d+");
        assertThat(created.body()).contains("\"product\":\"book\"").contains("\"quantity\":2");

        HttpResponse<String> read = get(location);

        assertThat(read.statusCode()).isEqualTo(200);
        assertThat(read.body()).isEqualTo(created.body());
    }

    @Test
    void anInvalidBodyIsABadRequestNamingTheFields() throws Exception {
        HttpResponse<String> response = post("{\"product\":\"\",\"quantity\":0}");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).isEqualTo("{\"invalid\":[\"product\",\"quantity\"]}");
    }

    @Test
    void anUnknownOrderIsMappedByTheAdvice() throws Exception {
        HttpResponse<String> response = get("/orders/999999");

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).isEqualTo("{\"error\":\"order 999999 does not exist\"}");
    }

    @Test
    void aPathNoControllerMapsIsNotFound() throws Exception {
        assertThat(get("/nope").statusCode()).isEqualTo(404);
    }

    private HttpResponse<String> get(String path) throws Exception {
        return client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + PORT + path)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String json) throws Exception {
        return client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + PORT + "/orders"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static void awaitAccepting(int port) throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(15));
        while (Instant.now().isBefore(deadline)) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", port), 200);
                return;
            } catch (IOException ignored) {
                Thread.sleep(100);
            }
        }
        throw new AssertionError("nothing accepted on 127.0.0.1:" + port);
    }
}
