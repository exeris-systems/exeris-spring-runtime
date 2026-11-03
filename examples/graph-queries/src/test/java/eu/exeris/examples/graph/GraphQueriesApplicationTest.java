/*
 * Copyright (C) 2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.examples.graph;

import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.Driver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.Neo4jContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.exceptions.graph.GraphQueryException;
import eu.exeris.kernel.spi.graph.model.GraphTraversal;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.security.ImmutableStorageContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Boots the example against a real Neo4j and a real PostgreSQL. Docker is required: without it the
 * containers fail to start and the test fails.
 */
@SpringBootTest
@Testcontainers
class GraphQueriesApplicationTest {

    private static final String NEO4J_PASSWORD = "graph-example-secret";

    @Container
    static final Neo4jContainer<?> NEO4J = new Neo4jContainer<>("neo4j:5-community")
            .withAdminPassword(NEO4J_PASSWORD);

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    // tag::test-properties[]
    @DynamicPropertySource
    static void backends(DynamicPropertyRegistry registry) {
        // Read by the kernel graph engine.
        registry.add("graph.neo4j.uri", NEO4J::getBoltUrl);
        registry.add("graph.neo4j.user", () -> "neo4j");
        registry.add("graph.neo4j.password", () -> NEO4J_PASSWORD);
        // Read by Spring Boot's Neo4jAutoConfiguration for its own Driver bean, not by the kernel.
        registry.add("spring.neo4j.uri", NEO4J::getBoltUrl);
        registry.add("spring.neo4j.authentication.username", () -> "neo4j");
        registry.add("spring.neo4j.authentication.password", () -> NEO4J_PASSWORD);
        // Read by the kernel persistence subsystem, which the graph subsystem depends on.
        registry.add("exeris.runtime.persistence.jdbc-url", POSTGRES::getJdbcUrl);
        registry.add("exeris.runtime.persistence.username", POSTGRES::getUsername);
        registry.add("exeris.runtime.persistence.password", POSTGRES::getPassword);
    }
    // end::test-properties[]

    @Autowired
    private FollowGraph graph;

    @Autowired
    private FollowQueries queries;

    @Autowired
    private Driver springBootDriver;

    private UUID ann;
    private UUID bob;
    private UUID cid;
    private UUID dan;

    // ann -> bob -> cid -> dan, fresh ids per test.
    @BeforeEach
    void chain() {
        ann = UUID.randomUUID();
        bob = UUID.randomUUID();
        cid = UUID.randomUUID();
        dan = UUID.randomUUID();
        graph.follow(ann, bob);
        graph.follow(bob, cid);
        graph.follow(cid, dan);
    }

    @Test
    void traversalReturnsEveryNodeWithinTheDepthBound() {
        assertThat(graph.followedWithin(ann, 1)).containsExactly(bob);
        assertThat(graph.followedWithin(ann, 2)).containsExactlyInAnyOrder(bob, cid);
        assertThat(graph.followedWithin(ann, 3)).containsExactlyInAnyOrder(bob, cid, dan);
    }

    @Test
    void traversalHonoursTheDescriptorDirection() {
        assertThat(graph.followedWithin(cid, 1)).containsExactly(dan);
        assertThat(graph.followersOf(cid)).containsExactly(bob);
        assertThat(graph.followersOf(ann)).isEmpty();
    }

    @Test
    void annotatedMethodIsRoutedThroughTheTemplate() {
        List<UUID> reachable = queries.reachable(GraphTraversal.create(bob, FollowGraph.FOLLOWS, 2));

        assertThat(reachable).containsExactlyInAnyOrder(cid, dan);
    }

    @Test
    void streamedTraversalIsAJsonArrayOfTheReachableIds() {
        assertThat(graph.followedWithinJson(ann, 2))
                .startsWith("[").endsWith("]")
                .contains(bob.toString(), cid.toString())
                .doesNotContain(dan.toString());
    }

    @Test
    void annotatedLoanedBufferMethodIsRoutedToTheStreamingTraversal() {
        try (LoanedBuffer buffer = queries.reachableJson(GraphTraversal.create(bob, FollowGraph.FOLLOWS, 1))) {
            String json = new String(buffer.segment().asSlice(0, buffer.size()).toArray(ValueLayout.JAVA_BYTE),
                    StandardCharsets.UTF_8);
            assertThat(json).isEqualTo("[\"" + cid + "\"]");
        }
    }

    // tag::tenant-refusal[]
    @Test
    void traversalUnderATenantContextIsRefused() {
        // The Neo4j binding can scope no tenant isolation strategy, so a traversal under a
        // tenant context is refused before any query is sent.
        assertThatThrownBy(() -> ScopedValue
                .where(KernelProviders.STORAGE_CONTEXT, ImmutableStorageContext.shared("tenant-a"))
                .call(() -> graph.followedWithin(ann, 1)))
                .isInstanceOfSatisfying(GraphQueryException.class, refused -> {
                    assertThat(refused.errorCode()).isEqualTo("EX-GRPH-5002");
                    assertThat(refused.rawArgs()[0]).isEqualTo("ISOLATION_SCOPE");
                });
    }
    // end::tenant-refusal[]

    @Test
    void springBootDriverIsASecondClientOfTheSameDatabase() {
        long edges = springBootDriver.executableQuery(
                        "MATCH (:User {id: $from})-[r:FOLLOWS]->(:User {id: $to}) RETURN count(r) AS n")
                .withParameters(Map.of("from", ann.toString(), "to", bob.toString()))
                .execute()
                .records().getFirst().get("n").asLong();

        assertThat(edges).isEqualTo(1);
    }
}
