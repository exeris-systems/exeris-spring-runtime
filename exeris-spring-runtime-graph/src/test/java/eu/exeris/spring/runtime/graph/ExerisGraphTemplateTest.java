/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.graph;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

import eu.exeris.kernel.spi.graph.GraphEngine;
import eu.exeris.kernel.spi.graph.GraphSession;
import eu.exeris.kernel.spi.graph.model.GraphTraversal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 4C Step 3 — {@link ExerisGraphTemplate} happy-path + error-path tests (per ADR-030
 * obligation 3). Integration tests against a real Community PGQ driver are Step 5.
 */
class ExerisGraphTemplateTest {

    private final GraphEngine engine = mock(GraphEngine.class);
    private final GraphSession session = mock(GraphSession.class);
    private final GraphEngineSupplier presentSupplier = () -> Optional.of(engine);
    private final GraphEngineSupplier emptySupplier = Optional::empty;
    private final ExerisGraphProperties requireOn = new ExerisGraphProperties(true, true);
    private final ExerisGraphProperties requireOff = new ExerisGraphProperties(true, false);

    @Test
    void execute_opensSession_runsCallback_closesSession() throws Exception {
        when(engine.openSession()).thenReturn(session);
        var template = new ExerisGraphTemplate(presentSupplier, requireOn);

        String result = template.execute(s -> {
            assertThat(s).isSameAs(session);
            return "ok";
        });

        assertThat(result).isEqualTo("ok");
        var io = inOrder(engine, session);
        io.verify(engine).openSession();
        io.verify(session).close();
    }

    @Test
    void execute_runtimeException_propagatesAndStillClosesSession() {
        when(engine.openSession()).thenReturn(session);
        var template = new ExerisGraphTemplate(presentSupplier, requireOn);
        var boom = new RuntimeException("boom");

        assertThatThrownBy(() -> template.execute(_ -> { throw boom; }))
                .isSameAs(boom);
        verify(session).close();
    }

    @Test
    void execute_checkedException_wrappedAndStillClosesSession() {
        when(engine.openSession()).thenReturn(session);
        var template = new ExerisGraphTemplate(presentSupplier, requireOn);
        var checked = new Exception("checked-fail");

        assertThatThrownBy(() -> template.execute(_ -> { throw checked; }))
                .isInstanceOf(ExerisGraphTemplate.GraphTemplateExecutionException.class)
                .hasCause(checked);
        verify(session).close();
    }

    @Test
    void traverseBfs_delegatesToSession() {
        when(engine.openSession()).thenReturn(session);
        var traversal = GraphTraversal.create(UUID.randomUUID(),
        eu.exeris.kernel.spi.graph.model.GraphEdgeDescriptor.create("User", "FOLLOWS", "User"),
        2);
        List<UUID> expected = List.of(UUID.randomUUID(), UUID.randomUUID());
        when(session.traverseBreadthFirst(traversal)).thenReturn(expected);

        var template = new ExerisGraphTemplate(presentSupplier, requireOn);
        assertThat(template.traverseBfs(traversal)).isSameAs(expected);
        verify(session).traverseBreadthFirst(traversal);
    }

    @Test
    void inTransaction_commitsOnSuccess() {
        when(engine.openSession()).thenReturn(session);
        var template = new ExerisGraphTemplate(presentSupplier, requireOn);
        var ran = new AtomicBoolean();

        template.inTransaction(s -> {
            ran.set(true);
            assertThat(s).isSameAs(session);
        });

        assertThat(ran).isTrue();
        var io = inOrder(session);
        io.verify(session).beginTransaction();
        io.verify(session).commit();
        io.verify(session).close();
        verify(session, never()).rollback();
    }

    @Test
    void inTransaction_rollsBackAndRethrowsOnException() {
        when(engine.openSession()).thenReturn(session);
        var template = new ExerisGraphTemplate(presentSupplier, requireOn);
        var boom = new RuntimeException("biz-fail");

        assertThatThrownBy(() -> template.inTransaction(_ -> { throw boom; }))
                .isSameAs(boom);

        var io = inOrder(session);
        io.verify(session).beginTransaction();
        io.verify(session).rollback();
        io.verify(session).close();
        verify(session, never()).commit();
    }

    @Test
    void inTransaction_rollbackFailureAttachedAsSuppressed() {
        when(engine.openSession()).thenReturn(session);
        var template = new ExerisGraphTemplate(presentSupplier, requireOn);
        var bizFail = new RuntimeException("biz-fail");
        var rollbackFail = new RuntimeException("rollback-fail");
        org.mockito.Mockito.doThrow(rollbackFail).when(session).rollback();

        assertThatThrownBy(() -> template.inTransaction(_ -> { throw bizFail; }))
                .isSameAs(bizFail)
                .satisfies(actual -> assertThat(actual.getSuppressed()).contains(rollbackFail));
    }

    @Test
    void requireOn_emptySupplier_throwsWithDiagnosticMessage() {
        var template = new ExerisGraphTemplate(emptySupplier, requireOn);
        var traversal = GraphTraversal.create(UUID.randomUUID(),
                eu.exeris.kernel.spi.graph.model.GraphEdgeDescriptor.create("User", "FOLLOWS", "User"),
                2);
        assertThatThrownBy(() -> template.traverseBfs(traversal))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("GraphEngine is not available");
    }

    @Test
    void requireOff_emptySupplier_stillThrowsAtTemplateLevel() {
        // Per ADR-030 obligation 5 — with requireEngine=false the template is constructed but
        // every method throws until an engine becomes available. The fail-loud-on-call behaviour
        // is the same as require=true at the template surface; the difference matters only at
        // the supplier-level diagnostic.
        var template = new ExerisGraphTemplate(emptySupplier, requireOff);
        assertThatThrownBy(() -> template.dialect())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot operate without a kernel GraphEngine");
    }
}
