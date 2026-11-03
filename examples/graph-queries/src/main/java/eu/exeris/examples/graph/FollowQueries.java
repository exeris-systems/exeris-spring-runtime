/*
 * Copyright (C) 2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.examples.graph;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Component;

import eu.exeris.kernel.spi.graph.model.GraphTraversal;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.spring.runtime.graph.ExerisGraphQuery;

// tag::query[]
// Not final: the @ExerisGraphQuery processor installs a class-based proxy.
@Component
public class FollowQueries {

    // The proxy routes the call to ExerisGraphTemplate.traverseBfs(traversal);
    // this body never runs.
    @ExerisGraphQuery
    public List<UUID> reachable(GraphTraversal traversal) {
        throw new IllegalStateException("routed by ExerisGraphQueryProcessor");
    }

    // Routed to ExerisGraphTemplate.streamBfsJson(traversal). The caller owns the
    // returned buffer and must close it.
    @ExerisGraphQuery
    public LoanedBuffer reachableJson(GraphTraversal traversal) {
        throw new IllegalStateException("routed by ExerisGraphQueryProcessor");
    }
}
// end::query[]
