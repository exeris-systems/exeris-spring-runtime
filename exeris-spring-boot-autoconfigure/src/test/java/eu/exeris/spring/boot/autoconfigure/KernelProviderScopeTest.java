/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.boot.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.persistence.PersistenceEngine;

/**
 * Unit tests for {@link KernelProviderScope} — the value-returning sibling of the web module's
 * {@code KernelProviderBinder}, used by non-web bridge modules (flow step actions in particular)
 * to re-propagate kernel provider {@code ScopedValue} slots onto threads the bootstrap scope
 * does not reach. This reproduces the saga-step failure ("Exeris PersistenceEngine is not bound
 * in the current scope" on a flow scheduler worker thread) and asserts the fix.
 */
class KernelProviderScopeTest {

    @Test
    void noop_runsAction_returnsResult_andBindsNothing() {
        String result = KernelProviderScope.noop().call(() -> {
            assertThat(KernelProviders.PERSISTENCE_ENGINE.isBound()).isFalse();
            assertThat(KernelProviders.MEMORY_ALLOCATOR.isBound()).isFalse();
            return "outcome";
        });
        assertThat(result).isEqualTo("outcome");
    }

    @Test
    void capturing_whenSlotsUnbound_rebindsBothFromCapturedReferences() {
        PersistenceEngine engine = mock(PersistenceEngine.class);
        MemoryAllocator allocator = mock(MemoryAllocator.class);

        KernelProviderScope scope =
                KernelProviderScope.capturing(() -> Optional.of(engine), () -> Optional.of(allocator));

        AtomicReference<PersistenceEngine> seenEngine = new AtomicReference<>();
        AtomicReference<MemoryAllocator> seenAllocator = new AtomicReference<>();
        String result = scope.call(() -> {
            assertThat(KernelProviders.PERSISTENCE_ENGINE.isBound()).isTrue();
            assertThat(KernelProviders.MEMORY_ALLOCATOR.isBound()).isTrue();
            seenEngine.set(KernelProviders.PERSISTENCE_ENGINE.get());
            seenAllocator.set(KernelProviders.MEMORY_ALLOCATOR.get());
            return "outcome";
        });

        assertThat(result).isEqualTo("outcome");
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

        KernelProviderScope scope = KernelProviderScope.capturing(
                () -> {
                    supplierConsulted.set(true);
                    return Optional.of(captured);
                },
                Optional::empty);

        AtomicReference<PersistenceEngine> seen = new AtomicReference<>();
        ScopedValue.where(KernelProviders.PERSISTENCE_ENGINE, alreadyBound)
                .run(() -> scope.call(() -> {
                    seen.set(KernelProviders.PERSISTENCE_ENGINE.get());
                    return null;
                }));

        assertThat(seen.get()).isSameAs(alreadyBound);
        assertThat(supplierConsulted).as("kernel-bound slot must not trigger a re-bind").isFalse();
    }

    @Test
    void capturing_whenNoCapturedReference_runsActionWithoutBinding() {
        KernelProviderScope scope = KernelProviderScope.capturing(Optional::empty, Optional::empty);

        String result = scope.call(() -> {
            assertThat(KernelProviders.PERSISTENCE_ENGINE.isBound()).isFalse();
            assertThat(KernelProviders.MEMORY_ALLOCATOR.isBound()).isFalse();
            return "ran";
        });

        assertThat(result).isEqualTo("ran");
    }

    @Test
    void capturing_bindsOnlyThePersistenceEngine_whenAllocatorReferenceMissing() {
        PersistenceEngine engine = mock(PersistenceEngine.class);
        KernelProviderScope scope =
                KernelProviderScope.capturing(() -> Optional.of(engine), Optional::empty);

        scope.call(() -> {
            assertThat(KernelProviders.PERSISTENCE_ENGINE.isBound()).isTrue();
            assertThat(KernelProviders.MEMORY_ALLOCATOR.isBound()).isFalse();
            return null;
        });
    }

    @Test
    void capturing_propagatesActionExceptions_andUnbindsAfterThrow() {
        PersistenceEngine engine = mock(PersistenceEngine.class);
        KernelProviderScope scope =
                KernelProviderScope.capturing(() -> Optional.of(engine), Optional::empty);

        assertThatThrownBy(() -> scope.call(() -> {
            throw new IllegalStateException("step failed");
        }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("step failed");
        assertThat(KernelProviders.PERSISTENCE_ENGINE.isBound())
                .as("binding must not leak past a throwing action")
                .isFalse();
    }
}
