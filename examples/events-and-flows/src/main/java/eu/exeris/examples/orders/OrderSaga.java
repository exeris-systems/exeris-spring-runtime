/*
 * Copyright (C) 2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.examples.orders;

import java.util.UUID;

import org.springframework.stereotype.Component;

import eu.exeris.kernel.spi.flow.FlowDefinitionBuilder;
import eu.exeris.kernel.spi.flow.model.FlowContext;
import eu.exeris.kernel.spi.flow.model.FlowDefinition;
import eu.exeris.kernel.spi.flow.model.FlowOutcome;
import eu.exeris.spring.runtime.flow.ExerisFlowDefinition;

// tag::saga[]
@Component
public class OrderSaga implements ExerisFlowDefinition {

    public static final String NAME = "order-saga";

    private final OrderBook orders;
    private final Inventory inventory;
    private final Payments payments;

    public OrderSaga(OrderBook orders, Inventory inventory, Payments payments) {
        this.orders = orders;
        this.inventory = inventory;
        this.payments = payments;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public FlowDefinition define(FlowDefinitionBuilder builder) {
        return builder
                .step("reserve-stock", this::reserveStock, this::releaseStock)
                .step("charge-payment", this::chargePayment, null)
                .transition(0, 1)
                .timeoutDuration(30_000_000_000L)
                .build();
    }

    private FlowOutcome reserveStock(FlowContext flow) {
        inventory.reserve(orderId(flow));
        return FlowOutcome.CONTINUE;
    }

    private FlowOutcome releaseStock(FlowContext flow) {
        inventory.release(orderId(flow));
        return FlowOutcome.CONTINUE;
    }

    private FlowOutcome chargePayment(FlowContext flow) {
        UUID orderId = orderId(flow);
        return payments.charge(orderId, orders.amountOf(orderId)) ? FlowOutcome.COMPLETE : FlowOutcome.FAIL;
    }

    /** The saga instance id is the order id. */
    private static UUID orderId(FlowContext flow) {
        return new UUID(flow.instanceIdMost(), flow.instanceIdLeast());
    }
}
// end::saga[]
