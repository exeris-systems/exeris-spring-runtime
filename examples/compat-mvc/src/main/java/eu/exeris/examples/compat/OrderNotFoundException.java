/*
 * Copyright (C) 2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.examples.compat;

public class OrderNotFoundException extends RuntimeException {

    public OrderNotFoundException(long id) {
        super("order " + id + " does not exist");
    }
}
