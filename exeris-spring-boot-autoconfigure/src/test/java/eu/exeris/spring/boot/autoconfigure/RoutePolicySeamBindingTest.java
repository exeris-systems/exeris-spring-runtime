/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.boot.autoconfigure;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import eu.exeris.kernel.spi.http.HttpKernelProviders;
import eu.exeris.kernel.spi.http.HttpRoutePolicy;
import eu.exeris.kernel.spi.http.RouteRequirement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The lifecycle binds two optional kernel seams — the HTTP handler and the ADR-063 route policy — and
 * this enumerates the combinations, because an unbound policy slot fails <strong>open</strong>.
 *
 * <p>That asymmetry is why these are enumerated rather than sampled. A handler that fails to bind
 * produces a runtime with no routes, which is impossible to miss. A policy that fails to bind produces
 * a runtime that serves every route to everyone, which looks exactly like a working deployment.
 *
 * <p>{@code HttpRoutePolicy} is a kernel SPI type, so this exercises the seam without
 * {@code autoconfigure} depending on {@code exeris-spring-runtime-web} — the banned edge that shaped
 * the design. The DSL that produces real policies lives there; a lambda stands in for it here.
 */
class RoutePolicySeamBindingTest {

    @Test
    void policyIsVisibleToTheKernelWhenDeclared() throws Exception {
        HttpRoutePolicy declared = (method, path) -> RouteRequirement.permitAll();
        Observation observation = new Observation();

        bootWith(observation, Optional.of(exchange -> { }), Optional.of(declared));

        observation.assertObserved();
        assertThat(observation.bound)
                .as("HTTP_ROUTE_POLICY must be bound for the kernel boot when a policy is declared")
                .isTrue();
        assertThat(observation.seen.get())
                .as("the bound policy must be the declared instance, not a substitute")
                .isSameAs(declared);
    }

    /**
     * ADR-063 obligation 6 at the binding layer: no declaration, no binding. Asserted rather than
     * assumed, because "the slot happens to be unbound" and "we deliberately left it unbound" look
     * identical from outside — and only one of them survives a refactor.
     */
    @Test
    void slotIsLeftUnboundWhenNoPolicyIsDeclared() throws Exception {
        Observation observation = new Observation();

        bootWith(observation, Optional.of(exchange -> { }), Optional.empty());

        observation.assertObserved();
        assertThat(observation.bound)
                .as("with no declaration the slot must stay unbound (ADR-063 obligation 6)")
                .isFalse();
    }

    /**
     * The three-argument constructor predates the seam and must keep meaning "no policy". An old call
     * site silently acquiring one would change an application's authorization posture by dependency
     * bump — in the permissive direction is bad enough; in the restrictive direction it is an outage.
     */
    @Test
    void legacyConstructorBindsNoPolicy() throws Exception {
        Observation observation = new Observation();

        ExerisRuntimeLifecycle lifecycle = new ExerisRuntimeLifecycle(
                properties(),
                new ExerisSpringConfigProvider(observingEnvironment(freePort(), observation)),
                Optional.of(exchange -> { }));
        run(lifecycle);

        observation.assertObserved();
        assertThat(observation.bound).isFalse();
    }

    /**
     * A policy with no handler. Covered because the shape it replaced — an if/else over two Optionals
     * — is exactly where one of them stops being bound.
     */
    @Test
    void policyBindsEvenWithNoHttpHandler() throws Exception {
        Observation observation = new Observation();

        bootWith(observation, Optional.empty(),
                Optional.of((method, path) -> RouteRequirement.authenticated()));

        observation.assertObserved();
        assertThat(observation.bound)
                .as("the policy seam is independent of the handler seam")
                .isTrue();
    }

    private static void bootWith(Observation observation,
                                 Optional<eu.exeris.kernel.spi.http.HttpHandler> handler,
                                 Optional<HttpRoutePolicy> policy) throws IOException {
        run(new ExerisRuntimeLifecycle(
                properties(),
                new ExerisSpringConfigProvider(observingEnvironment(freePort(), observation)),
                handler,
                policy));
    }

    private static void run(ExerisRuntimeLifecycle lifecycle) {
        try {
            lifecycle.start();
            assertThat(lifecycle.isRunning()).isTrue();
        } finally {
            lifecycle.stop();
        }
    }

    private static ExerisRuntimeProperties properties() {
        // Ingress only. Persistence is excluded deliberately: it cannot boot with no database
        // reachable, and this test is about a binding, not about storage.
        return new ExerisRuntimeProperties(
                true,
                false,
                new ExerisRuntimeProperties.WebProperties(ExerisRuntimeProperties.Mode.PURE),
                new ExerisRuntimeProperties.LifecycleProperties(30),
                new ExerisRuntimeProperties.ShutdownProperties(true, 30),
                List.of("memory", "transport", "http"));
    }

    /**
     * The observation point.
     *
     * <p>{@code HTTP_ROUTE_POLICY} is a {@code ScopedValue}: bound for the kernel's boot and gone
     * afterwards, so it cannot be inspected from the test thread once {@code start()} has returned.
     * The kernel reads configuration during that boot, through this property source — which makes
     * {@code getProperty} the one hook that runs inside the scope without needing a socket, a handler
     * invocation, or a subclass of a final type.
     */
    private static StandardEnvironment observingEnvironment(int port, Observation observation) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource(
                "routePolicySeamTest", Map.of("exeris.runtime.network.port", port)) {
            @Override
            public Object getProperty(String name) {
                observation.record();
                return super.getProperty(name);
            }
        });
        return environment;
    }

    /** Records what the kernel could see of the route-policy slot, from inside the boot scope. */
    private static final class Observation {
        private final AtomicBoolean ran = new AtomicBoolean();
        private final AtomicBoolean bound = new AtomicBoolean();
        private final AtomicReference<HttpRoutePolicy> seen = new AtomicReference<>();

        void record() {
            ran.set(true);
            Optional<HttpRoutePolicy> policy = HttpKernelProviders.httpRoutePolicy();
            bound.set(policy.isPresent());
            policy.ifPresent(seen::set);
        }

        void assertObserved() {
            assertThat(ran)
                    .as("the observation point never ran, so every assertion below would pass "
                            + "vacuously — the test would be green and blind")
                    .isTrue();
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
