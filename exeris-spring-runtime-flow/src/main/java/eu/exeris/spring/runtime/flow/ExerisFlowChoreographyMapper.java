/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.flow;

import java.util.Set;

import eu.exeris.kernel.spi.flow.ChoreographyDecision;
import eu.exeris.kernel.spi.flow.FlowChoreographyMapper;
import eu.exeris.kernel.spi.events.EventDescriptor;

/**
 * Spring-side surface for kernel choreography mappers (Phase 4B Step 3).
 *
 * <p>Implementations are Spring beans that translate a kernel {@link EventDescriptor}
 * into a {@link ChoreographyDecision}: ignore the event, wake an existing parked flow
 * instance, or start a new flow instance. {@link ExerisFlowChoreographyBridge} discovers
 * beans of this type at lifecycle start and registers each one with the kernel via
 * {@link eu.exeris.kernel.spi.flow.FlowEngine#registerChoreographyMapper}.
 *
 * <h2>Why an interface, not an annotation</h2>
 * <p>The events module exposes subscriptions through {@link ExerisEventListener} on a
 * method. Choreography mappers are different in two ways: (1) they typically need
 * access to {@link ExerisFlowTemplate} to resolve a compiled {@code FlowExecutionPlan}
 * for a {@link ChoreographyDecision.Start} decision, which pulls them toward
 * class-shaped beans; and (2) the kernel SPI already exposes them as a SAM interface,
 * so the cleanest mapping is to extend it directly rather than wrap it.
 *
 * <h2>Routing-only contract</h2>
 * <p>The mapper receives only the {@link EventDescriptor} — there is no payload, no
 * scheduler reference, and no broker handle. This is by design: the kernel SPI is
 * implementation-blind, and the choreography decision is a routing decision, not an
 * event-handling decision. If a mapper needs payload data to decide, the application
 * has split a single concern across two seams — register an event listener as well
 * via {@link ExerisEventListener}.
 *
 * <h2>Thread safety</h2>
 * <p>Implementations MUST be safe for concurrent invocation from multiple virtual
 * threads — the kernel dispatches mappers on the bus's dispatch path.
 *
 * @since 0.5.0
 * @see FlowChoreographyMapper
 * @see ChoreographyDecision
 * @see ExerisFlowChoreographyBridge
 */
public interface ExerisFlowChoreographyMapper extends FlowChoreographyMapper {

    /**
     * Returns the kernel event type names this mapper subscribes to.
     *
     * <p>Each name must already be registered in the kernel {@code EventRegistry}
     * before the bridge runs (i.e., at least one {@link ExerisEventListener}-style
     * registration on the events module side, or kernel-level registration in
     * {@code ExerisRuntimeLifecycle}). Unknown names will fail loudly when the
     * kernel attempts to subscribe.
     *
     * <p>Must not be empty; the bridge rejects mappers with no subscribed types
     * at metadata-collection time.
     *
     * @return immutable, non-empty set of event type names
     */
    Set<String> eventTypeNames();
}
