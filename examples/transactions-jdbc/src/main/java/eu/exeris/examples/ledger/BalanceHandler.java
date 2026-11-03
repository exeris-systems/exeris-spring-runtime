/*
 * Copyright (C) 2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.examples.ledger;

import org.springframework.stereotype.Component;

import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpStatus;
import eu.exeris.spring.runtime.web.ExerisRequestHandler;
import eu.exeris.spring.runtime.web.ExerisRoute;
import eu.exeris.spring.runtime.web.ExerisServerRequest;
import eu.exeris.spring.runtime.web.ExerisServerResponse;

/** {@code GET /balance?id=<account>}. */
@Component
@ExerisRoute(method = HttpMethod.GET, path = "/balance")
public class BalanceHandler implements ExerisRequestHandler {

    private final Ledger ledger;

    public BalanceHandler(Ledger ledger) {
        this.ledger = ledger;
    }

    @Override
    public ExerisServerResponse handle(ExerisServerRequest request) throws Exception {
        try {
            return ExerisServerResponse.ok().body(Long.toString(ledger.balance(QueryParameters.required(request, "id"))));
        } catch (NoSuchAccountException ex) {
            return ExerisServerResponse.status(HttpStatus.NOT_FOUND).body(ex.getMessage());
        }
    }
}
