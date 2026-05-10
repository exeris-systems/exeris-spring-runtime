/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.flow;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.ApplicationContext;
import org.springframework.context.SmartLifecycle;

import eu.exeris.kernel.spi.events.EventBus;
import eu.exeris.kernel.spi.events.EventEngine;
import eu.exeris.kernel.spi.flow.FlowEngine;
import eu.exeris.spring.runtime.events.EventEngineSupplier;

/**
 * Discovers {@link ExerisFlowChoreographyMapper} beans and registers each one with the
 * kernel {@link FlowEngine} via {@link FlowEngine#registerChoreographyMapper} (Phase 4B
 * Step 3).
 *
 * <h2>Lifecycle</h2>
 * <p>Two-phase, mirroring {@link ExerisFlowDefinitionRegistrar}:
 * <ul>
 *   <li>{@code afterSingletonsInstantiated()} — collect mapper beans, validate that
 *       each declares at least one event type name. Cheap; runs at refresh time.</li>
 *   <li>{@code start()} — resolve {@link FlowEngine} and {@link EventBus}, gate on
 *       {@code FlowEngineCapabilities.choreographySupport()}, then register every
 *       mapper. Subscription tokens are owned by the kernel engine and released on
 *       {@code FlowEngine#close()} as part of the bootstrap shutdown reverse order.</li>
 * </ul>
 *
 * <h2>Phase ordering</h2>
 * <p>Phase {@link #PHASE} is {@link Integer#MAX_VALUE} {@code - 98} — one slot after
 * {@link ExerisFlowDefinitionRegistrar} (-99) and {@code ExerisEventListenerRegistrar}
 * (-99), so flow plans are registered and event listeners are subscribed by the time
 * choreography mappers wire themselves to the bus. Spring runs higher-phased
 * {@code SmartLifecycle} beans later in {@code start()} (and earlier in {@code stop()}).
 *
 * <h2>Tolerant / strict posture</h2>
 * <p>Mirrors {@link ExerisFlowDefinitionRegistrar}:
 * <ul>
 *   <li>No mapper beans declared: silent no-op even if the engine is missing — the
 *       module has nothing to do.</li>
 *   <li>Mapper beans declared but {@link FlowEngine} unavailable: fail loud at
 *       {@link #start()} when {@code exeris.runtime.flow.require-engine=true} (default);
 *       log a diagnostic and continue when explicitly opted out (test/dev only).</li>
 *   <li>Mapper beans declared but {@link EventEngine} (and therefore {@link EventBus})
 *       unavailable: same posture as above. The choreography bridge cannot register
 *       without a bus, so a missing bus is treated identically to a missing engine.</li>
 *   <li>Mapper beans declared but the bound engine reports
 *       {@code choreographySupport() = false}: always fail loud — the user explicitly
 *       opted into choreography via {@code exeris.runtime.flow.choreography-enabled=true}
 *       and a tier without that capability cannot honour the contract.</li>
 * </ul>
 *
 * <h2>Subscription teardown</h2>
 * <p>{@link #stop()} does not unsubscribe — the kernel engine's {@code close()} cancels
 * all tokens it owns when {@code ExerisRuntimeLifecycle} stops, which happens after this
 * bean's {@code stop()} per Spring's reverse-phase shutdown. Local state is cleared so a
 * subsequent {@code start()} re-runs registration cleanly.
 *
 * @since 0.5.0
 * @see ExerisFlowChoreographyMapper
 * @see FlowEngine#registerChoreographyMapper
 */
public final class ExerisFlowChoreographyBridge implements SmartInitializingSingleton, SmartLifecycle {

    /**
     * SmartLifecycle phase for this bridge: {@link Integer#MAX_VALUE} {@code - 98}.
     *
     * <p>One slot after {@link ExerisFlowDefinitionRegistrar} (which sits at
     * {@code Integer.MAX_VALUE - 99}, alongside {@code ExerisEventListenerRegistrar}),
     * so plans are compiled and event listeners are subscribed before the bridge wires
     * choreography mappers to the bus. The kernel lifecycle itself sits at
     * {@code Integer.MAX_VALUE - 100}, so the engine reference is captured by the time
     * this bean's {@link #start()} fires.
     */
    public static final int PHASE = Integer.MAX_VALUE - 98;

    private static final Logger LOG = System.getLogger(ExerisFlowChoreographyBridge.class.getName());

