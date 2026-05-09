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
 * JFR event emitted when {@code ExerisDataSource.getConnection()} opens a new
 * {@code PersistenceConnection} (non-transactional path).
 *
 * <p>Distinguishable from {@link JpaConnectionBoundEvent}, which covers the
 * transactional-reuse path. Together they provide minimum observability for the
 * JPA compat path (see ADR-017 §6.4).
 *
 * @since 0.1.0
 */
@Name("eu.exeris.spring.runtime.data.JpaConnectionAcquired")
@Label("JPA Connection Acquired")
@Category({"Exeris Spring Runtime", "Data", "JDBC"})
@StackTrace(false)
public final class JpaConnectionAcquiredEvent extends Event {

    private static final EventType EVENT_TYPE =
            EventType.getEventType(JpaConnectionAcquiredEvent.class);

    /**
     * Emits a {@link JpaConnectionAcquiredEvent} if JFR is enabled and recording.
     *
     * <p>The original event carried a {@code readOnly} field, but the only call site
     * always passed {@code false} (the kernel {@code PersistenceConnection} surface does
     * not currently expose a stable read-only flag, and JPA's read-only hint travels at
     * a higher layer). The field was removed rather than left as an always-false
     * placeholder. If a real read-only signal is plumbed through later, it should arrive
     * with concrete semantics, not as a default.
     */
    public static void emit() {
        if (!EVENT_TYPE.isEnabled()) {
            return;
        }
        JpaConnectionAcquiredEvent event = new JpaConnectionAcquiredEvent();
        event.commit();
    }
}
