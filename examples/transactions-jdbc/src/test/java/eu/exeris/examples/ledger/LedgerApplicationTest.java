/*
 * Copyright (C) 2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.examples.ledger;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;

import eu.exeris.spring.runtime.data.compat.ExerisDataSource;
import eu.exeris.spring.runtime.tx.ExerisPlatformTransactionManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Boots the example and drives every transaction through a real request and a real database. */
@SpringBootTest
@Import(PropagationProbe.class)
class LedgerApplicationTest {

    private static final int PORT = freePort();

    private final HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ApplicationContext context;

    @DynamicPropertySource
    static void port(DynamicPropertyRegistry registry) {
        registry.add("exeris.runtime.network.port", () -> PORT);
    }

    @BeforeEach
    void ready() throws InterruptedException {
        awaitAccepting(PORT);
    }

    @Test
    void theRuntimeOwnsTheTransactionManagerAndTheDataSource() {
        assertThat(transactionManager).isInstanceOf(ExerisPlatformTransactionManager.class);
        assertThat(dataSource).isInstanceOf(ExerisDataSource.class);
        // spring-jdbc is on the classpath, and Spring Boot still registers no transaction manager of
        // its own: the Exeris one is the only one, and JdbcTemplate runs over the bridge.
        assertThat(context.getBeansOfType(PlatformTransactionManager.class)).hasSize(1);
        assertThat(context.getBeansOfType(JdbcTransactionManager.class)).isEmpty();
        assertThat(jdbcTemplate.getDataSource()).isSameAs(dataSource);
    }

    @Test
    void theDataSourceServesNoThreadOutsideTheKernelScope() {
        // The test thread is not a kernel request thread, so the engine is not bound on it.
        assertThatThrownBy(() -> dataSource.getConnection())
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("PersistenceEngine is not bound in the current scope");
    }

    @Test
    void aTransferCommitsBothUpdates() throws Exception {
        assertThat(open("alice", 100).statusCode()).isEqualTo(201);
        assertThat(open("bob", 0).statusCode()).isEqualTo(201);

        assertThat(transfer("alice", "bob", 30).statusCode()).isEqualTo(200);

        assertThat(balance("alice")).isEqualTo("70");
        assertThat(balance("bob")).isEqualTo("30");
    }

    @Test
    void anOverdraftRollsBackBothUpdates() throws Exception {
        open("carol", 10);
        open("dave", 0);

        HttpResponse<String> response = transfer("carol", "dave", 50);

        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(response.body()).isEqualTo("insufficient funds in carol");
        // Both updates ran before the check failed; neither is visible afterwards.
        assertThat(balance("carol")).isEqualTo("10");
        assertThat(balance("dave")).isEqualTo("0");
    }

