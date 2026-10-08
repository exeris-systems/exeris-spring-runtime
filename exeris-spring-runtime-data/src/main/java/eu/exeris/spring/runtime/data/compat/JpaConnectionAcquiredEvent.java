/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.data.compat;

import jdk.jfr.Category;
import jdk.jfr.Event;
import jdk.jfr.EventType;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;
import eu.exeris.spring.boot.autoconfigure.compat.CompatibilityMode;

/**
 * JFR event emitted when {@code ExerisDataSource.getConnection()} opens a new
 * {@code PersistenceConnection} (non-transactional path).
 *
 * <p>Distinguishable from {@link JpaConnectionBoundEvent}, which covers the
 * transactional-reuse path. Together they provide minimum observability for the
 * JPA compat path (see ADR-017 §6.4).
 *
 * @since 0.1
 */
@Name("eu.exeris.spring.runtime.data.JpaConnectionAcquired")
@Label("JPA Connection Acquired")
@Category({"Exeris Spring Runtime", "Data", "JDBC"})
@StackTrace(false)
@CompatibilityMode
public final class JpaConnectionAcquiredEvent extends Event {

    /**
     * Default constructor for flight recorder event allocation.
     */
    public JpaConnectionAcquiredEvent() {
    }

    private static final EventType EVENT_TYPE =
            EventType.getEventType(JpaConnectionAcquiredEvent.class);

    /**
     * Emits a {@link JpaConnectionAcquiredEvent} if JFR is enabled and recording.
     */
    public static void emit() {
        if (!EVENT_TYPE.isEnabled()) {
            return;
        }
        JpaConnectionAcquiredEvent event = new JpaConnectionAcquiredEvent();
        event.commit();
    }
}
