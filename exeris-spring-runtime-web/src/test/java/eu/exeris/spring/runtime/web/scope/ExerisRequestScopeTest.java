/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.web.scope;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.Test;

/**
 * Direct coverage of the {@code ExerisRequestScope} facade — the whole of Phase 3B-α after ADR-029
 * obligations 2 and 6 were withdrawn (RFC-2026-08-08).
 *
 * <p>The unbound-path invariant previously lived in
 * {@code ExerisStructuredScopeIntegrationTest#disabledPathReturnsEmpty} and is restated here without
 * the deleted wrapper. It was always a property of this facade rather than of the fan-out helper.
 *
 * <p>Nothing here asserts {@code ScopedValue} propagation semantics: those belong to the JDK, and
 * mistaking them for our own is precisely what the withdrawal corrected.
 */
class ExerisRequestScopeTest {

    @Test
    void unboundScope_typedAccessorsReturnEmpty() {
        assertThat(ExerisRequestScope.current()).isEmpty();
        assertThat(ExerisRequestScope.tenantId()).isEmpty();
        assertThat(ExerisRequestScope.correlationId()).isEmpty();
        assertThat(ExerisRequestScope.attribute("anything", String.class)).isEmpty();
    }

    @Test
    void unboundScope_requireAccessorsThrow() {
        assertThatThrownBy(ExerisRequestScope::requireTenantId).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(ExerisRequestScope::requireCorrelationId).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void runWith_bindsForTheDurationAndUnbindsAfter() {
        UUID tenant = UUID.randomUUID();
        RequestScope scope = new RequestScope(tenant, "corr-1", Map.of("k", "v"));

        ExerisRequestScope.runWith(scope, () -> {
            assertThat(ExerisRequestScope.tenantId()).contains(tenant);
            assertThat(ExerisRequestScope.correlationId()).contains("corr-1");
            assertThat(ExerisRequestScope.attribute("k", String.class)).contains("v");
            assertThat(ExerisRequestScope.requireTenantId()).isEqualTo(tenant);
        });

        assertThat(ExerisRequestScope.current())
                .as("binding must not outlive runWith")
                .isEmpty();
    }

    @Test
    void callWith_returnsTheValueAndBindsWhileRunning() throws Exception {
        UUID tenant = UUID.randomUUID();
        RequestScope scope = new RequestScope(tenant, "corr-2", null);

        Optional<UUID> observed = ExerisRequestScope.callWith(scope, ExerisRequestScope::tenantId);

        assertThat(observed).contains(tenant);
        assertThat(ExerisRequestScope.current()).isEmpty();
    }

    /**
     * The rebind is what a caller must do explicitly on a thread they spawn themselves — the
     * supported answer now that no fan-out helper ships. Asserts our facade round-trips it, not that
     * the JDK propagates anything.
     */
    @Test
    void callWith_rebindsOnAThreadThatInheritedNothing() throws Exception {
        UUID tenant = UUID.randomUUID();
        RequestScope scope = new RequestScope(tenant, "corr-3", null);
        AtomicReference<Optional<UUID>> seen = new AtomicReference<>();

        Thread worker = Thread.ofVirtual().start(
                () -> seen.set(ExerisRequestScope.callWith(scope, ExerisRequestScope::tenantId)));
        worker.join();

        assertThat(seen.get()).contains(tenant);
    }

    @Test
    void nestedScopes_innerShadowsOuterAndOuterIsRestored() {
        UUID outerTenant = UUID.randomUUID();
        UUID innerTenant = UUID.randomUUID();

        ExerisRequestScope.runWith(new RequestScope(outerTenant, "outer", null), () -> {
            ExerisRequestScope.runWith(new RequestScope(innerTenant, "inner", null),
                    () -> assertThat(ExerisRequestScope.tenantId()).contains(innerTenant));
            assertThat(ExerisRequestScope.tenantId())
                    .as("outer binding must be restored once the inner scope ends")
                    .contains(outerTenant);
        });
    }
}
