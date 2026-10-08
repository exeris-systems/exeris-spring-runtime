/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.events;

import java.util.Objects;
import java.util.UUID;

import eu.exeris.kernel.spi.events.EventBus;
import eu.exeris.kernel.spi.events.EventDescriptor;
import eu.exeris.kernel.spi.events.EventEngine;
import eu.exeris.kernel.spi.events.EventPayload;

/**
 * Spring-managed publisher that forwards events to the kernel {@link EventBus}.
 *
 * <p>Deliberately distinct from {@code org.springframework.context.ApplicationEventPublisher}.
 * The Exeris bus and the Spring application bus are two separate event systems by
 * architectural rule — wiring one into the other would invert ownership of the kernel
 * event path and break the runtime/application split.
 *
 * <h2>Payload Ownership</h2>
 * <p>Callers retain ownership of the {@link EventPayload} they pass in. Once the bus
 * accepts the publish call the kernel takes responsibility for the payload's lifecycle
 * along the dispatch path; callers must not reuse the payload after the publish returns.
 * Use {@code try (EventPayload p = ...)} when the payload is a one-shot allocation.
 *
 * @since 0.1
 */
public final class ExerisEventPublisher {

    private final EventEngineSupplier engineSupplier;
    private final ExerisEventTypeRegistry typeRegistry;

    /**
     * Creates a new event publisher.
     *
     * @param engineSupplier the event engine supplier
     * @param typeRegistry   the event type registry
     */
    public ExerisEventPublisher(EventEngineSupplier engineSupplier,
                                ExerisEventTypeRegistry typeRegistry) {
        this.engineSupplier = Objects.requireNonNull(engineSupplier, "engineSupplier");
        this.typeRegistry = Objects.requireNonNull(typeRegistry, "typeRegistry");
    }

    /**
     * Publishes an already-built {@link EventDescriptor} with the supplied payload.
     *
     * @param descriptor the event descriptor identifying the event type and stream
     * @param payload    the event payload
     */
    public void publish(EventDescriptor descriptor, EventPayload payload) {
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(payload, "payload");
        bus().publish(descriptor, payload);
    }

    /**
     * Convenience: builds the descriptor via {@link ExerisEventTypeRegistry#descriptorFor}
     * and publishes with the supplied payload. The descriptor's event UUID is generated
     * fresh on every call.
     *
     * @param typeName the event type name
     * @param streamId the target stream identifier
     * @param payload  the event payload
     */
    public void publish(String typeName, UUID streamId, EventPayload payload) {
        EventDescriptor descriptor = typeRegistry.descriptorFor(typeName, streamId);
        publish(descriptor, payload);
    }

    /**
     * Synchronously publishes and waits until the kernel has fully dispatched the event
     * to all subscribed handlers. Use sparingly — this blocks the calling thread.
     *
     * <p><strong>Handlers run on the calling thread</strong> in subscription order,
     * observing {@code ScopedValue} bindings established by the publisher. Calls block until
     * every handler finishes. Use {@link #publish(EventDescriptor, EventPayload)} for asynchronous fan-out.
     *
     * @param descriptor the event descriptor identifying the event type and metadata
     * @param payload    the event payload
     * @throws InterruptedException if the calling thread is interrupted while waiting
     * @throws NullPointerException if {@code descriptor} or {@code payload} is null
     */
    public void publishAndAwait(EventDescriptor descriptor, EventPayload payload) throws InterruptedException {
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(payload, "payload");
        bus().publishAndAwait(descriptor, payload);
    }

    private EventBus bus() {
        EventEngine engine = engineSupplier.requireEngine();
        return engine.bus();
    }
}
