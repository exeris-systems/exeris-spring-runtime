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

import org.junit.jupiter.api.Test;

import eu.exeris.kernel.spi.graph.GraphEngine;
import eu.exeris.kernel.spi.graph.GraphSession;
import eu.exeris.kernel.spi.graph.model.GraphTraversal;
import eu.exeris.kernel.spi.memory.LoanedBuffer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Phase 4C Step 3 — {@link ExerisGraphQueryProcessor} validation + routing tests.
 *
 * <p>Validation paths (per ADR-030 obligation 4 — fail-fast at post-processing time):
 * non-public method, unsupported return type, wrong parameter shape. Routing paths cover
 * {@code List<UUID>} → {@code traverseBfs} and {@code LoanedBuffer} → {@code streamBfsJson}.
 */
class ExerisGraphQueryProcessorTest {

    private ExerisGraphTemplate buildTemplate(GraphSession session) {
        GraphEngine engine = mock(GraphEngine.class);
        when(engine.openSession()).thenReturn(session);
        return new ExerisGraphTemplate(() -> Optional.of(engine), new ExerisGraphProperties(true, true));
    }

    @Test
    void nonAnnotatedBean_returnedAsIs() {
        var template = buildTemplate(mock(GraphSession.class));
        var processor = new ExerisGraphQueryProcessor(template);
        Object bean = new Object();
        assertThat(processor.postProcessAfterInitialization(bean, "plain")).isSameAs(bean);
    }

    @Test
    void unsupportedReturnType_failsFastAtPostProcessing() {
        var template = buildTemplate(mock(GraphSession.class));
        var processor = new ExerisGraphQueryProcessor(template);

        assertThatThrownBy(() ->
                processor.postProcessAfterInitialization(new UnsupportedReturnTypeBean(), "bad"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("returns java.lang.String")
                .hasMessageContaining("List<UUID>")
                .hasMessageContaining("LoanedBuffer");
    }

    @Test
    void wrongParameterShape_failsFastAtPostProcessing() {
        var template = buildTemplate(mock(GraphSession.class));
        var processor = new ExerisGraphQueryProcessor(template);

        assertThatThrownBy(() ->
                processor.postProcessAfterInitialization(new WrongParamShapeBean(), "bad"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exactly one parameter")
                .hasMessageContaining("GraphTraversal");
    }

    @Test
    void listUuidReturnType_routesToTraverseBfs() {
        GraphSession session = mock(GraphSession.class);
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        when(session.traverseBreadthFirst(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(a, b));
        var template = buildTemplate(session);
        var processor = new ExerisGraphQueryProcessor(template);

        var proxied = (ListUuidBean) processor.postProcessAfterInitialization(new ListUuidBean(), "ok");
        var traversal = GraphTraversal.create(UUID.randomUUID(),
        eu.exeris.kernel.spi.graph.model.GraphEdgeDescriptor.create("User", "FOLLOWS", "User"),
        2);
        assertThat(proxied.findNeighbours(traversal)).containsExactly(a, b);
    }

    @Test
    void loanedBufferReturnType_routesToStreamBfsJson() {
        GraphSession session = mock(GraphSession.class);
        LoanedBuffer buffer = mock(LoanedBuffer.class);
        when(session.streamBfsJson(org.mockito.ArgumentMatchers.any())).thenReturn(buffer);
        var template = buildTemplate(session);
        var processor = new ExerisGraphQueryProcessor(template);

        var proxied = (LoanedBufferBean) processor.postProcessAfterInitialization(new LoanedBufferBean(), "ok");
        var traversal = GraphTraversal.create(UUID.randomUUID(),
        eu.exeris.kernel.spi.graph.model.GraphEdgeDescriptor.create("User", "FOLLOWS", "User"),
        2);
        assertThat(proxied.streamNeighbours(traversal)).isSameAs(buffer);
    }

    @Test
    void nullTraversalArgument_throwsAtInvocation() {
        var template = buildTemplate(mock(GraphSession.class));
        var processor = new ExerisGraphQueryProcessor(template);
        var proxied = (ListUuidBean) processor.postProcessAfterInitialization(new ListUuidBean(), "ok");

        assertThatThrownBy(() -> proxied.findNeighbours(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("null GraphTraversal");
    }

    // ---- fixtures ----

    public static class ListUuidBean {
        @ExerisGraphQuery
        public List<UUID> findNeighbours(GraphTraversal traversal) {
            throw new UnsupportedOperationException("proxied");
        }
    }

    public static class LoanedBufferBean {
        @ExerisGraphQuery
        public LoanedBuffer streamNeighbours(GraphTraversal traversal) {
            throw new UnsupportedOperationException("proxied");
        }
    }

    public static class UnsupportedReturnTypeBean {
        @ExerisGraphQuery
        public String wrongReturn(GraphTraversal traversal) {
            throw new UnsupportedOperationException("never invoked — validation should reject");
        }
    }

    public static class WrongParamShapeBean {
        @ExerisGraphQuery
        public List<UUID> wrongParams(String name, int depth) {
            throw new UnsupportedOperationException("never invoked — validation should reject");
        }
    }
}