    private final ApplicationContext applicationContext;
    private final FlowEngineSupplier flowEngineSupplier;
    private final EventEngineSupplier eventEngineSupplier;
    private final ExerisFlowProperties properties;
    private final Object lifecycleLock = new Object();

    private final List<ExerisFlowChoreographyMapper> mappers = new ArrayList<>();
    private volatile boolean running = false;

    public ExerisFlowChoreographyBridge(ApplicationContext applicationContext,
                                        FlowEngineSupplier flowEngineSupplier,
                                        EventEngineSupplier eventEngineSupplier,
                                        ExerisFlowProperties properties) {
        this.applicationContext = Objects.requireNonNull(applicationContext, "applicationContext");
        this.flowEngineSupplier = Objects.requireNonNull(flowEngineSupplier, "flowEngineSupplier");
        this.eventEngineSupplier = Objects.requireNonNull(eventEngineSupplier, "eventEngineSupplier");
        this.properties = Objects.requireNonNull(properties, "properties");
    }

    @Override
    public void afterSingletonsInstantiated() {
        mappers.clear();
        Map<String, ExerisFlowChoreographyMapper> beans =
                applicationContext.getBeansOfType(ExerisFlowChoreographyMapper.class);
        for (Map.Entry<String, ExerisFlowChoreographyMapper> entry : beans.entrySet()) {
            ExerisFlowChoreographyMapper mapper = entry.getValue();
            Set<String> types = mapper.eventTypeNames();
            if (types == null || types.isEmpty()) {
                throw new IllegalStateException(
                        "ExerisFlowChoreographyMapper bean '" + entry.getKey()
                                + "' declares no event type names. eventTypeNames() must "
                                + "return at least one name.");
            }
            mappers.add(mapper);
        }
    }

    @Override
    public void start() {
        synchronized (lifecycleLock) {
            if (running) {
                return;
            }
            if (mappers.isEmpty()) {
                running = true;
                return;
            }
            Optional<FlowEngine> flow = flowEngineSupplier.tryGet();
            Optional<EventEngine> events = eventEngineSupplier.tryGet();
            if (flow.isEmpty() || events.isEmpty()) {
                if (properties.requireEngine()) {
                    throw new IllegalStateException(
                            "Exeris flow choreography bridge cannot start: "
                                    + mappers.size() + " ExerisFlowChoreographyMapper bean(s) declared "
                                    + "but " + (flow.isEmpty() ? "FlowEngine" : "EventEngine")
                                    + " is not bound. Confirm the kernel has a FlowProvider and an "
                                    + "EventProvider on the classpath, exeris.runtime.events.enabled=true "
                                    + "and exeris.runtime.auto-start is enabled. To explicitly tolerate "
                                    + "this in tests/dev, set exeris.runtime.flow.require-engine=false.");
                }
                LOG.log(Level.WARNING,
                        "Exeris flow choreography bridge starting without a kernel "
                                + (flow.isEmpty() ? "FlowEngine" : "EventEngine")
                                + " — {0} ExerisFlowChoreographyMapper bean(s) will not be subscribed. "
                                + "exeris.runtime.flow.require-engine=false has been set; this is "
                                + "intended for test/dev only.",
                        mappers.size());
                running = true;
                return;
            }
            FlowEngine flowEngine = flow.get();
            if (!flowEngine.capabilities().choreographySupport()) {
                throw new IllegalStateException(
                        "Exeris flow choreography bridge cannot start: kernel FlowEngine "
                                + "(provider=" + flowEngine.capabilities().providerId()
                                + ") reports choreographySupport=false but "
                                + mappers.size() + " ExerisFlowChoreographyMapper bean(s) are declared. "
                                + "Either disable choreography (exeris.runtime.flow.choreography-enabled=false) "
                                + "or run on a tier that supports it.");
            }
            EventBus bus = events.get().bus();
            for (ExerisFlowChoreographyMapper mapper : mappers) {
                flowEngine.registerChoreographyMapper(mapper, mapper.eventTypeNames(), bus);
            }
            running = true;
        }
    }

    @Override
    public void stop() {
        synchronized (lifecycleLock) {
            if (!running) {
                return;
            }
            // Kernel engine.close() cancels subscription tokens it owns as part of
            // ExerisRuntimeLifecycle.stop(). We just clear local state so a subsequent
            // start() re-runs registration cleanly against a freshly bound engine.
            running = false;
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return PHASE;
    }

    int registeredMapperCount() {
        return mappers.size();
    }
}
