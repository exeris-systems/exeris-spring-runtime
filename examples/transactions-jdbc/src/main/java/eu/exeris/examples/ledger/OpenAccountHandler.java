/*
 * Copyright (C) 2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.examples.ledger;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpStatus;
import eu.exeris.spring.runtime.web.ExerisRequestHandler;
import eu.exeris.spring.runtime.web.ExerisRoute;
import eu.exeris.spring.runtime.web.ExerisServerRequest;
import eu.exeris.spring.runtime.web.ExerisServerResponse;

/** {@code POST /accounts} with {@code {"id": "<account>", "balance": <amount>}}. */
@Component
@ExerisRoute(method = HttpMethod.POST, path = "/accounts")
public class OpenAccountHandler implements ExerisRequestHandler {

    record Account(String id, long balance) {
    }

    private final Ledger ledger;

    private final ObjectMapper json;

    public OpenAccountHandler(Ledger ledger, ObjectMapper json) {
        this.ledger = ledger;
        this.json = json;
    }

    @Override
    public ExerisServerResponse handle(ExerisServerRequest request) throws Exception {
        Account account = json.readValue(request.bodyBytes(), Account.class);
        ledger.open(account.id(), account.balance());
        return ExerisServerResponse.status(HttpStatus.CREATED);
    }
}
