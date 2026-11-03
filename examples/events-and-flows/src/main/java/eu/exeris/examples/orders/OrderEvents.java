/*
 * Copyright (C) 2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.examples.orders;

import java.util.UUID;

import org.springframework.stereotype.Component;

import eu.exeris.kernel.spi.events.EventPayload;
import eu.exeris.spring.runtime.events.ExerisEventPublisher;

// tag::publisher[]
@Component
public class OrderEvents {

    private final ExerisEventPublisher publisher;

    public OrderEvents(ExerisEventPublisher publisher) {
        this.publisher = publisher;
    }

    /** Publishes {@code order.placed} on the stream of the order. The event carries no payload. */
    public void orderPlaced(UUID orderId) {
        publisher.publish(OrderEventTypes.ORDER_PLACED, orderId, EventPayload.empty());
    }
}
// end::publisher[]
