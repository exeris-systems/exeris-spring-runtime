/*
 * Copyright (C) 2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.examples.graph;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Component;

import eu.exeris.kernel.spi.graph.model.GraphEdgeDescriptor;
import eu.exeris.kernel.spi.graph.model.GraphEdgeDescriptor.Direction;
import eu.exeris.kernel.spi.graph.model.GraphTraversal;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.spring.runtime.graph.ExerisGraphTemplate;

// tag::template[]
@Component
public class FollowGraph {

    // User -[FOLLOWS]-> User, walked from source to target.
    static final GraphEdgeDescriptor FOLLOWS = GraphEdgeDescriptor.create("User", "FOLLOWS", "User");

    // The same relationship walked from target to source.
    static final GraphEdgeDescriptor FOLLOWED_BY = new GraphEdgeDescriptor(
            "User", "FOLLOWS", "User", 1.0, false, Direction.INCOMING, null);

    private final ExerisGraphTemplate graph;

    public FollowGraph(ExerisGraphTemplate graph) {
        this.graph = graph;
    }

    public void follow(UUID follower, UUID followed) {
        graph.inTransaction(session -> session.upsertEdge(FOLLOWS, follower, followed, 1.0, null));
    }

    // Everyone reachable from start in 1 to maxDepth FOLLOWS steps.
    public List<UUID> followedWithin(UUID start, int maxDepth) {
        return graph.traverseBfs(GraphTraversal.create(start, FOLLOWS, maxDepth));
    }

    public List<UUID> followersOf(UUID user) {
        return graph.traverseBfs(GraphTraversal.create(user, FOLLOWED_BY, 1));
    }

    // The same traversal as followedWithin, as a JSON array of ids in an off-heap buffer.
    public String followedWithinJson(UUID start, int maxDepth) {
        try (LoanedBuffer buffer = graph.streamBfsJson(GraphTraversal.create(start, FOLLOWS, maxDepth))) {
            // The segment spans capacity(); the payload is its first size() bytes.
            MemorySegment json = buffer.segment().asSlice(0, buffer.size());
            // Decoding copies to the heap. To avoid the copy, write the segment where it goes
            // before the buffer is released.
            return new String(json.toArray(ValueLayout.JAVA_BYTE), StandardCharsets.UTF_8);
        }
    }
}
// end::template[]
