/*
 * Copyright (C) 2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.examples.orders;

import java.util.Set;

import org.springframework.stereotype.Component;

import eu.exeris.kernel.spi.events.EventDescriptor;
import eu.exeris.kernel.spi.flow.ChoreographyDecision;
import eu.exeris.spring.runtime.flow.ExerisFlowChoreographyMapper;
import eu.exeris.spring.runtime.flow.ExerisFlowTemplate;

// tag::choreography[]
/** Starts the order saga for every {@code order.placed} event, with the order id as instance id. */
@Component
public class OrderPlacedStartsSaga implements ExerisFlowChoreographyMapper {

    private final ExerisFlowTemplate flows;

    public OrderPlacedStartsSaga(ExerisFlowTemplate flows) {
        this.flows = flows;
    }

    @Override
    public Set<String> eventTypeNames() {
        return Set.of(OrderEventTypes.ORDER_PLACED);
    }

    @Override
    public ChoreographyDecision map(EventDescriptor event) {
        return new ChoreographyDecision.Start(
                flows.planFor(OrderSaga.NAME), event.streamIdHigh(), event.streamIdLow());
    }
}
// end::choreography[]
