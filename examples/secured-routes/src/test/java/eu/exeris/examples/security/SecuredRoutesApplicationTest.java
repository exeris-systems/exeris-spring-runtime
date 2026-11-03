/*
 * Copyright (C) 2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.examples.security;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.sun.net.httpserver.HttpServer;

import eu.exeris.kernel.community.testkit.security.TestJwt;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boots the example and calls it over a real socket with real tokens. The kernel verifies each token
 * against a loopback JWKS endpoint that publishes the testkit signing key.
 */
@SpringBootTest
class SecuredRoutesApplicationTest {

    private static final int PORT = freePort();

    // tag::jwks-server[]
    private static final HttpServer JWKS = startJwksServer();

    private static HttpServer startJwksServer() {
        byte[] jwks = new JWKSet(new RSAKey.Builder(TestJwt.testPublicKey()).keyID(TestJwt.TEST_KID).build())
                .toString()
                .getBytes(StandardCharsets.UTF_8);
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/jwks", exchange -> {
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, jwks.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(jwks);
                }
            });
            server.start();
            return server;
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    @DynamicPropertySource
    static void edgeKeys(DynamicPropertyRegistry registry) {
        registry.add("exeris.runtime.network.port", () -> PORT);
        registry.add("security.jwks.uri", () -> "http://127.0.0.1:" + JWKS.getAddress().getPort() + "/jwks");
        registry.add("security.jwt.issuer", () -> TestJwt.EXPECTED_ISSUER);
        registry.add("security.jwt.audience", () -> TestJwt.EXPECTED_AUDIENCE);
    }
    // end::jwks-server[]

    private final HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    @AfterAll
    static void stopJwksServer() {
        JWKS.stop(0);
    }

    @BeforeEach
    void awaitListener() throws InterruptedException {
        awaitAccepting(PORT);
    }

    // tag::assertions[]
    @Test
    void aPublicRouteNeedsNoToken() throws Exception {
        assertThat(get("/catalog", null).statusCode()).isEqualTo(200);
    }

    @Test
    void aScopedRouteRefusesARequestWithNoToken() throws Exception {
        assertThat(get("/orders", null).statusCode()).isEqualTo(401);
    }

    @Test
    void aScopedRouteRefusesATokenWithoutTheScope() throws Exception {
        assertThat(get("/orders", tokenWithScope("profile")).statusCode()).isEqualTo(403);
    }

    @Test
    void aScopedRouteAdmitsATokenWithTheScope() throws Exception {
        HttpResponse<String> response = get("/orders", tokenWithScope("orders:read"));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("orders");
    }

    @Test
    void anUndeclaredRouteNeedsAnyVerifiedToken() throws Exception {
        assertThat(get("/account", null).statusCode()).isEqualTo(401);
        assertThat(get("/account", tokenWithScope("profile")).statusCode()).isEqualTo(200);
    }

    @Test
    void aTokenFromAnotherIssuerIsRefused() throws Exception {
        String foreign = TestJwt.builder().issuer("https://other.example.com").claim("scope", "orders:read").serialize();

        assertThat(get("/orders", foreign).statusCode()).isEqualTo(401);
    }

    private static String tokenWithScope(String scope) {
        return TestJwt.builder().claim("scope", scope).serialize();
    }
    // end::assertions[]

    @Test
    void theEdgeAdmitsAProbeRouteAnonymously() throws Exception {
        // Not 401: the edge admits it. Not 200: this application declares no handler for it.
        assertThat(get("/health", null).statusCode()).isEqualTo(404);
    }

    private HttpResponse<String> get(String path, String bearerToken) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + PORT + path))
                .timeout(Duration.ofSeconds(5))
                .GET();
        if (bearerToken != null) {
            request.header("Authorization", "Bearer " + bearerToken);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
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
