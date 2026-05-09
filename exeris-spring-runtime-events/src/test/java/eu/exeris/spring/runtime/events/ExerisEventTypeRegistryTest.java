/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.events;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import org.junit.jupiter.api.Test;

import eu.exeris.kernel.spi.events.EventDescriptor;
import eu.exeris.kernel.spi.events.EventEngine;
import eu.exeris.kernel.spi.events.EventRegistry;
import eu.exeris.kernel.spi.events.EventTypeSpec;

class ExerisEventTypeRegistryTest {

    @Test
    void ordinalOfDelegatesToKernelRegistry() {
        EventRegistry registry = mock(EventRegistry.class);
        when(registry.isRegistered("payment.completed")).thenReturn(true);
        when(registry.ordinalOf("payment.completed")).thenReturn(7);

        ExerisEventTypeRegistry typeRegistry = new ExerisEventTypeRegistry(supplier(registry));

        assertThat(typeRegistry.ordinalOf("payment.completed")).isEqualTo(7);
    }

    @Test
    void unknownEventTypeFailsLoudly() {
        EventRegistry registry = mock(EventRegistry.class);
        when(registry.isRegistered("missing")).thenReturn(false);

        ExerisEventTypeRegistry typeRegistry = new ExerisEventTypeRegistry(supplier(registry));

        assertThatThrownBy(() -> typeRegistry.ordinalOf("missing"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing");
    }

    @Test
    void descriptorFlagsCarryRegisteredSpecMetadata() {
        EventRegistry registry = mock(EventRegistry.class);
        EventTypeSpec spec = EventTypeSpec.ofPersistent("payment.completed", 7);
        when(registry.resolve("payment.completed")).thenReturn(spec);

        ExerisEventTypeRegistry typeRegistry = new ExerisEventTypeRegistry(supplier(registry));

        UUID streamId = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
        EventDescriptor descriptor = typeRegistry.descriptorFor("payment.completed", streamId);

        assertThat(descriptor.eventTypeOrdinal()).isEqualTo(7);
        assertThat(descriptor.flags()).isEqualTo(spec.toDescriptorFlags());
        assertThat(descriptor.streamIdHigh()).isEqualTo(streamId.getMostSignificantBits());
        assertThat(descriptor.streamIdLow()).isEqualTo(streamId.getLeastSignificantBits());
        assertThat(descriptor.isPersistent()).isTrue();
    }

    @Test
    void engineUnavailableThrowsClearMessage() {
        ExerisEventTypeRegistry typeRegistry = new ExerisEventTypeRegistry(Optional::empty);

        assertThatThrownBy(() -> typeRegistry.ordinalOf("payment.completed"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("EventEngine is not available");
    }

    private static EventEngineSupplier supplier(EventRegistry registry) {
        EventEngine engine = mock(EventEngine.class);
        when(engine.registry()).thenReturn(registry);
        return () -> Optional.of(engine);
    }
}
