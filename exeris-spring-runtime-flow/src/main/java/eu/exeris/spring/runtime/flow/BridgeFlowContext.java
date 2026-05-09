/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.flow;

import eu.exeris.kernel.spi.flow.model.FlowContext;
import eu.exeris.kernel.spi.flow.model.FlowState;

/**
 * Heap-backed seed implementation of the kernel {@link FlowContext} interface used by
 * {@link ExerisFlowTemplate#newContext(String)}.
 *
 * <p>Carries the initial values handed to {@code FlowScheduler.schedule(plan, context)}:
 * a freshly minted instance UUID (split into most/least 64-bit halves), the plan's
 * definition name, {@code currentStep = 0}, {@code state = CREATED}, and the timeout
 * inherited from the compiled plan. Kernel-side state advances independently as the
 * flow progresses — this record is the seed the engine is given on submission.
 *
 * <p>Package-private on purpose. Callers that need a custom context (resume by external
 * instance id, custom timeout) implement {@link FlowContext} directly against the SPI.
 *
 * @since 0.5.0
 */
record BridgeFlowContext(
        long instanceIdMost,
        long instanceIdLeast,
        String definitionName,
        int currentStep,
        FlowState state,
        long timeoutNanos) implements FlowContext {
}
