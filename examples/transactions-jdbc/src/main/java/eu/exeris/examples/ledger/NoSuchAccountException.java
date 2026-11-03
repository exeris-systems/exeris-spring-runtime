/*
 * Copyright (C) 2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.examples.ledger;

/** Thrown when an account does not exist. */
public class NoSuchAccountException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public NoSuchAccountException(String account) {
        super("no account " + account);
    }
}
