/*
 * Copyright (C) 2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.examples.orders;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/** Stock reservations, one per order. */
@Component
public class Inventory {

    private final Set<UUID> reserved = ConcurrentHashMap.newKeySet();
    private final Set<UUID> released = ConcurrentHashMap.newKeySet();

    public void reserve(UUID orderId) {
        reserved.add(orderId);
    }

    public void release(UUID orderId) {
        if (reserved.remove(orderId)) {
            released.add(orderId);
        }
    }

    public boolean isReserved(UUID orderId) {
        return reserved.contains(orderId);
    }

    public boolean wasReleased(UUID orderId) {
        return released.contains(orderId);
    }
}
