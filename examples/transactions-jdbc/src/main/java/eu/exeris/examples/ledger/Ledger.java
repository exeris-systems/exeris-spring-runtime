/*
 * Copyright (C) 2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.examples.ledger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class Ledger {

    private final JdbcTemplate jdbc;

    private final DataSource dataSource;

    public Ledger(JdbcTemplate jdbc, DataSource dataSource) {
        this.jdbc = jdbc;
        this.dataSource = dataSource;
    }

    @Transactional
    public void open(String account, long balance) {
        jdbc.update("INSERT INTO account (id, balance) VALUES (?, ?)", account, balance);
    }

    // tag::transfer[]
    @Transactional
    public void transfer(String from, String to, long amount) {
        // JdbcTemplate takes the transaction's connection, so every statement below runs in one
        // transaction, which commits or rolls back when the method returns.
        adjust(to, amount);
        adjust(from, -amount);
        if (jdbc.queryForObject("SELECT balance FROM account WHERE id = ?", Long.class, from) < 0) {
            // An unchecked exception rolls back both updates.
            throw new InsufficientFundsException(from);
        }
    }

    private void adjust(String account, long delta) {
        if (jdbc.update("UPDATE account SET balance = balance + ? WHERE id = ?", delta, account) == 0) {
            throw new NoSuchAccountException(account);
        }
    }
    // end::transfer[]

    // tag::balance[]
    @Transactional(readOnly = true)
    public long balance(String account) throws SQLException {
        // Inside the transaction getConnection() returns the transaction's connection, and close()
        // does not end it: the transaction manager ends the transaction when the method returns.
        try (Connection connection = dataSource.getConnection();
             PreparedStatement query = connection.prepareStatement(
                     "SELECT balance FROM account WHERE id = ?")) {
            query.setString(1, account);
            try (ResultSet row = query.executeQuery()) {
                if (!row.next()) {
                    throw new NoSuchAccountException(account);
                }
                return row.getLong(1);
            }
        }
    }
    // end::balance[]
}