    @Test
    void aTransferToAnUnknownAccountRollsBackTheCreditAlreadyMade() throws Exception {
        open("gina", 20);

        HttpResponse<String> response = transfer("nobody", "gina", 5);

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).isEqualTo("no account nobody");
        assertThat(balance("gina")).isEqualTo("20");
    }

    @Test
    void aFailedInsertRollsBackAndTheNextTransactionCommits() throws Exception {
        assertThat(open("erin", 5).statusCode()).isEqualTo(201);
        // The primary key rejects a second account with the same id.
        assertThat(open("erin", 7).statusCode()).isEqualTo(500);

        assertThat(balance("erin")).isEqualTo("5");
        assertThat(open("frank", 1).statusCode()).isEqualTo(201);
        assertThat(balance("frank")).isEqualTo("1");
    }

    @Test
    void joiningPropagationsShareTheRequestTransaction() throws Exception {
        for (String kase : new String[] {"required", "mandatory", "supports"}) {
            String account = "join-" + kase;
            assertThat(probe(kase, account)).as(kase).isEqualTo("ok");
            assertThat(exists(account)).as(kase).isTrue();
            assertThat(exists(account + "-inner")).as(kase).isTrue();
        }
    }

    @Test
    void suspendingPropagationsAreRefusedOnARequestRouteAndTheOuterTransactionStillCommits() throws Exception {
        for (String kase : new String[] {"requires-new", "not-supported"}) {
            String account = "suspend-" + kase;
            assertThat(probe(kase, account)).as(kase).isEqualTo("IllegalTransactionStateException");
            // The inner work never ran; the outer insert committed.
            assertThat(exists(account)).as(kase).isTrue();
            assertThat(exists(account + "-inner")).as(kase).isFalse();
        }
    }

    @Test
    void neverAndNestedAreRefusedInsideATransaction() throws Exception {
        assertThat(probe("never", "refused-never")).isEqualTo("IllegalTransactionStateException");
        assertThat(probe("nested", "refused-nested")).isEqualTo("UnsupportedOperationException");
        assertThat(exists("refused-never")).isTrue();
        assertThat(exists("refused-nested-inner")).isFalse();
    }

    @Test
    void aJoinedParticipantThatFailsRollsBackTheWholeTransaction() throws Exception {
        assertThat(probe("swallowed-joined-failure", "joined-failure")).isEqualTo("UnexpectedRollbackException");
        assertThat(exists("joined-failure")).isFalse();
        assertThat(exists("joined-failure-inner")).isFalse();
    }

    @Test
    void anAfterCommitCallbackCannotJoinTheTransactionThatEnded() throws Exception {
        assertThat(probe("after-commit-required", "after-join")).isEqualTo("IllegalTransactionStateException");
        assertThat(exists("after-join")).isTrue();
        assertThat(exists("after-join-inner")).isFalse();
    }

    @Test
    void anAfterCommitCallbackStartsANewTransactionWithRequiresNew() throws Exception {
        assertThat(probe("after-commit-requires-new", "after-new")).isEqualTo("ok");
        assertThat(exists("after-new")).isTrue();
        assertThat(exists("after-new-inner")).isTrue();
    }

    @Test
    void writesOutsideATransactionOnARequestRouteAreRolledBackSilently() throws Exception {
        assertThat(probe("no-transaction", "bare")).isEqualTo("ok");
        assertThat(exists("bare-template")).as("JdbcTemplate write").isFalse();
        assertThat(exists("bare-datasource")).as("DataSource write").isFalse();
        // The writes were rolled back when the request ended, not left pending: the same keys insert.
        assertThat(open("bare-template", 1).statusCode()).isEqualTo(201);
        assertThat(open("bare-datasource", 1).statusCode()).isEqualTo(201);
    }

    private String probe(String kase, String account) throws IOException, InterruptedException {
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(URI.create(
                                "http://127.0.0.1:" + PORT + "/probe?case=" + kase + "&id=" + account))
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(kase).isEqualTo(200);
        return response.body();
    }

    private boolean exists(String account) throws IOException, InterruptedException {
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + PORT + "/balance?id=" + account)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(account).isIn(200, 404);
        return response.statusCode() == 200;
    }

    private HttpResponse<String> open(String account, long balance) throws IOException, InterruptedException {
        return post("/accounts", "{\"id\": \"" + account + "\", \"balance\": " + balance + "}");
    }

    private HttpResponse<String> transfer(String from, String to, long amount)
            throws IOException, InterruptedException {
        return post("/transfers",
                "{\"from\": \"" + from + "\", \"to\": \"" + to + "\", \"amount\": " + amount + "}");
    }

    private HttpResponse<String> post(String path, String json) throws IOException, InterruptedException {
        return client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + PORT + path))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private String balance(String account) throws IOException, InterruptedException {
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + PORT + "/balance?id=" + account)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return response.body();
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static void awaitAccepting(int port) throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(15));
        while (Instant.now().isBefore(deadline)) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", port), 200);
                return;
            } catch (IOException ignored) {
                Thread.sleep(100);
            }
        }
        throw new AssertionError("nothing accepted on 127.0.0.1:" + port);
    }
}
