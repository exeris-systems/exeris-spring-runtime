/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.web.scope.concurrent;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import eu.exeris.spring.runtime.web.scope.ExerisRequestScope;
import eu.exeris.spring.runtime.web.scope.RequestScope;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 3B-α merge-blocking guards per ADR-029 obligation 6 — tenant isolation across forks.
 */
class ExerisStructuredScopeIntegrationTest {

    /**
     * Inside a {@code fork(...)} task, {@link ExerisRequestScope#tenantId()} must return the
     * same value as in the enclosing scope at construction time, without any manual rebinding
     * at the call site.
     */
    @Test
    void tenantIdPropagatesAcrossForks() throws Exception {
        UUID outerTenant = UUID.randomUUID();
        RequestScope outer = new RequestScope(outerTenant, "corr-outer", null);

        AtomicReference<UUID> innerObserved = new AtomicReference<>();

        ExerisRequestScope.runWith(outer, () -> {
            try (var scope = ExerisStructuredScope.<UUID>failFast()) {
                var task = scope.fork(() -> {
                    innerObserved.set(ExerisRequestScope.tenantId().orElse(null));
                    return outerTenant;
                });
                scope.join();
                assertThat(task.get()).isEqualTo(outerTenant);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        });

        assertThat(innerObserved.get())
                .as("tenantId observed inside fork must equal the tenant bound on the outer scope")
                .isEqualTo(outerTenant);
    }

    /**
     * Two concurrent outer request scopes with different tenants must never see each other's
     * tenant from inside a fork. This catches accidental {@code ThreadLocal}-style leakage or
     * static-state regressions in the wrapper.
     */
    @Test
    void tenantIdIsolatesPerOutermostRequest() throws Exception {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        RequestScope scopeA = new RequestScope(tenantA, "corr-A", null);
        RequestScope scopeB = new RequestScope(tenantB, "corr-B", null);

        AtomicReference<UUID> observedFromA = new AtomicReference<>();
        AtomicReference<UUID> observedFromB = new AtomicReference<>();
        CountDownLatch bothStarted = new CountDownLatch(2);
        CountDownLatch releaseBoth = new CountDownLatch(1);

        CountDownLatch bothCompleted = new CountDownLatch(2);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            executor.submit(() -> ExerisRequestScope.runWith(scopeA, () -> {
                try (var scope = ExerisStructuredScope.<UUID>failFast()) {
                    scope.fork(() -> {
                        bothStarted.countDown();
                        releaseBoth.await();
                        observedFromA.set(ExerisRequestScope.tenantId().orElse(null));
                        return tenantA;
                    });
                    scope.join();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                } finally {
                    bothCompleted.countDown();
                }
            }));
            executor.submit(() -> ExerisRequestScope.runWith(scopeB, () -> {
                try (var scope = ExerisStructuredScope.<UUID>failFast()) {
                    scope.fork(() -> {
                        bothStarted.countDown();
                        releaseBoth.await();
                        observedFromB.set(ExerisRequestScope.tenantId().orElse(null));
                        return tenantB;
                    });
                    scope.join();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                } finally {
                    bothCompleted.countDown();
                }
            }));

            bothStarted.await();
            releaseBoth.countDown();
            bothCompleted.await();
        }

        assertThat(observedFromA.get())
                .as("fork started under scope A must observe tenant A, never tenant B")
                .isEqualTo(tenantA);
        assertThat(observedFromB.get())
                .as("fork started under scope B must observe tenant B, never tenant A")
                .isEqualTo(tenantB);
    }

    /**
     * {@link ExerisStructuredScope#firstSuccess()} — {@code join()} returns the value of the
     * first successful fork. Tenant propagation works the same as for {@link
     * ExerisStructuredScope#failFast()}.
     */
    @Test
    void firstSuccessPropagatesTenantAndReturnsValue() throws Exception {
        UUID tenant = UUID.randomUUID();
        RequestScope outer = new RequestScope(tenant, "corr-first-success", null);
        AtomicReference<UUID> innerTenant = new AtomicReference<>();

        ExerisRequestScope.runWith(outer, () -> {
            try (var scope = ExerisStructuredScope.<String>firstSuccess()) {
                scope.fork(() -> {
                    Thread.sleep(50);
                    return "slower";
                });
                scope.fork(() -> {
                    innerTenant.set(ExerisRequestScope.tenantId().orElse(null));
                    return "faster";
                });
                String result = scope.join();
                assertThat(result).isIn("faster", "slower");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        });

        assertThat(innerTenant.get())
                .as("tenant set on outer must be observable from inside any fork")
                .isEqualTo(tenant);
    }

    /**
     * {@link ExerisStructuredScope#allSuccessful()} — {@code join()} returns the list of result
     * values when every fork succeeds. Tenant propagation works the same.
     */
    @Test
    void allSuccessfulReturnsListOfResultsAndPropagatesTenant() throws Exception {
        UUID tenant = UUID.randomUUID();
        RequestScope outer = new RequestScope(tenant, "corr-all-successful", null);
        AtomicReference<UUID> innerTenantA = new AtomicReference<>();
        AtomicReference<UUID> innerTenantB = new AtomicReference<>();

        ExerisRequestScope.runWith(outer, () -> {
            try (var scope = ExerisStructuredScope.<String>allSuccessful()) {
                scope.fork(() -> {
                    innerTenantA.set(ExerisRequestScope.tenantId().orElse(null));
                    return "result-A";
                });
                scope.fork(() -> {
                    innerTenantB.set(ExerisRequestScope.tenantId().orElse(null));
                    return "result-B";
                });
                java.util.List<String> results = scope.join();
                assertThat(results)
                        .as("allSuccessful().join() must return the list of subtask result values")
                        .containsExactlyInAnyOrder("result-A", "result-B");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        });

        assertThat(innerTenantA.get()).isEqualTo(tenant);
        assertThat(innerTenantB.get()).isEqualTo(tenant);
    }

    /**
     * Outside an active outer scope, the wrapper still forwards forks but with no rebinding.
     * Subtasks see an unbound {@link ExerisRequestScope}.
     */
    @Test
    void disabledPathReturnsEmpty() throws Exception {
        AtomicReference<Optional<UUID>> inner = new AtomicReference<>(Optional.of(UUID.randomUUID()));

        try (var scope = ExerisStructuredScope.<Void>failFast()) {
            var task = scope.fork(() -> {
                inner.set(ExerisRequestScope.tenantId());
                return null;
            });
            scope.join();
            assertThat(task.get()).isNull();
        }

        assertThat(inner.get())
                .as("with no outer scope bound, inner fork sees Optional.empty()")
                .isEmpty();
    }
}
