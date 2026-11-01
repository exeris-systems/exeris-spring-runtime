/*
 * Copyright (C) 2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.spring.runtime.web;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.spring.boot.autoconfigure.ExerisRuntimeAutoConfiguration;
import eu.exeris.spring.boot.autoconfigure.ExerisRuntimeLifecycle;
import eu.exeris.spring.runtime.actuator.ExerisActuatorAutoConfiguration;
import eu.exeris.spring.runtime.actuator.ExerisRuntimeHealthIndicator;
import eu.exeris.spring.runtime.web.autoconfigure.ExerisCompatAutoConfiguration;
import eu.exeris.spring.runtime.web.autoconfigure.ExerisWebAutoConfiguration;

/**
 * A handler is routed whatever its dependency graph, including when it reaches back into the
 * runtime's own chain.
 *
 * <p>The runtime lifecycle holds the HTTP dispatcher, and the dispatcher holds the handler table. A
 * handler that depends on {@link ExerisRuntimeHealthIndicator} — which depends on
 * {@link ExerisRuntimeLifecycle} — therefore sits inside the chain that builds its own table. Both
 * tests boot the real kernel through the auto-configurations, in Spring Boot's order (application
 * configuration first), and ask over a live socket.
 */
class RouteRegistryCompletenessIntegrationTest {

    @Test
    void pureMode_routesAHandlerThatDependsOnTheRuntimeLifecycleChain() throws Exception {
        int port = freePort();
        new ApplicationContextRunner()
                .withUserConfiguration(PureHandlerConfig.class)
                .withConfiguration(AutoConfigurations.of(
                        ExerisRuntimeAutoConfiguration.class,
                        ExerisActuatorAutoConfiguration.class,
                        ExerisWebAutoConfiguration.class))
                .withPropertyValues(runtimeProperties("pure", port))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(ExerisRouteRegistry.class)
                            .resolve(HttpMethod.GET, "/lifecycle-aware"))
                            .as("the handler must be in the route table")
                            .isSameAs(context.getBean(LifecycleAwareHandler.class));

                    awaitAccepting(port);
                    assertThat(statusOf(port, "/lifecycle-aware"))
                            .as("a handler depending on the runtime lifecycle chain must be routed, not 404")
                            .isEqualTo(200);
                });
    }

    @Test
    void compatibilityMode_routesAControllerThatDependsOnTheRuntimeLifecycleChain() throws Exception {
        int port = freePort();
        new ApplicationContextRunner()
                .withUserConfiguration(CompatControllerConfig.class)
                .withConfiguration(AutoConfigurations.of(
                        ExerisRuntimeAutoConfiguration.class,
                        ExerisActuatorAutoConfiguration.class,
                        ExerisWebAutoConfiguration.class,
                        ExerisCompatAutoConfiguration.class))
                .withPropertyValues(runtimeProperties("compatibility", port))
                .run(context -> {
                    assertThat(context).hasNotFailed();

                    awaitAccepting(port);
                    assertThat(statusOf(port, "/lifecycle-aware"))
                            .as("a controller depending on the runtime lifecycle chain must be routed")
                            .isEqualTo(200);
                    assertThat(statusOf(port, "/lifecycle-aware/fail"))
                            .as("an advice depending on the runtime lifecycle chain must handle the failure")
                            .isEqualTo(409);
                    assertThat(statusOf(port, "/actuator/health"))
                            .as("the runtime's own compat actuator controller sits in the same chain")
                            .isEqualTo(200);
                });
    }

    private static String[] runtimeProperties(String webMode, int port) {
        return new String[] {
                "exeris.runtime.web.mode=" + webMode,
                "exeris.runtime.network.port=" + port,
                "exeris.runtime.subsystems[0]=memory",
                "exeris.runtime.subsystems[1]=transport",
                "exeris.runtime.subsystems[2]=http"
        };
    }

    private static int statusOf(int port, String path) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(500))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode();
    }

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

    @Configuration(proxyBeanMethods = false)
    static class PureHandlerConfig {

        @Bean
        LifecycleAwareHandler lifecycleAwareHandler(ExerisRuntimeHealthIndicator healthIndicator) {
            return new LifecycleAwareHandler(healthIndicator);
        }
    }

    @ExerisRoute(method = HttpMethod.GET, path = "/lifecycle-aware")
    static final class LifecycleAwareHandler implements ExerisRequestHandler {

        private final ExerisRuntimeHealthIndicator healthIndicator;

        LifecycleAwareHandler(ExerisRuntimeHealthIndicator healthIndicator) {
            this.healthIndicator = healthIndicator;
        }

        @Override
        public ExerisServerResponse handle(ExerisServerRequest request) {
            return ExerisServerResponse.ok().body(String.valueOf(healthIndicator != null));
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class CompatControllerConfig {

        @Bean
        LifecycleAwareController lifecycleAwareController(ExerisRuntimeHealthIndicator healthIndicator) {
            return new LifecycleAwareController(healthIndicator);
        }

        @Bean
        LifecycleAwareAdvice lifecycleAwareAdvice(ExerisRuntimeHealthIndicator healthIndicator) {
            return new LifecycleAwareAdvice(healthIndicator);
        }
    }

    @RestController
    static final class LifecycleAwareController {

        private final ExerisRuntimeHealthIndicator healthIndicator;

        LifecycleAwareController(ExerisRuntimeHealthIndicator healthIndicator) {
            this.healthIndicator = healthIndicator;
        }

        @GetMapping("/lifecycle-aware")
        String lifecycleAware() {
            return String.valueOf(healthIndicator != null);
        }

        @GetMapping("/lifecycle-aware/fail")
        String fail() {
            throw new IllegalArgumentException("refused");
        }
    }

    @RestControllerAdvice
    static final class LifecycleAwareAdvice {

        private final ExerisRuntimeHealthIndicator healthIndicator;

        LifecycleAwareAdvice(ExerisRuntimeHealthIndicator healthIndicator) {
            this.healthIndicator = healthIndicator;
        }

        @ExceptionHandler(IllegalArgumentException.class)
        @ResponseStatus(HttpStatus.CONFLICT)
        String refused(IllegalArgumentException ex) {
            return ex.getMessage() + ":" + (healthIndicator != null);
        }
    }
}
