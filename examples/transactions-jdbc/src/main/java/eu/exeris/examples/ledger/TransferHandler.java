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

/** {@code POST /transfers} with {@code {"from": "<account>", "to": "<account>", "amount": <amount>}}. */
// tag::handler[]
@Component
@ExerisRoute(method = HttpMethod.POST, path = "/transfers")
public class TransferHandler implements ExerisRequestHandler {

    record Transfer(String from, String to, long amount) {
    }

    private final Ledger ledger;

    private final ObjectMapper json;

    public TransferHandler(Ledger ledger, ObjectMapper json) {
        this.ledger = ledger;
        this.json = json;
    }

    @Override
    public ExerisServerResponse handle(ExerisServerRequest request) throws Exception {
        Transfer transfer = json.readValue(request.bodyBytes(), Transfer.class);
        try {
            ledger.transfer(transfer.from(), transfer.to(), transfer.amount());
            return ExerisServerResponse.ok();
        } catch (InsufficientFundsException ex) {
            // The transaction has already rolled back when the exception reaches this point.
            return ExerisServerResponse.status(HttpStatus.CONFLICT).body(ex.getMessage());
        } catch (NoSuchAccountException ex) {
            return ExerisServerResponse.status(HttpStatus.NOT_FOUND).body(ex.getMessage());
        }
    }
}
// end::handler[]
