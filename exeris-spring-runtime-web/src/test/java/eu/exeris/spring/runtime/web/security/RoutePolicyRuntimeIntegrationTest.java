/*
 * Copyright (C) 2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import eu.exeris.kernel.community.testkit.security.TestJwt;
import eu.exeris.kernel.spi.http.HttpHandler;
import eu.exeris.kernel.spi.http.HttpRoutePolicy;
import eu.exeris.spring.boot.autoconfigure.ExerisRuntimeLifecycle;
import eu.exeris.spring.boot.autoconfigure.ExerisRuntimeProperties;
import eu.exeris.spring.boot.autoconfigure.ExerisSpringConfigProvider;
import eu.exeris.spring.runtime.web.autoconfigure.ExerisCompatAutoConfiguration;
import eu.exeris.spring.runtime.web.autoconfigure.ExerisSecurityErrorMappingAutoConfiguration;
import eu.exeris.spring.runtime.web.compat.ExerisCompatDispatcher;
import eu.exeris.spring.runtime.web.test.TestJwtSecurityProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * Runtime integration verification for route security policy enforcement (ADR-063).
 *
 * <p>Boots the runtime via {@link ExerisRuntimeLifecycle} to bind {@code HTTP_ROUTE_POLICY}
 * in the bootstrap thread scope and verify enforcement by the kernel admission engine over
 * a live socket.
 *
 * <p>Validates anonymous access for {@code permitAll} routes, 401 for a route that requires an
 * identity when none is presented, 403 and 200 for a scope rule decided on a verified token's
 * scopes, and that an edge admission does not bypass {@code @PreAuthorize} on the Compatibility Mode
 * handler it admits to.
 *
 * <p>Tests that present a token boot the kernel {@code security} subsystem, which selects
 * {@link TestJwtSecurityProvider}: the Community verification pipeline over the testkit signing key.
 *
 * @see "ADR-063: Route Security Policy"
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
     * A scope rule is decided on the scopes of a token the kernel itself verified: a valid token
     * without the scope is forbidden, one carrying it is admitted, and no token at all is
     * unauthenticated rather than forbidden.
     */
    @Test
    void kernelDecidesAScopeRuleOnTheVerifiedTokensScopes() throws Exception {
        int port = freePort();

        HttpRoutePolicy policy = ExerisRoutePolicyCompiler.compile(ExerisHttpSecurity.create()
                .requestMatchers("/health", "/health/live", "/health/ready", "/db/ping", "/db/roundtrip")
                .permitAll()
                .requestMatchers("/orders").hasAnyScope("orders:read")
                .anyRequest().permitAll());

        AtomicInteger handled = new AtomicInteger();
        ExerisRuntimeLifecycle lifecycle = new ExerisRuntimeLifecycle(
                properties(ExerisRuntimeProperties.Mode.PURE, SECURED_SUBSYSTEMS),
                new ExerisSpringConfigProvider(environmentWithPort(port)),
                Optional.of(exchange -> {
                    handled.incrementAndGet();
                    exchange.respond(eu.exeris.kernel.spi.http.HttpResponse.noBody(
                            eu.exeris.kernel.spi.http.HttpStatus.OK, exchange.request().version()));
                }),
                Optional.of(policy));

        HttpClient client = client();

        try {
            lifecycle.start();
            awaitAccepting(port);

            assertThat(statusOf(client, port, "/orders"))
                    .as("a scope rule must answer a request with no token as unauthenticated, not forbidden")
                    .isEqualTo(401);

            assertThat(statusOf(client, port, "/orders", tokenWithScope("other")))
                    .as("a verified token lacking the required scope must be forbidden by the kernel")
                    .isEqualTo(403);

            assertThat(handled)
                    .as("a request the edge refuses must never reach the handler")
                    .hasValue(0);

            assertThat(statusOf(client, port, "/orders", tokenWithScope("orders:read")))
                    .as("a verified token carrying the required scope must be admitted")
                    .isEqualTo(200);

            assertThat(handled)
                    .as("the admitted request must reach the handler exactly once")
                    .hasValue(1);
        } finally {
            lifecycle.stop();
        }
    }

    /**
     * Edge admission is not authorization of the handler: a request the kernel admits on
     * {@code authenticated()} still meets {@code @PreAuthorize} on the Compatibility Mode controller
     * method, which refuses a token lacking the authority with 403 and serves one carrying it.
     */
    @Test
    void preAuthorizeIsStillEnforcedInsideTheHandlerForAnAdmittedRequest() throws Exception {
        int port = freePort();

        HttpRoutePolicy policy = ExerisRoutePolicyCompiler.compile(ExerisHttpSecurity.create()
                .requestMatchers("/health", "/health/live", "/health/ready", "/db/ping", "/db/roundtrip")
                .permitAll()
                .requestMatchers("/orders/admin").authenticated()
                .anyRequest().permitAll());

        AnnotationConfigApplicationContext context = compatContextWithMethodSecurity();
        ExerisCompatDispatcher compatDispatcher = context.getBean(ExerisCompatDispatcher.class);
        AdminOrdersController controller = context.getBean(AdminOrdersController.class);

        AtomicInteger dispatched = new AtomicInteger();
        HttpHandler countingDispatcher = exchange -> {
            dispatched.incrementAndGet();
            compatDispatcher.handle(exchange);
        };

        ExerisRuntimeLifecycle lifecycle = new ExerisRuntimeLifecycle(
                properties(ExerisRuntimeProperties.Mode.COMPATIBILITY, SECURED_SUBSYSTEMS),
                new ExerisSpringConfigProvider(environmentWithPort(port)),
                Optional.of(countingDispatcher),
                Optional.of(policy));

        HttpClient client = client();

        try {
            lifecycle.start();
            awaitAccepting(port);

            assertThat(statusOf(client, port, "/orders/admin", tokenWithScope("orders:read")))
                    .as("@PreAuthorize must refuse an admitted request whose token lacks the authority")
                    .isEqualTo(403);

            assertThat(dispatched)
                    .as("the 403 must come from inside the handler: the edge admitted the request "
                            + "to the compat dispatcher")
                    .hasValue(1);
            assertThat(controller.invocations())
                    .as("the guarded method body must not run when @PreAuthorize refuses")
                    .hasValue(0);

            assertThat(statusOf(client, port, "/orders/admin", tokenWithScope("orders:read orders:admin")))
                    .as("@PreAuthorize must admit a token carrying the authority")
                    .isEqualTo(200);

            assertThat(controller.invocations())
                    .as("the guarded method body must run once for the authorized request")
                    .hasValue(1);
        } finally {
            try {
                lifecycle.stop();
            } finally {
                context.close();
            }
        }
    }

    /**
     * Only the subsystems ingress needs. Persistence is excluded deliberately: it fails to boot with
     * no database reachable, and a route-policy test that needed one would be testing the wrong thing.
     */
    private static final List<String> INGRESS_SUBSYSTEMS = List.of("memory", "transport", "http");

    /** Ingress plus the security subsystem, which binds the provider that verifies bearer tokens. */
    private static final List<String> SECURED_SUBSYSTEMS = List.of("memory", "security", "transport", "http");

    private static ExerisRuntimeProperties properties() {
        return properties(ExerisRuntimeProperties.Mode.PURE, INGRESS_SUBSYSTEMS);
    }

    private static ExerisRuntimeProperties properties(ExerisRuntimeProperties.Mode mode, List<String> subsystems) {
        return new ExerisRuntimeProperties(
                true,
                false,
                new ExerisRuntimeProperties.WebProperties(mode),
                new ExerisRuntimeProperties.LifecycleProperties(30),
                new ExerisRuntimeProperties.ShutdownProperties(true, 30),
                subsystems);
    }

    /**
     * A token the kernel's {@link TestJwtSecurityProvider} verifies, carrying a literal
     * space-delimited {@code scope} claim. The Spring side reads the same claim: its default
     * converter grants {@code SCOPE_<scope>} for each entry.
     */
    private static String tokenWithScope(String scope) {
        return TestJwt.builder().claim("scope", scope).serialize();
    }

    /**
     * A Compatibility Mode context with method security on and a {@link JwtDecoder} trusting the
     * same testkit key the kernel trusts, so the compat security filter populates the Spring
     * {@code SecurityContext} from the token the edge already verified.
     */
    private static AnnotationConfigApplicationContext compatContextWithMethodSecurity() {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(
                new MapPropertySource("compatProps", Map.of("exeris.runtime.web.mode", "compatibility")));
        // User configuration first, as Boot orders it ahead of auto-configuration: the compat security
        // filter is conditional on a JwtDecoder bean, which must already be defined when that
        // condition is evaluated.
        context.register(MethodSecurityCompatConfig.class);
        context.register(ExerisSecurityErrorMappingAutoConfiguration.class);
        context.register(ExerisCompatAutoConfiguration.class);
        context.refresh();
        return context;
    }

    private static HttpClient client() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(500))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    private static StandardEnvironment environmentWithPort(int port) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource(
                "routePolicyTest", Map.of("exeris.runtime.network.port", port)));
        return environment;
    }

    private static int statusOf(HttpClient client, int port, String path) throws Exception {
        return statusOf(client, port, path, null);
    }

    private static int statusOf(HttpClient client, int port, String path, String bearerToken) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(5))
                .GET();
        if (bearerToken != null) {
            request.header("Authorization", "Bearer " + bearerToken);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString()).statusCode();
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

    @Configuration
    @EnableMethodSecurity
    static class MethodSecurityCompatConfig {

        @Bean
        JwtDecoder jwtDecoder() {
            return NimbusJwtDecoder.withPublicKey(TestJwt.testPublicKey()).build();
        }

        @Bean
        AdminOrdersController adminOrdersController() {
            return new AdminOrdersController();
        }
    }

    @RestController
    public static class AdminOrdersController {

        private final AtomicInteger invocations = new AtomicInteger();

        @GetMapping("/orders/admin")
        @PreAuthorize("hasAuthority('SCOPE_orders:admin')")
        public String adminOrders() {
            invocations.incrementAndGet();
            return "admin-orders";
        }

        public AtomicInteger invocations() {
            return invocations;
        }
    }
}
