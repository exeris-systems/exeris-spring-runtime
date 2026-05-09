/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.events;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.ApplicationContext;
import org.springframework.context.SmartLifecycle;
import org.springframework.core.annotation.AnnotationUtils;

import eu.exeris.kernel.spi.events.EventBus;
import eu.exeris.kernel.spi.events.EventDescriptor;
import eu.exeris.kernel.spi.events.EventEngine;
import eu.exeris.kernel.spi.events.EventHandler;
import eu.exeris.kernel.spi.events.EventPayload;
import eu.exeris.kernel.spi.events.SubscriptionToken;

/**
 * Discovers {@code @ExerisEventListener} methods on Spring beans and registers them
 * with the kernel {@link EventBus} as {@link EventHandler} subscriptions.
 *
 * <h2>Lifecycle</h2>
 * <p>Implements both {@link SmartInitializingSingleton} (collects listener metadata at
 * the end of context refresh, before any kernel boot) and {@link SmartLifecycle} (does
 * the actual subscribe at start, unsubscribe at stop). The two phases are split because
 * the kernel {@code EventEngine} is not available until {@code ExerisRuntimeLifecycle}
 * has booted, which happens during the SmartLifecycle start sequence after context
 * refresh has completed.
 *
 * <h2>Phase Ordering</h2>
 * <p>Phase {@code Integer.MAX_VALUE - 99} runs immediately after the kernel lifecycle
 * (which sits at {@code Integer.MAX_VALUE - 100}), so the kernel is booted and the
 * {@code EventEngine} reference is captured by the time {@link #start()} fires.
 *
 * <h2>Handler Signature</h2>
 * <p>Each annotated method must have signature
 * {@code void method(EventDescriptor descriptor, EventPayload payload)}. Anything else
 * is rejected with an explicit error at metadata collection time.
 *
 * @since 0.1.0
 */
public final class ExerisEventListenerRegistrar implements SmartInitializingSingleton, SmartLifecycle {

    private static final int PHASE = Integer.MAX_VALUE - 99;
    private static final MethodType HANDLER_TYPE =
            MethodType.methodType(void.class, EventDescriptor.class, EventPayload.class);
    private static final Logger LOG = System.getLogger(ExerisEventListenerRegistrar.class.getName());

    private final ApplicationContext applicationContext;
    private final EventEngineSupplier engineSupplier;

    private final List<ListenerBinding> bindings = new ArrayList<>();
    private final List<SubscriptionToken> activeSubscriptions = new ArrayList<>();
    private volatile boolean running = false;

    public ExerisEventListenerRegistrar(ApplicationContext applicationContext,
                                        EventEngineSupplier engineSupplier) {
        this.applicationContext = Objects.requireNonNull(applicationContext, "applicationContext");
        this.engineSupplier = Objects.requireNonNull(engineSupplier, "engineSupplier");
    }

    @Override
    public void afterSingletonsInstantiated() {
        bindings.clear();
        Map<String, Object> beans = applicationContext.getBeansOfType(Object.class);
        for (Map.Entry<String, Object> entry : beans.entrySet()) {
            Object bean = entry.getValue();
            for (Method method : bean.getClass().getMethods()) {
                ExerisEventListener annotation = AnnotationUtils.findAnnotation(method, ExerisEventListener.class);
                if (annotation == null) {
                    continue;
                }
                validateSignature(bean, method);
                MethodHandle handle = bindHandle(bean, method);
                for (String typeName : annotation.eventTypes()) {
                    bindings.add(new ListenerBinding(typeName, bean, method, handle));
                }
            }
        }
    }

    @Override
    public void start() {
        if (running) {
            return;
        }
        // Tolerate an absent engine during start: the kernel may be intentionally not
        // booted (test harness with auto-start=false, dev fallback, or a kernel
        // configured without an EventProvider). In all those cases the lifecycle
        // contract still requires the bean to transition to running so Spring's
        // shutdown ordering remains correct; subscriptions simply stay empty and a
        // diagnostic line is emitted. Mis-publish at runtime still fails loud
        // because the publisher and type registry call requireEngine().
        Optional<EventEngine> engine = engineSupplier.tryGet();
        if (engine.isEmpty()) {
            if (!bindings.isEmpty()) {
                LOG.log(Level.WARNING,
                        "Exeris event listener registrar starting without a kernel EventEngine — "
                                + "{0} @ExerisEventListener method(s) will not be subscribed. "
                                + "Confirm the kernel has an EventProvider on the classpath and "
                                + "exeris.runtime.auto-start has not been disabled in production.",
                        bindings.size());
            }
            running = true;
            return;
        }
        EventBus bus = engine.get().bus();
        for (ListenerBinding binding : bindings) {
            EventHandler handler = (descriptor, payload) -> invokeHandler(binding, descriptor, payload);
            SubscriptionToken token = bus.subscribe(binding.eventTypeName(), handler);
            activeSubscriptions.add(token);
        }
        running = true;
    }

    @Override
    public void stop() {
        if (!running) {
            return;
        }
        // Best-effort: try to unsubscribe via the captured engine; if the kernel is
        // already torn down (e.g. ExerisRuntimeLifecycle.stop() ran first) the engine
        // reference is gone and there is nothing left to clean up on the bus side.
        engineSupplier.tryGet().ifPresent(engine -> {
            EventBus bus = engine.bus();
            for (SubscriptionToken token : activeSubscriptions) {
                if (token != null && token.isValid()) {
                    bus.unsubscribe(token);
                }
            }
        });
        activeSubscriptions.clear();
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return PHASE;
    }

    int boundListenerCount() {
        return bindings.size();
    }

    int activeSubscriptionCount() {
        return activeSubscriptions.size();
    }

    private static void validateSignature(Object bean, Method method) {
        Class<?>[] parameterTypes = method.getParameterTypes();
        boolean returnsVoid = method.getReturnType() == void.class || method.getReturnType() == Void.TYPE;
        boolean correctArity = parameterTypes.length == 2;
        boolean correctParams = correctArity
                && parameterTypes[0] == EventDescriptor.class
                && parameterTypes[1] == EventPayload.class;
        if (!returnsVoid || !correctParams) {
            throw new IllegalStateException(
                    "@ExerisEventListener method must have signature "
                            + "void(EventDescriptor, EventPayload): "
                            + bean.getClass().getName() + "#" + method.getName());
        }
    }

    private static MethodHandle bindHandle(Object bean, Method method) {
        try {
            method.setAccessible(true);
            return MethodHandles.lookup().unreflect(method).bindTo(bean).asType(HANDLER_TYPE);
        } catch (IllegalAccessException ex) {
            throw new IllegalStateException(
                    "Cannot access @ExerisEventListener method "
                            + bean.getClass().getName() + "#" + method.getName(), ex);
        }
    }

    private static void invokeHandler(ListenerBinding binding, EventDescriptor descriptor, EventPayload payload) {
        try {
            binding.handle().invokeExact(descriptor, payload);
        } catch (RuntimeException | Error ex) {
            throw ex;
        } catch (Throwable ex) {
            throw new IllegalStateException(
                    "@ExerisEventListener invocation failed: "
                            + binding.bean().getClass().getName() + "#" + binding.method().getName(), ex);
        }
    }

    private record ListenerBinding(String eventTypeName, Object bean, Method method, MethodHandle handle) {
        ListenerBinding {
            Objects.requireNonNull(eventTypeName, "eventTypeName");
            Objects.requireNonNull(bean, "bean");
            Objects.requireNonNull(method, "method");
            Objects.requireNonNull(handle, "handle");
        }
    }
}
