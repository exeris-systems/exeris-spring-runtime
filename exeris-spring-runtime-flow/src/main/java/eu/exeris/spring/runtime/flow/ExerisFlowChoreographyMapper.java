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
 * Spring-side adapter for kernel choreography mappers.
 *
 * <p>Implementations are Spring beans that translate a kernel {@link EventDescriptor}
 * into a {@link ChoreographyDecision}: ignore the event, wake an existing parked flow
 * instance, or start a new flow instance. {@link ExerisFlowChoreographyBridge} discovers
 * beans of this type at lifecycle start and registers each one with the kernel via
 * {@link eu.exeris.kernel.spi.flow.FlowEngine#registerChoreographyMapper}.
 *
 * <p><b>Routing-Only Contract:</b> The mapper receives only the {@link EventDescriptor} —
 * there is no payload, no scheduler reference, and no broker handle. This is by design: the
 * kernel SPI is implementation-blind, and the choreography decision is a routing decision,
 * not an event-handling decision.
 *
 * <p><b>Thread Safety:</b> Implementations MUST be safe for concurrent invocation from
 * multiple virtual threads — the kernel dispatches mappers on the bus's dispatch path.
 *
 * @implSpec Implementations are Spring beans extending the kernel SAM interface directly
 *     rather than using method-level annotations, ensuring direct integration with the kernel
 *     choreography pipeline and access to {@link ExerisFlowTemplate}.
 * @since 0.5
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
