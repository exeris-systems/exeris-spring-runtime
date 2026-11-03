/*
 * Copyright (C) 2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.examples.security;

import org.springframework.stereotype.Component;

import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.spring.runtime.web.ExerisRequestHandler;
import eu.exeris.spring.runtime.web.ExerisRoute;
import eu.exeris.spring.runtime.web.ExerisServerRequest;
import eu.exeris.spring.runtime.web.ExerisServerResponse;

@Component
@ExerisRoute(method = HttpMethod.GET, path = "/account")
public class AccountHandler implements ExerisRequestHandler {

    @Override
    public ExerisServerResponse handle(ExerisServerRequest request) {
        return ExerisServerResponse.ok().body("account");
    }
}
