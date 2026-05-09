/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.flow;

import eu.exeris.kernel.spi.flow.FlowDefinitionBuilder;
import eu.exeris.kernel.spi.flow.model.FlowDefinition;

/**
 * Programming model for Spring beans that declare an Exeris {@code FlowEngine} flow.
 *
 * <p>Implementations are discovered as Spring beans by
 * {@link ExerisFlowDefinitionRegistrar} after context refresh. Each bean's
 * {@link #define(FlowDefinitionBuilder)} method is invoked once at lifecycle start;
 * the resulting {@link FlowDefinition} is compiled into a {@code FlowExecutionPlan} via
 * {@code FlowExecutionPlanFactory.compile} and stored in {@link ExerisFlowTemplate}
 * keyed by {@link #name()}.
 *
 * <h2>Why an interface, not an annotation</h2>
 * <p>Annotation-driven step discovery (e.g. {@code @FlowStep} on methods) would require
 * a reflective post-processor to assemble lambdas at runtime. That is incompatible with
 * the constructor-first discipline applied across the rest of the integration layer and
 * would silently couple the kernel SPI to Spring's reflection machinery. The interface
 * keeps the contract explicit: callers compose lambdas directly against the kernel
 * {@link FlowDefinitionBuilder} surface.
 *
 * <h2>Step body discipline</h2>
 * <p>Step actions and compensations are {@code FlowStepAction} lambdas. They receive a
 * {@code FlowContext} (kernel SPI type) and return a {@code FlowOutcome}. Spring beans
 * collaborate via constructor injection on the implementing class — they MUST NOT appear
 * in the lambda parameter list, since the lambda runs inside an Exeris-owned virtual
 * thread under a {@code ScopedValue} scope that is independent of the Spring request /
 * application thread context.
 *
 * <h2>Lifecycle coupling</h2>
 * <p>A step lambda capturing a Spring bean creates a soft lifecycle coupling: the Spring
 * bean MUST outlive the in-flight flow. {@code ExerisRuntimeLifecycle} drains the flow
 * engine before Spring shuts down, but stale state (closed pools, evicted singletons) on
 * the captured bean side is the application's responsibility.
 *
 * <h2>Example</h2>
 * <pre>{@code
 * @Component
 * public class OrderFulfillmentFlow implements ExerisFlowDefinition {
 *
 *     private final InventoryPort inventory;
 *     private final PaymentPort payment;
 *
 *     public OrderFulfillmentFlow(InventoryPort inventory, PaymentPort payment) {
 *         this.inventory = inventory;
 *         this.payment = payment;
 *     }
 *
 *     @Override public String name() { return "order-fulfillment"; }
 *
 *     @Override
 *     public FlowDefinition define(FlowDefinitionBuilder b) {
 *         return b
 *             .step("reserve-stock",  ctx -> inventory.reserve(ctx),  ctx -> inventory.release(ctx))
 *             .step("charge-payment", ctx -> payment.charge(ctx),     ctx -> payment.refund(ctx))
 *             .step("dispatch-order", ctx -> dispatch(ctx),           null)
 *             .transition(0, 1)
 *             .transition(1, 2)
 *             .timeoutDuration(300_000_000_000L)
 *             .maxRetries(3)
 *             .build();
 *     }
 * }
 * }</pre>
 *
 * @since 0.5.0
 */
public interface ExerisFlowDefinition {

    /**
     * Stable identifier used to schedule and look up this flow at runtime.
     *
     * <p>Must be unique across all {@code ExerisFlowDefinition} beans in the same
     * application context. Discovery fails fast if duplicates are detected.
     */
    String name();

    /**
     * Builds the kernel {@link FlowDefinition} for this flow using the supplied
     * {@link FlowDefinitionBuilder}. Invoked exactly once during boot.
     *
     * <p>Implementations MUST return the result of {@code builder.build()}; they MUST NOT
     * cache the builder beyond the call. {@code FlowDefinition} is the durable artefact —
     * the builder is single-use per definition.
     */
    FlowDefinition define(FlowDefinitionBuilder builder);
}
