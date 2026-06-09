/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.data.compat;

import jdk.jfr.EventType;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Coverage for the JFR observability events emitted by the JDBC compat bridge
 * ({@link JpaConnectionAcquiredEvent}, {@link JpaConnectionBoundEvent}; ADR-017 §6.4).
 *
 * <p>Each test exercises both paths of {@code emit()}: the no-recording case (default test
 * JVM — {@code isEnabled()} is false, so {@code emit()} early-returns; asserted as no-throw)
 * and the enabled commit path (an active recording with the event enabled), asserting the
 * committed event is present in the recording stream. Test execution is sequential, so the
 * no-recording case is not perturbed by the recording opened in the enabled case.
 */
class JpaConnectionEventsTest {

    private static final String ACQUIRED = "eu.exeris.spring.runtime.data.JpaConnectionAcquired";
    private static final String BOUND = "eu.exeris.spring.runtime.data.JpaConnectionBound";

    @Test
    void acquiredEvent_disabledThenEnabled() throws IOException {
        // no-recording path: isEnabled() false → emit() early-returns (asserted no-throw)
        assertThatCode(JpaConnectionAcquiredEvent::emit).doesNotThrowAnyException();
        // enabled path — committed event must appear in the recording
        assertThat(recordEmit(ACQUIRED, JpaConnectionAcquiredEvent::emit)).contains(ACQUIRED);
    }

    @Test
    void boundEvent_disabledThenEnabled() throws IOException {
        assertThatCode(JpaConnectionBoundEvent::emit).doesNotThrowAnyException();
        assertThat(recordEmit(BOUND, JpaConnectionBoundEvent::emit)).contains(BOUND);
    }

    /** Runs {@code emitter} inside an active recording that enables {@code eventName}, returning
     *  the event-type names captured. */
    private static List<String> recordEmit(String eventName, Runnable emitter) throws IOException {
        Path dump = Files.createTempFile("exeris-jfr-", ".jfr");
        try (Recording recording = new Recording()) {
            recording.enable(eventName);
            recording.start();
            emitter.run();
            recording.stop();
            recording.dump(dump);
            return RecordingFile.readAllEvents(dump).stream()
                    .map(RecordedEvent::getEventType)
                    .map(EventType::getName)
                    .toList();
        } finally {
            Files.deleteIfExists(dump);
        }
    }
}
