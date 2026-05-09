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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import eu.exeris.kernel.spi.events.EventBus;
import eu.exeris.kernel.spi.events.EventDescriptor;
import eu.exeris.kernel.spi.events.EventEngine;
import eu.exeris.kernel.spi.events.EventPayload;
import eu.exeris.kernel.spi.events.EventRegistry;
import eu.exeris.kernel.spi.events.EventTypeSpec;

class ExerisEventPublisherTest {

    @Test
    void publishDelegatesToBus() {
        EventBus bus = mock(EventBus.class);
        EventEngineSupplier supplier = supplier(bus, null);
        ExerisEventPublisher publisher = new ExerisEventPublisher(supplier, mock(ExerisEventTypeRegistry.class));

        EventDescriptor descriptor = EventDescriptor.of(1, 2, 3, 4, 0, 0, 1L);
        EventPayload payload = EventPayload.empty();
        publisher.publish(descriptor, payload);

        verify(bus, times(1)).publish(descriptor, payload);
    }

    @Test
    void convenienceOverloadBuildsDescriptorViaTypeRegistry() {
        EventRegistry registry = mock(EventRegistry.class);
        EventTypeSpec spec = EventTypeSpec.of("payment.completed", 7);
        when(registry.resolve("payment.completed")).thenReturn(spec);

        EventBus bus = mock(EventBus.class);
        EventEngineSupplier supplier = supplier(bus, registry);
        ExerisEventTypeRegistry typeRegistry = new ExerisEventTypeRegistry(supplier);
        ExerisEventPublisher publisher = new ExerisEventPublisher(supplier, typeRegistry);

        UUID streamId = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
        EventPayload payload = EventPayload.empty();
        publisher.publish("payment.completed", streamId, payload);

        ArgumentCaptor<EventDescriptor> captor = ArgumentCaptor.forClass(EventDescriptor.class);
        verify(bus).publish(captor.capture(), any(EventPayload.class));
        EventDescriptor descriptor = captor.getValue();
        assertThat(descriptor.eventTypeOrdinal()).isEqualTo(7);
        assertThat(descriptor.streamIdHigh()).isEqualTo(streamId.getMostSignificantBits());
        assertThat(descriptor.streamIdLow()).isEqualTo(streamId.getLeastSignificantBits());
    }

    @Test
    void publishAndAwaitPropagatesInterruption() throws InterruptedException {
        EventBus bus = mock(EventBus.class);
        EventDescriptor descriptor = EventDescriptor.of(1, 2, 3, 4, 0, 0, 1L);
        EventPayload payload = EventPayload.empty();
        org.mockito.Mockito.doThrow(new InterruptedException("test")).when(bus).publishAndAwait(descriptor, payload);

        ExerisEventPublisher publisher = new ExerisEventPublisher(supplier(bus, null), mock(ExerisEventTypeRegistry.class));

        assertThatThrownBy(() -> publisher.publishAndAwait(descriptor, payload))
                .isInstanceOf(InterruptedException.class);
    }

    @Test
    void engineUnavailableFailsLoudly() {
        ExerisEventPublisher publisher = new ExerisEventPublisher(Optional::empty, mock(ExerisEventTypeRegistry.class));

        assertThatThrownBy(() -> publisher.publish(EventDescriptor.of(0, 0, 0, 0, 0, 0, 0L), EventPayload.empty()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("EventEngine is not available");
    }

    private static EventEngineSupplier supplier(EventBus bus, EventRegistry registry) {
        EventEngine engine = mock(EventEngine.class);
        when(engine.bus()).thenReturn(bus);
        if (registry != null) {
            when(engine.registry()).thenReturn(registry);
        }
        return () -> Optional.of(engine);
    }
}
