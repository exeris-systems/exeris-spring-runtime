/*
 * Copyright (C) 2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.spring.runtime.graph;

import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.env.MockEnvironment;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.graph.model.GraphEdgeDescriptor;
import eu.exeris.kernel.spi.graph.model.GraphTraversal;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.spring.boot.autoconfigure.ExerisRuntimeLifecycle;
import eu.exeris.spring.boot.autoconfigure.ExerisRuntimeProperties;
import eu.exeris.spring.boot.autoconfigure.ExerisSpringConfigProvider;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runtime integration test of the graph bridge against the real kernel graph subsystem.
 *
 * <p>Boots {@link ExerisRuntimeLifecycle} with the {@code memory}, {@code persistence} and
 * {@code graph} subsystems from {@code exeris-kernel-community}, on the SQL/PGQ backend over a
 * PostgreSQL container, and wires {@link ExerisGraphAutoConfiguration} around the started
 * lifecycle. Every call is made from the test thread, which the kernel bootstrap scope does not
 * reach: the SQL/PGQ backend reads {@link KernelProviders#PERSISTENCE_ENGINE} and the JSON
 * streaming path allocates from {@link KernelProviders#MEMORY_ALLOCATOR}, so the template must
 * re-bind both slots from the lifecycle's captured references.
 *
 * <p>Docker is required: without it the container does not start and the test fails.
 *
 * <h2>Mode</h2>
 * <p>PURE_MODE — the graph bridge is mode-agnostic; no web mode is exercised.
 */
@Testcontainers
class ExerisGraphTemplateKernelIntegrationTest {

    private static final GraphEdgeDescriptor FOLLOWS = GraphEdgeDescriptor.create("User", "FOLLOWS", "User");

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    private static ExerisRuntimeLifecycle lifecycle;
    private static AnnotationConfigApplicationContext context;

    private UUID ann;
    private UUID bob;
    private UUID cid;

    @BeforeAll
    static void bootKernelGraph() throws SQLException {
        try (Connection conn = jdbc(); Statement stmt = conn.createStatement()) {
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS %s (
                        source_id UUID NOT NULL,
                        target_id UUID NOT NULL,
                        PRIMARY KEY (source_id, target_id)
                    )
                    """.formatted(FOLLOWS.tableName()));
        }

        ExerisRuntimeProperties properties = new ExerisRuntimeProperties(
                true,
                false,
                new ExerisRuntimeProperties.WebProperties(ExerisRuntimeProperties.Mode.PURE),
                new ExerisRuntimeProperties.LifecycleProperties(30),
                new ExerisRuntimeProperties.ShutdownProperties(true, 30),
                List.of("memory", "persistence", "graph"));
        MockEnvironment env = new MockEnvironment()
                .withProperty("exeris.runtime.persistence.jdbc-url", POSTGRES.getJdbcUrl())
                .withProperty("exeris.runtime.persistence.username", POSTGRES.getUsername())
                .withProperty("exeris.runtime.persistence.password", POSTGRES.getPassword())
                .withProperty("exeris.runtime.persistence.run-migrations", "false")
                .withProperty("graph.backendType", "postgresql");
        lifecycle = new ExerisRuntimeLifecycle(properties, new ExerisSpringConfigProvider(env), Optional.empty());
        lifecycle.start();

        context = new AnnotationConfigApplicationContext();
        context.getBeanFactory().registerSingleton("exerisRuntimeLifecycle", lifecycle);
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("testProps",
                Map.of("exeris.runtime.graph.enabled", "true")));
        context.register(ExerisGraphAutoConfiguration.class, StreamingQueryConfig.class);
        context.refresh();
    }

    @AfterAll
    static void stopKernel() {
        if (context != null) {
            context.close();
        }
        if (lifecycle != null) {
            lifecycle.stop();
        }
    }

    // ann -> bob -> cid, fresh ids per test.
    @BeforeEach
    void chain() throws SQLException {
        ann = UUID.randomUUID();
        bob = UUID.randomUUID();
        cid = UUID.randomUUID();
        try (Connection conn = jdbc();
             PreparedStatement insert = conn.prepareStatement(
                     "INSERT INTO " + FOLLOWS.tableName() + " (source_id, target_id) VALUES (?, ?)")) {
            for (UUID[] edge : new UUID[][] {{ann, bob}, {bob, cid}}) {
                insert.setObject(1, edge[0]);
                insert.setObject(2, edge[1]);
                insert.addBatch();
            }
            insert.executeBatch();
        }
        assertThat(lifecycle.getGraphEngine())
                .as("kernel must capture a GraphEngine for this test to be meaningful")
                .isPresent();
        assertThat(KernelProviders.PERSISTENCE_ENGINE.isBound() || KernelProviders.MEMORY_ALLOCATOR.isBound())
                .as("the calling thread must be outside every kernel scope")
                .isFalse();
    }

    @Test
    void sqlPgqTraverseBfs_fromAThreadOutsideTheKernelScope_returnsTheReachableNodes() {
        ExerisGraphTemplate template = context.getBean(ExerisGraphTemplate.class);

        assertThat(template.traverseBfs(GraphTraversal.create(ann, FOLLOWS, 2)))
                .containsExactlyInAnyOrder(bob, cid);
    }

    @Test
    void streamBfsJson_fromAThreadOutsideTheKernelScope_returnsACallerOwnedBuffer() {
        ExerisGraphTemplate template = context.getBean(ExerisGraphTemplate.class);

        try (LoanedBuffer buffer = template.streamBfsJson(GraphTraversal.create(ann, FOLLOWS, 2))) {
            assertThat(json(buffer)).contains(bob.toString(), cid.toString());
        }
    }

    @Test
    void annotatedLoanedBufferQuery_fromAThreadOutsideTheKernelScope_isRoutedThroughTheTemplate() {
        StreamingQueries queries = context.getBean(StreamingQueries.class);

        try (LoanedBuffer buffer = queries.reachable(GraphTraversal.create(bob, FOLLOWS, 1))) {
            assertThat(json(buffer)).contains(cid.toString()).doesNotContain(bob.toString());
        }
    }

    // The segment spans capacity(); only the first size() bytes are the payload.
    private static String json(LoanedBuffer buffer) {
        byte[] bytes = buffer.segment().asSlice(0, buffer.size()).toArray(ValueLayout.JAVA_BYTE);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static Connection jdbc() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    @Configuration
    static class StreamingQueryConfig {

        @Bean
        StreamingQueries streamingQueries() {
            return new StreamingQueries();
        }
    }

    public static class StreamingQueries {

        @ExerisGraphQuery
        public LoanedBuffer reachable(GraphTraversal traversal) {
            throw new UnsupportedOperationException("routed by ExerisGraphQueryProcessor");
        }
    }
}
