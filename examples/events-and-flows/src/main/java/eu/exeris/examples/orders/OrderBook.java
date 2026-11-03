/*
 * Copyright (C) 2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.examples.orders;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/** The amount of each order, keyed by order id. The saga's steps read it. */
@Component
public class OrderBook {

    private final Map<UUID, Long> amounts = new ConcurrentHashMap<>();

    public void put(UUID orderId, long amountCents) {
        amounts.put(orderId, amountCents);
    }

    public long amountOf(UUID orderId) {
        Long amount = amounts.get(orderId);
        if (amount == null) {
            throw new IllegalStateException("unknown order " + orderId);
        }
        return amount;
    }
}
