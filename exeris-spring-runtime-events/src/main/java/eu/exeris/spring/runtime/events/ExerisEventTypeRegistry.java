/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.events;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import eu.exeris.kernel.spi.events.EventDescriptor;
import eu.exeris.kernel.spi.events.EventEngine;
import eu.exeris.kernel.spi.events.EventRegistry;
import eu.exeris.kernel.spi.events.EventTypeSpec;

/**
 * Spring-side facade over the kernel {@link EventRegistry} that resolves event-type names
 * to ordinals and builds {@link EventDescriptor} instances on behalf of publishers.
 *
 * <p>The registry is held lazily through an {@link EventEngineSupplier} so that bean
 * construction (which happens during Spring context refresh) does not require the kernel
 * to already be booted. The first method call resolves the engine; if the kernel has not
 * yet bound an event subsystem the call fails with a clear message.
 *
 * <h2>Descriptor Construction</h2>
 * <p>{@link #descriptorFor(String, UUID)} mints a fresh event UUID and timestamp on every
 * call. Callers that need stable event IDs (e.g. for outbox de-duplication) should build
 * the descriptor directly via {@link EventDescriptor#of}; the registry only owns the
 * type-name → ordinal/flags portion.
 *
 * @since 0.1.0
 */
public final class ExerisEventTypeRegistry {

    private final EventEngineSupplier engineSupplier;

    public ExerisEventTypeRegistry(EventEngineSupplier engineSupplier) {
        this.engineSupplier = Objects.requireNonNull(engineSupplier, "engineSupplier");
    }

    /**
     * Returns the kernel ordinal registered for the supplied type name.
     *
     * @throws IllegalStateException when the kernel event subsystem is not active
     * @throws IllegalArgumentException when the type name is not registered
     */
    public int ordinalOf(String typeName) {
        Objects.requireNonNull(typeName, "typeName");
        EventRegistry registry = registry();
        if (!registry.isRegistered(typeName)) {
            throw new IllegalArgumentException("Event type not registered in kernel EventRegistry: " + typeName);
        }
        return registry.ordinalOf(typeName);
    }

    /**
     * Returns the {@link EventTypeSpec} registered for the supplied type name, or throws
     * if the kernel has no such registration.
     */
    public EventTypeSpec specFor(String typeName) {
        Objects.requireNonNull(typeName, "typeName");
        EventRegistry registry = registry();
        EventTypeSpec spec = registry.resolve(typeName);
        if (spec == null) {
            throw new IllegalArgumentException("Event type not registered in kernel EventRegistry: " + typeName);
        }
        return spec;
    }

    /**
     * Builds a fresh {@link EventDescriptor} for the supplied type and stream. A random
     * event UUID and current epoch-millis timestamp are generated; flags are derived from
     * the registered {@link EventTypeSpec}.
     */
    public EventDescriptor descriptorFor(String typeName, UUID streamId) {
        Objects.requireNonNull(typeName, "typeName");
        Objects.requireNonNull(streamId, "streamId");
        EventTypeSpec spec = specFor(typeName);
        return EventDescriptor.of(
                nextEventIdComponent(),
                nextEventIdComponent(),
                streamId.getMostSignificantBits(),
                streamId.getLeastSignificantBits(),
                spec.ordinal(),
                spec.toDescriptorFlags(),
                System.currentTimeMillis()
        );
    }

    /**
     * Returns one 64-bit component of an event UUID. The two components are concatenated
     * by {@link #descriptorFor} into a 128-bit identifier that flows through the kernel
     * EventBus.
     *
     * <p>Security posture: event UUID generation is <strong>not</strong> a security
     * boundary. The requirement on this random source is uniqueness across published
     * events on a single JVM, not unpredictability against an adversary. Switching to
     * {@link java.security.SecureRandom} would pay an entropy cost without buying any
     * defence (event IDs are visible in logs, telemetry, and downstream subscribers
     * by design); switching to {@link java.util.Random} would lower entropy without any
     * benefit. {@link ThreadLocalRandom} is the right primitive here.
     */
    @SuppressWarnings("java:S2245") // event UUID generation is not a security boundary
    private static long nextEventIdComponent() {
        return ThreadLocalRandom.current().nextLong();
    }

    private EventRegistry registry() {
        EventEngine engine = engineSupplier.requireEngine();
        return engine.registry();
    }
}
