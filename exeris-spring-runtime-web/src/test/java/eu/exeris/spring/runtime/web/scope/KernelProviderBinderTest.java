/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.web.scope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.persistence.PersistenceEngine;

/**
 * Unit tests for {@link KernelProviderBinder} — the seam that re-propagates kernel provider
 * {@code ScopedValue} slots onto a request handler thread that did not inherit the kernel
 * bootstrap scope (the externally-supplied {@code HttpHandler} runs on the transport carrier
 * thread, which carries no bootstrap bindings). This reproduces the benchmark failure
 * ("Exeris PersistenceEngine is not bound in the current scope") and asserts the fix.
 */
class KernelProviderBinderTest {

    @Test
    void noop_runsAction_andBindsNothing() {
        AtomicBoolean ran = new AtomicBoolean(false);
        KernelProviderBinder.noop().bind(() -> {
            ran.set(true);
            assertThat(KernelProviders.PERSISTENCE_ENGINE.isBound()).isFalse();
            assertThat(KernelProviders.MEMORY_ALLOCATOR.isBound()).isFalse();
        });
        assertThat(ran).isTrue();
    }

    @Test
    void capturing_whenSlotsUnbound_rebindsBothFromCapturedReferences() {
        PersistenceEngine engine = mock(PersistenceEngine.class);
        MemoryAllocator allocator = mock(MemoryAllocator.class);

        KernelProviderBinder binder =
                KernelProviderBinder.capturing(() -> Optional.of(engine), () -> Optional.of(allocator));

        AtomicReference<PersistenceEngine> seenEngine = new AtomicReference<>();
        AtomicReference<MemoryAllocator> seenAllocator = new AtomicReference<>();
        binder.bind(() -> {
            assertThat(KernelProviders.PERSISTENCE_ENGINE.isBound()).isTrue();
            assertThat(KernelProviders.MEMORY_ALLOCATOR.isBound()).isTrue();
            seenEngine.set(KernelProviders.PERSISTENCE_ENGINE.get());
            seenAllocator.set(KernelProviders.MEMORY_ALLOCATOR.get());
        });

        assertThat(seenEngine.get()).isSameAs(engine);
        assertThat(seenAllocator.get()).isSameAs(allocator);
        // Binding is scoped to the action only.
        assertThat(KernelProviders.PERSISTENCE_ENGINE.isBound()).isFalse();
        assertThat(KernelProviders.MEMORY_ALLOCATOR.isBound()).isFalse();
    }

    @Test
    void capturing_whenSlotAlreadyBound_doesNotOverrideAndDoesNotConsultSupplier() {
        PersistenceEngine alreadyBound = mock(PersistenceEngine.class);
        PersistenceEngine captured = mock(PersistenceEngine.class);
        AtomicBoolean supplierConsulted = new AtomicBoolean(false);

        KernelProviderBinder binder = KernelProviderBinder.capturing(
                () -> {
                    supplierConsulted.set(true);
                    return Optional.of(captured);
                },
                Optional::empty);

        AtomicReference<PersistenceEngine> seen = new AtomicReference<>();
        ScopedValue.where(KernelProviders.PERSISTENCE_ENGINE, alreadyBound)
                .run(() -> binder.bind(() -> seen.set(KernelProviders.PERSISTENCE_ENGINE.get())));

        assertThat(seen.get()).isSameAs(alreadyBound);
        assertThat(supplierConsulted).as("kernel-bound slot must not trigger a re-bind").isFalse();
    }

    @Test
    void capturing_whenNoCapturedReference_runsActionWithoutBinding() {
        KernelProviderBinder binder = KernelProviderBinder.capturing(Optional::empty, Optional::empty);

        AtomicBoolean ran = new AtomicBoolean(false);
        binder.bind(() -> {
            ran.set(true);
            assertThat(KernelProviders.PERSISTENCE_ENGINE.isBound()).isFalse();
            assertThat(KernelProviders.MEMORY_ALLOCATOR.isBound()).isFalse();
        });

        assertThat(ran).isTrue();
    }

    @Test
    void capturing_bindsOnlyThePersistenceEngine_whenAllocatorReferenceMissing() {
        PersistenceEngine engine = mock(PersistenceEngine.class);
        KernelProviderBinder binder =
                KernelProviderBinder.capturing(() -> Optional.of(engine), Optional::empty);

        binder.bind(() -> {
            assertThat(KernelProviders.PERSISTENCE_ENGINE.isBound()).isTrue();
            assertThat(KernelProviders.MEMORY_ALLOCATOR.isBound()).isFalse();
        });
    }
}
