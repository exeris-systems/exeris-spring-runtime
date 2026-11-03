/*
 * Copyright (C) 2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.examples.graph;

import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.exceptions.graph.GraphQueryException;
import eu.exeris.kernel.spi.graph.model.GraphTraversal;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.security.ImmutableStorageContext;
import eu.exeris.spring.runtime.graph.ExerisGraphTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Boots the same application on the SQL/PGQ backend: the graph lives in PostgreSQL, and no Neo4j
 * runs. Every call is made from the test thread, outside any kernel scope. Docker is required.
 */
@SpringBootTest
@ActiveProfiles("sqlpgq")
@Testcontainers
class SqlPgqGraphQueriesTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void backend(DynamicPropertyRegistry registry) {
        registry.add("exeris.runtime.persistence.jdbc-url", POSTGRES::getJdbcUrl);
        registry.add("exeris.runtime.persistence.username", POSTGRES::getUsername);
        registry.add("exeris.runtime.persistence.password", POSTGRES::getPassword);
    }

    @Autowired
    private ExerisGraphTemplate template;

    @Autowired
    private FollowGraph graph;

    @Autowired
    private FollowQueries queries;

    private UUID ann;
    private UUID bob;
    private UUID cid;
    private UUID dan;

    // ann -> bob -> cid -> dan, fresh ids per test.
    @BeforeEach
    void chain() throws SQLException {
        // tag::edge-table[]
        // The SQL/PGQ backend stores an edge type in its own table and does not create it on a
        // write. Run this DDL from your schema migrations; the dialect generates it.
        String ddl = template.dialect().buildCreateEdgeTable(FollowGraph.FOLLOWS);
        // end::edge-table[]
        try (Connection connection = jdbc();
             Statement statement = connection.createStatement()) {
            statement.execute(ddl);
        }
        ann = UUID.randomUUID();
        bob = UUID.randomUUID();
        cid = UUID.randomUUID();
        dan = UUID.randomUUID();
        graph.follow(ann, bob);
        graph.follow(bob, cid);
        graph.follow(cid, dan);
    }

    @Test
    void testThreadIsOutsideEveryKernelScope() {
        assertThat(KernelProviders.PERSISTENCE_ENGINE.isBound()).isFalse();
        assertThat(KernelProviders.MEMORY_ALLOCATOR.isBound()).isFalse();
    }

    @Test
    void traversalReturnsEveryNodeWithinTheDepthBound() {
        assertThat(graph.followedWithin(ann, 1)).containsExactly(bob);
        assertThat(graph.followedWithin(ann, 2)).containsExactlyInAnyOrder(bob, cid);
        assertThat(graph.followedWithin(ann, 3)).containsExactlyInAnyOrder(bob, cid, dan);
    }

    @Test
    void traversalHonoursTheDescriptorDirection() {
        assertThat(graph.followersOf(cid)).containsExactly(bob);
        assertThat(graph.followersOf(ann)).isEmpty();
    }

    @Test
    void upsertingAnEdgeTwiceKeepsOneRow() throws SQLException {
        graph.follow(ann, bob);

        try (Connection connection = jdbc();
             PreparedStatement count = connection.prepareStatement("SELECT count(*) FROM "
                     + FollowGraph.FOLLOWS.tableName() + " WHERE source_id = ? AND target_id = ?")) {
            count.setObject(1, ann);
            count.setObject(2, bob);
            try (ResultSet rows = count.executeQuery()) {
                rows.next();
                assertThat(rows.getLong(1)).isEqualTo(1);
            }
        }
    }

    @Test
    void annotatedMethodsAreRoutedThroughTheTemplate() {
        assertThat(queries.reachable(GraphTraversal.create(bob, FollowGraph.FOLLOWS, 2)))
                .containsExactlyInAnyOrder(cid, dan);
        try (LoanedBuffer buffer = queries.reachableJson(GraphTraversal.create(bob, FollowGraph.FOLLOWS, 1))) {
            String json = new String(buffer.segment().asSlice(0, buffer.size()).toArray(ValueLayout.JAVA_BYTE),
                    StandardCharsets.UTF_8);
            assertThat(json).isEqualTo("[\"" + cid + "\"]");
        }
    }

    @Test
    void streamedTraversalIsAJsonArrayOfTheReachableIds() {
        assertThat(graph.followedWithinJson(ann, 2))
                .startsWith("[").endsWith("]")
                .contains(bob.toString(), cid.toString())
                .doesNotContain(dan.toString());
    }

    @Test
    void traversalUnderASharedTenantContextIsRefused() {
        // SQL/PGQ scopes SEPARATED_SCHEMA and DEDICATED contexts, not a SHARED tenant key.
        assertThatThrownBy(() -> ScopedValue
                .where(KernelProviders.STORAGE_CONTEXT, ImmutableStorageContext.shared("tenant-a"))
                .call(() -> graph.followedWithin(ann, 1)))
                .isInstanceOfSatisfying(GraphQueryException.class, refused -> {
                    assertThat(refused.errorCode()).isEqualTo("EX-GRPH-5002");
                    assertThat(refused.rawArgs()[0]).isEqualTo("ISOLATION_SCOPE");
                });
    }

    private static Connection jdbc() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
