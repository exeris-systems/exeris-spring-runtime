/*
 * Copyright (C) 2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.examples.orders;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import eu.exeris.kernel.spi.events.EventDescriptor;
import eu.exeris.kernel.spi.events.EventPayload;
import eu.exeris.spring.runtime.events.ExerisEventListener;

// tag::listener[]
@Component
public class OrderAudit {

    private final Set<UUID> placed = ConcurrentHashMap.newKeySet();

    // The kernel owns the payload: do not keep it, or anything read from it, past this call.
    @ExerisEventListener(eventTypes = OrderEventTypes.ORDER_PLACED)
    public void onOrderPlaced(EventDescriptor descriptor, EventPayload payload) {
        placed.add(new UUID(descriptor.streamIdHigh(), descriptor.streamIdLow()));
    }

    public boolean hasSeen(UUID orderId) {
        return placed.contains(orderId);
    }
}
// end::listener[]
