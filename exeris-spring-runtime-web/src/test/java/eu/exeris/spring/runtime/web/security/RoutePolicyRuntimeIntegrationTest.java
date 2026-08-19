/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.web.security;

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
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import eu.exeris.kernel.spi.http.HttpRoutePolicy;
import eu.exeris.spring.boot.autoconfigure.ExerisRuntimeLifecycle;
import eu.exeris.spring.boot.autoconfigure.ExerisRuntimeProperties;
import eu.exeris.spring.boot.autoconfigure.ExerisSpringConfigProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * Runtime-integration evidence for ADR-063: an {@link ExerisHttpSecurity} declaration, compiled and
 * bound into the kernel's {@code HTTP_ROUTE_POLICY} slot, is actually enforced by the kernel on the
 * admission path — against a real kernel over a real socket.
 *
 * <h2>Why this boots through {@code ExerisRuntimeLifecycle} rather than the testkit fixture</h2>
 *
 * <p>Not a stylistic preference — the fixture cannot carry this. {@code EmbeddedHttpEngineFixture}
 * exposes only {@code start(HttpHandler)} and boots the kernel on its own thread, and a
 * {@code ScopedValue} binding does not cross a thread boundary. Binding {@code HTTP_ROUTE_POLICY}
 * around {@code fixture.start(...)} was measured to have no effect: a route declared
 * {@code authenticated()} was served <strong>200 without a token</strong>, because the kernel never
 * saw the policy.
 *
 * <p>{@code ExerisRuntimeLifecycle} binds the slot on the same thread it calls
 * {@code KernelBootstrap.boot} on, which is both what production does and the only path on which this
 * is observable. So this test exercises the real seam rather than a fixture-shaped approximation.
 *
 * <h2>What is covered here, and what is not</h2>
 *
 * <p>Covered without an identity provider: {@code permitAll} is reachable anonymously, and a route
 * declared {@code authenticated()} is refused with <strong>401</strong> — the kernel's enforcer maps
 * "requirement present, no principal" to {@code UNAUTHENTICATED} regardless of whether any identity
 * provider is bound.
 *
 * <p>Not covered here: the 403 case (a real principal whose scopes are insufficient) needs a bound
 * {@code SecurityProvider} issuing a {@code PrincipalContext}, which is an OIDC/JWKS fixture rather
 * than a route-policy concern. It is stated rather than quietly omitted.
 */
class RoutePolicyRuntimeIntegrationTest {

    @Test
    void kernelEnforcesTheCompiledPolicyOnTheAdmissionPath() throws Exception {
        int port = freePort();

        HttpRoutePolicy policy = ExerisRoutePolicyCompiler.compile(ExerisHttpSecurity.create()
                .requestMatchers("/health", "/health/live", "/health/ready", "/db/ping", "/db/roundtrip")
                .permitAll()
                .requestMatchers("/open").permitAll()
                .requestMatchers("/guarded").authenticated()
                .anyRequest().permitAll());

        ExerisRuntimeLifecycle lifecycle = new ExerisRuntimeLifecycle(
                properties(),
                new ExerisSpringConfigProvider(environmentWithPort(port)),
                Optional.of(exchange -> exchange.respond(eu.exeris.kernel.spi.http.HttpResponse.noBody(
                        eu.exeris.kernel.spi.http.HttpStatus.OK, exchange.request().version()))),
                Optional.of(policy));

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(500))
                .version(HttpClient.Version.HTTP_1_1)
                .build();

        try {
            lifecycle.start();
            awaitAccepting(port);

            assertThat(statusOf(client, port, "/open"))
                    .as("permitAll route must be reachable anonymously")
                    .isEqualTo(200);

            assertThat(statusOf(client, port, "/guarded"))
                    .as("authenticated() route must be refused without an identity — and refused by "
                            + "the kernel on the admission path, before the dispatcher runs")
                    .isEqualTo(401);

            assertThat(statusOf(client, port, "/undeclared"))
                    .as("the declared unmatched answer (permitAll here) applies to undeclared paths")
                    .isEqualTo(200);

            assertThat(statusOf(client, port, "/guarded?retry=1"))
                    .as("a query string must not change which rule applies — appending one must not "
                            + "turn a guarded route into an open one")
                    .isEqualTo(401);
        } finally {
            lifecycle.stop();
        }
    }

    /**
     * Obligation 6, end to end: with no policy bound the kernel applies no per-route requirement, so
     * the same declaration-free deployment behaves as it did before ADR-061 existed.
     */
    @Test
    void withNoPolicyBoundEveryRouteIsServed() throws Exception {
        int port = freePort();

        ExerisRuntimeLifecycle lifecycle = new ExerisRuntimeLifecycle(
                properties(),
                new ExerisSpringConfigProvider(environmentWithPort(port)),
                Optional.of(exchange -> exchange.respond(eu.exeris.kernel.spi.http.HttpResponse.noBody(
                        eu.exeris.kernel.spi.http.HttpStatus.OK, exchange.request().version()))),
                Optional.empty());

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(500))
                .version(HttpClient.Version.HTTP_1_1)
                .build();

        try {
            lifecycle.start();
            awaitAccepting(port);

            assertThat(statusOf(client, port, "/guarded"))
                    .as("no policy bound means no requirement — declaring nothing changes nothing")
                    .isEqualTo(200);
        } finally {
            lifecycle.stop();
        }
    }

    /**
     * Only the subsystems ingress needs. Persistence is excluded deliberately: it fails to boot with
     * no database reachable, and a route-policy test that needed one would be testing the wrong thing.
     */
    private static ExerisRuntimeProperties properties() {
        return new ExerisRuntimeProperties(
                true,
                false,
                new ExerisRuntimeProperties.WebProperties(ExerisRuntimeProperties.Mode.PURE),
                new ExerisRuntimeProperties.LifecycleProperties(30),
                new ExerisRuntimeProperties.ShutdownProperties(true, 30),
                java.util.List.of("memory", "transport", "http"));
    }

    private static StandardEnvironment environmentWithPort(int port) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource(
                "routePolicyTest", Map.of("exeris.runtime.network.port", port)));
        return environment;
    }

    private static int statusOf(HttpClient client, int port, String path) throws Exception {
        return client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                        .timeout(Duration.ofSeconds(5))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    /**
     * A port nobody is listening on yet. Racy in principle; in practice the window is the few
     * milliseconds between closing this socket and the kernel binding, and a fixed port is the only
     * way to address a listener whose bound port the lifecycle does not expose.
     */
    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static void awaitAccepting(int port) throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(15));
        while (Instant.now().isBefore(deadline)) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", port), 200);
                return;
            } catch (IOException ignored) {
                Thread.sleep(100);
            }
        }
        fail("Exeris ingress never began accepting on 127.0.0.1:" + port);
    }
}
