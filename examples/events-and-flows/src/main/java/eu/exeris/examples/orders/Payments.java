/*
 * Copyright (C) 2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.examples.orders;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/** A payment gateway that declines any charge above its limit. */
@Component
public class Payments {

    public static final long LIMIT_CENTS = 100_00;

    private final Set<UUID> charged = ConcurrentHashMap.newKeySet();

    /** Returns {@code false} when the charge is declined. */
    public boolean charge(UUID orderId, long amountCents) {
        if (amountCents > LIMIT_CENTS) {
            return false;
        }
        charged.add(orderId);
        return true;
    }

    public boolean isCharged(UUID orderId) {
        return charged.contains(orderId);
    }
}
