/*
 * Copyright (C) 2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.examples.orders;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/** With persistence enabled, the kernel records each saga's terminal state in exeris_saga_state. */
@SpringBootTest
@ActiveProfiles("durable")
@DirtiesContext
class DurableSagaStateTest {

    private static final String JDBC_URL = "jdbc:h2:mem:orders-durable;DB_CLOSE_DELAY=-1";

    @Autowired
    Checkout checkout;

    @Test
    void theKernelRecordsTheStateOfEachSaga() throws Exception {
        UUID completed = checkout.placeOrder(25_00);
        UUID rolledBack = checkout.placeOrder(Payments.LIMIT_CENTS + 1);

        assertThat(awaitState(completed, "COMPLETED")).isTrue();
        assertThat(awaitState(rolledBack, "FAILED_ROLLEDBACK")).isTrue();
    }

    private static boolean awaitState(UUID instance, String expected) throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
        while (Instant.now().isBefore(deadline)) {
            if (stateOf(instance).filter(expected::equals).isPresent()) {
                return true;
            }
            Thread.sleep(20);
        }
        return false;
    }

    private static Optional<String> stateOf(UUID instance) throws SQLException {
        try (Connection connection = DriverManager.getConnection(JDBC_URL, "sa", "");
             PreparedStatement query = connection.prepareStatement(
                     "SELECT state FROM exeris_saga_state WHERE instance_id_most = ? AND instance_id_least = ?")) {
            query.setLong(1, instance.getMostSignificantBits());
            query.setLong(2, instance.getLeastSignificantBits());
            try (ResultSet row = query.executeQuery()) {
                return row.next() ? Optional.of(row.getString(1)) : Optional.empty();
            }
        }
    }
}
