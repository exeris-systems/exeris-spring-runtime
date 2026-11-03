/*
 * Copyright (C) 2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.examples.orders;

import java.util.UUID;

import org.springframework.stereotype.Service;

import eu.exeris.kernel.spi.flow.model.FlowContext;
import eu.exeris.spring.runtime.flow.ExerisFlowTemplate;

@Service
public class Checkout {

    private final ExerisFlowTemplate flows;
    private final OrderBook orders;
    private final OrderEvents events;

    public Checkout(ExerisFlowTemplate flows, OrderBook orders, OrderEvents events) {
        this.flows = flows;
        this.orders = orders;
        this.events = events;
    }

    // tag::schedule[]
    /** Starts the saga for a new order and returns the order id. The saga runs asynchronously. */
    public UUID placeOrder(long amountCents) {
        FlowContext seed = flows.newContext(OrderSaga.NAME);
        UUID orderId = new UUID(seed.instanceIdMost(), seed.instanceIdLeast());
        // Record the order before scheduling: the first step can run before schedule returns.
        orders.put(orderId, amountCents);
        flows.schedule(OrderSaga.NAME, seed);
        return orderId;
    }
    // end::schedule[]

    // tag::announce[]
    /** Records a new order and publishes {@code order.placed}. A choreography mapper starts the saga. */
    public UUID announceOrder(long amountCents) {
        UUID orderId = UUID.randomUUID();
        orders.put(orderId, amountCents);
        events.orderPlaced(orderId);
        return orderId;
    }
    // end::announce[]
}
