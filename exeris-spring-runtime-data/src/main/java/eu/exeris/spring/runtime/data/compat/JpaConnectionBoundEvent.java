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

/**
 * JFR event emitted when {@code ExerisDataSource.getConnection()} returns an existing
 * transaction-bound {@link ExerisConnectionProxy} (transactional path — no new open).
 *
 * <p>Distinguishable from {@link JpaConnectionAcquiredEvent}, which covers new
 * connection opens. See ADR-017 §6.4 for the observability model.
 *
 * @since 0.1.0
 */
@Name("eu.exeris.spring.runtime.data.JpaConnectionBound")
@Label("JPA Connection Bound")
@Category({"Exeris Spring Runtime", "Data", "JDBC"})
@StackTrace(false)
public final class JpaConnectionBoundEvent extends Event {

    private static final EventType EVENT_TYPE =
            EventType.getEventType(JpaConnectionBoundEvent.class);

    /**
     * Emits a {@link JpaConnectionBoundEvent} if JFR is enabled and recording.
     */
    public static void emit() {
        if (!EVENT_TYPE.isEnabled()) {
            return;
        }
        JpaConnectionBoundEvent event = new JpaConnectionBoundEvent();
        event.commit();
    }
}
