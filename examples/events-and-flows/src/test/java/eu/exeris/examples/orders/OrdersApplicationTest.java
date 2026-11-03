/*
 * Copyright (C) 2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.examples.orders;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.events.IsolationKeySpec;
import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;
import eu.exeris.kernel.spi.exceptions.events.EventBusException;
import eu.exeris.kernel.spi.flow.FlowEngineStats;
import eu.exeris.kernel.spi.security.ImmutableStorageContext;
import eu.exeris.spring.boot.autoconfigure.ExerisRuntimeLifecycle;
import eu.exeris.spring.runtime.flow.ExerisFlowTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Boots the example against the Community kernel and drives events and sagas through it. */
@SpringBootTest
@DirtiesContext
class OrdersApplicationTest {

    @Autowired
    Checkout checkout;

    @Autowired
    OrderEvents events;

    @Autowired
    OrderAudit audit;

    @Autowired
    Inventory inventory;

    @Autowired
    Payments payments;

    @Autowired
    ExerisFlowTemplate flows;

    @Autowired
    ExerisRuntimeLifecycle runtime;

    @Test
    void aPublishedEventReachesTheListener() {
        UUID orderId = checkout.announceOrder(10_00);

        await(() -> audit.hasSeen(orderId), "the listener to receive order.placed");
    }

    @Test
    void anEventStartsTheSagaThroughChoreography() {
        UUID orderId = checkout.announceOrder(10_00);

        await(() -> payments.isCharged(orderId), "the saga started by order.placed to charge the order");
        assertThat(inventory.isReserved(orderId)).isTrue();
    }

    @Test
    void theSagaCompletesWhenEveryStepSucceeds() {
        long completedBefore = flows.stats().completedFlows();

        UUID orderId = checkout.placeOrder(25_00);

        await(() -> payments.isCharged(orderId), "the saga to charge the order");
        await(() -> flows.stats().completedFlows() > completedBefore, "the engine to count the saga completed");
        assertThat(inventory.isReserved(orderId)).isTrue();
        assertThat(inventory.wasReleased(orderId)).isFalse();
    }

    @Test
    void aFailedStepCompensatesTheStepsBeforeIt() {
        FlowEngineStats before = flows.stats();

        UUID orderId = checkout.placeOrder(Payments.LIMIT_CENTS + 1);

        await(() -> inventory.wasReleased(orderId), "the compensation to release the reserved stock");
        await(() -> flows.stats().failedFlows() > before.failedFlows(), "the engine to count the saga rolled back");
        assertThat(flows.stats().compensationsRun()).isGreaterThan(before.compensationsRun());
        assertThat(payments.isCharged(orderId)).isFalse();
        assertThat(inventory.isReserved(orderId)).isFalse();
    }

    @Test
    void aPublishUnderAnUnregisteredTenantKeyIsRefused() {
        String tenantKey = UUID.randomUUID().toString();
        ImmutableStorageContext tenant = ImmutableStorageContext.shared(tenantKey);
        UUID refused = UUID.randomUUID();

        assertThatThrownBy(() -> ScopedValue.where(KernelProviders.STORAGE_CONTEXT, tenant)
                        .run(() -> events.orderPlaced(refused)))
                .isInstanceOf(EventBusException.class)
                .satisfies(ex -> assertThat(((EventBusException) ex).errorCode())
                        .isEqualTo(KernelErrorCodes.EX_EVENT_6009));

        runtime.getEventEngine().orElseThrow().isolationRegistry().register(IsolationKeySpec.of(tenantKey, 1));
        UUID accepted = UUID.randomUUID();
        ScopedValue.where(KernelProviders.STORAGE_CONTEXT, tenant).run(() -> events.orderPlaced(accepted));

        await(() -> audit.hasSeen(accepted), "the listener to receive the tenant's event once its key is registered");
        assertThat(audit.hasSeen(refused)).isFalse();
    }

    private static void await(BooleanSupplier condition, String what) {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
        while (!condition.getAsBoolean()) {
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("timed out waiting for " + what);
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new AssertionError(ex);
            }
        }
    }
}
