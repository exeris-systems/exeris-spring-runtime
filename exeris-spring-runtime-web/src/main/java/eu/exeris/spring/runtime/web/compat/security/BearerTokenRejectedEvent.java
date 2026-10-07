/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.web.compat.security;

import eu.exeris.spring.boot.autoconfigure.compat.CompatibilityMode;

import jdk.jfr.Category;
import jdk.jfr.Event;
import jdk.jfr.EventType;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

/**
 * JFR event emitted whenever a Bearer token fails decoding or validation.
 *
 * <p>Emitted on both the reject path and the permissive path (see
 * {@code exeris.runtime.web.compat.security.reject-invalid-token}), so turning rejection off
 * silences the response, never the telemetry.
 *
 * <p>Carries the failure class name only. Token contents and validator messages are deliberately
 * excluded to prevent sensitive claim values from appearing in JFR recordings.
 *
 * @since 0.7
 */
@Name("eu.exeris.spring.runtime.web.BearerTokenRejected")
@Label("Bearer Token Rejected")
@Category({"Exeris Spring Runtime", "Web", "Security"})
@StackTrace(false)
@CompatibilityMode
public final class BearerTokenRejectedEvent extends Event {

    private static final EventType EVENT_TYPE =
            EventType.getEventType(BearerTokenRejectedEvent.class);

    @Label("Failure Type")
    private String failureType;

    @Label("Request Rejected")
    private boolean requestRejected;

    /**
     * Emits a rejection event if JFR is enabled and recording.
     *
     * @param failure         the decoder/validator failure; never {@code null}
     * @param requestRejected {@code true} when the request was answered with 401,
     *                        {@code false} when it continued anonymously
     */
    public static void emit(Throwable failure, boolean requestRejected) {
        if (!EVENT_TYPE.isEnabled()) {
            return;
        }
        BearerTokenRejectedEvent event = new BearerTokenRejectedEvent();
        event.failureType = failure.getClass().getName();
        event.requestRejected = requestRejected;
        event.commit();
    }
}
