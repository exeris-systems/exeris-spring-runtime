/*
 * Copyright (C) 2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.examples.ledger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.concurrent.atomic.AtomicReference;

import javax.sql.DataSource;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.spring.runtime.web.ExerisRequestHandler;
import eu.exeris.spring.runtime.web.ExerisRoute;
import eu.exeris.spring.runtime.web.ExerisServerRequest;
import eu.exeris.spring.runtime.web.ExerisServerResponse;

/**
 * Test-only route that runs one propagation case on a kernel request route and answers with the
 * name of the exception it ended in, or {@code ok}. {@code POST /probe?case=<case>&id=<account>}.
 */
@TestConfiguration(proxyBeanMethods = false)
class PropagationProbe {

    @Bean
    Inner inner(JdbcTemplate jdbc) {
        return new Inner(jdbc);
    }

    @Bean
    Outer outer(JdbcTemplate jdbc, DataSource dataSource, Inner inner) {
        return new Outer(jdbc, dataSource, inner);
    }

    @Bean
    Route probeRoute(Outer outer) {
        return new Route(outer);
    }

    static void insert(JdbcTemplate jdbc, String account) {
        jdbc.update("INSERT INTO account (id, balance) VALUES (?, ?)", account, 1L);
    }

    static class Inner {

        private final JdbcTemplate jdbc;

        Inner(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        @Transactional(propagation = Propagation.REQUIRED)
        public void required(String account) {
            insert(jdbc, account);
        }

        @Transactional(propagation = Propagation.REQUIRED)
        public void requiredThenFail(String account) {
            insert(jdbc, account);
            throw new IllegalStateException("inner failure");
        }

        @Transactional(propagation = Propagation.MANDATORY)
        public void mandatory(String account) {
            insert(jdbc, account);
        }

        @Transactional(propagation = Propagation.SUPPORTS)
        public void supports(String account) {
            insert(jdbc, account);
        }

        @Transactional(propagation = Propagation.REQUIRES_NEW)
        public void requiresNew(String account) {
            insert(jdbc, account);
        }

        @Transactional(propagation = Propagation.NOT_SUPPORTED)
        public void notSupported(String account) {
            insert(jdbc, account);
        }

        @Transactional(propagation = Propagation.NEVER)
        public void never(String account) {
            insert(jdbc, account);
        }

        @Transactional(propagation = Propagation.NESTED)
        public void nested(String account) {
            insert(jdbc, account);
        }
    }

    static class Outer {

        private final JdbcTemplate jdbc;

        private final DataSource dataSource;

        private final Inner inner;

        Outer(JdbcTemplate jdbc, DataSource dataSource, Inner inner) {
            this.jdbc = jdbc;
            this.dataSource = dataSource;
            this.inner = inner;
        }

        /**
         * Inserts {@code account}, then runs the inner case for {@code account + "-inner"}. A refusal
         * of the inner call is caught, so the outer insert still commits; its name is returned.
         */
        @Transactional
        public String around(String kase, String account) {
            insert(jdbc, account);
            String inside = account + "-inner";
            try {
                switch (kase) {
                    case "required" -> inner.required(inside);
                    case "mandatory" -> inner.mandatory(inside);
                    case "supports" -> inner.supports(inside);
                    case "requires-new" -> inner.requiresNew(inside);
                    case "not-supported" -> inner.notSupported(inside);
                    case "never" -> inner.never(inside);
                    case "nested" -> inner.nested(inside);
                    default -> throw new IllegalArgumentException(kase);
                }
                return "ok";
            } catch (RuntimeException ex) {
                return ex.getClass().getSimpleName();
            }
        }

        /** A joined participant rolls back and the outer method swallows its exception. */
        @Transactional
        public void swallowJoinedFailure(String account) {
            insert(jdbc, account);
            try {
                inner.requiredThenFail(account + "-inner");
            } catch (IllegalStateException expected) {
                // The outer transaction is now rollback-only.
            }
        }

        /** Runs the inner case from afterCommit; records what the callback ended in. */
        @Transactional
        public void fromAfterCommit(String kase, String account, AtomicReference<String> outcome) {
            insert(jdbc, account);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    try {
                        if (kase.equals("requires-new")) {
                            inner.requiresNew(account + "-inner");
                        } else {
                            inner.required(account + "-inner");
                        }
                        outcome.set("ok");
                    } catch (RuntimeException ex) {
                        outcome.set(ex.getClass().getSimpleName());
                    }
                }
            });
        }

        /** No @Transactional: one write through JdbcTemplate and one through the DataSource. */
        public void writeWithoutTransaction(String account) throws Exception {
            insert(jdbc, account + "-template");
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement insert = connection.prepareStatement(
                         "INSERT INTO account (id, balance) VALUES (?, ?)")) {
                insert.setString(1, account + "-datasource");
                insert.setLong(2, 1L);
                insert.executeUpdate();
            }
        }
    }

    @ExerisRoute(method = HttpMethod.POST, path = "/probe")
    static class Route implements ExerisRequestHandler {

        private final Outer outer;

        Route(Outer outer) {
            this.outer = outer;
        }

        @Override
        public ExerisServerResponse handle(ExerisServerRequest request) throws Exception {
            String kase = QueryParameters.required(request, "case");
            String account = QueryParameters.required(request, "id");
            try {
                return switch (kase) {
                    case "swallowed-joined-failure" -> {
                        outer.swallowJoinedFailure(account);
                        yield ExerisServerResponse.ok().body("ok");
                    }
                    case "after-commit-required", "after-commit-requires-new" -> {
                        AtomicReference<String> outcome = new AtomicReference<>("not run");
                        outer.fromAfterCommit(kase.substring("after-commit-".length()), account, outcome);
                        yield ExerisServerResponse.ok().body(outcome.get());
                    }
                    case "no-transaction" -> {
                        outer.writeWithoutTransaction(account);
                        yield ExerisServerResponse.ok().body("ok");
                    }
                    default -> ExerisServerResponse.ok().body(outer.around(kase, account));
                };
            } catch (RuntimeException ex) {
                return ExerisServerResponse.ok().body(ex.getClass().getSimpleName());
            }
        }
    }
}
