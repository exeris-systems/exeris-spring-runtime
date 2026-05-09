/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.events;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import eu.exeris.kernel.spi.events.EventBus;
import eu.exeris.kernel.spi.events.EventDescriptor;
import eu.exeris.kernel.spi.events.EventEngine;
import eu.exeris.kernel.spi.events.EventHandler;
import eu.exeris.kernel.spi.events.EventPayload;
import eu.exeris.kernel.spi.events.SubscriptionToken;

class ExerisEventListenerRegistrarTest {

    @Test
    void scansAndSubscribesAnnotatedMethodsOnStart() {
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
        ctx.register(GoodListenerBean.class);
        ctx.refresh();

        EventBus bus = mock(EventBus.class);
        when(bus.subscribe(any(String.class), any(EventHandler.class)))
                .thenReturn(new SubscriptionToken(1, 1L));

        ExerisEventListenerRegistrar registrar = new ExerisEventListenerRegistrar(ctx, supplier(bus), strict());
        registrar.afterSingletonsInstantiated();
        assertThat(registrar.boundListenerCount()).isEqualTo(2);

        registrar.start();

        assertThat(registrar.activeSubscriptionCount()).isEqualTo(2);
        verify(bus, times(1)).subscribe(eq("payment.completed"), any(EventHandler.class));
        verify(bus, times(1)).subscribe(eq("payment.failed"), any(EventHandler.class));

        ctx.close();
    }

    @Test
    void invokesAnnotatedMethodWhenKernelDispatches() {
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
        ctx.register(GoodListenerBean.class);
        ctx.refresh();

        EventBus bus = mock(EventBus.class);
        ArgumentCaptor<EventHandler> handlerCaptor = ArgumentCaptor.forClass(EventHandler.class);
        when(bus.subscribe(eq("payment.completed"), handlerCaptor.capture()))
                .thenReturn(new SubscriptionToken(1, 1L));
        when(bus.subscribe(eq("payment.failed"), any(EventHandler.class)))
                .thenReturn(new SubscriptionToken(1, 2L));

        ExerisEventListenerRegistrar registrar = new ExerisEventListenerRegistrar(ctx, supplier(bus), strict());
        registrar.afterSingletonsInstantiated();
        registrar.start();

        EventDescriptor descriptor = EventDescriptor.of(1, 2, 3, 4, 5, 0, 1L);
        EventPayload payload = EventPayload.empty();
        handlerCaptor.getValue().handle(descriptor, payload);

        GoodListenerBean bean = ctx.getBean(GoodListenerBean.class);
        assertThat(bean.invocations.get()).isEqualTo(1);
        assertThat(bean.lastDescriptor.get()).isEqualTo(descriptor);

        ctx.close();
    }

    @Test
    void unsubscribesAllSubscriptionsOnStop() {
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
        ctx.register(GoodListenerBean.class);
        ctx.refresh();

        EventBus bus = mock(EventBus.class);
        when(bus.subscribe(any(String.class), any(EventHandler.class)))
                .thenReturn(new SubscriptionToken(1, 1L), new SubscriptionToken(1, 2L));

        ExerisEventListenerRegistrar registrar = new ExerisEventListenerRegistrar(ctx, supplier(bus), strict());
        registrar.afterSingletonsInstantiated();
        registrar.start();
        registrar.stop();

        verify(bus, times(2)).unsubscribe(any(SubscriptionToken.class));
        assertThat(registrar.activeSubscriptionCount()).isZero();
        assertThat(registrar.isRunning()).isFalse();

        ctx.close();
    }

    @Test
    void invalidSignatureFailsAtMetadataCollection() {
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
        ctx.register(BadListenerBean.class);
        ctx.refresh();

        ExerisEventListenerRegistrar registrar =
                new ExerisEventListenerRegistrar(ctx, supplier(mock(EventBus.class)), strict());

        assertThatThrownBy(registrar::afterSingletonsInstantiated)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must have signature");

        ctx.close();
    }

    @Test
    void engineUnavailableInTolerantModeSkipsSubscriptionsButTransitionsToRunning() {
        // Test harness / auto-start=false scenario with require-engine=false: the kernel
        // never bound an EventEngine. The registrar must still transition to running so
        // Spring's lifecycle stop() ordering is correct; subscriptions simply stay empty.
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
        ctx.register(GoodListenerBean.class);
        ctx.refresh();

        ExerisEventListenerRegistrar registrar = new ExerisEventListenerRegistrar(ctx, Optional::empty, tolerant());
        registrar.afterSingletonsInstantiated();
        registrar.start();

        assertThat(registrar.isRunning()).isTrue();
        assertThat(registrar.activeSubscriptionCount()).isZero();
        assertThat(registrar.boundListenerCount()).isEqualTo(2);

        ctx.close();
    }

    @Test
    void engineUnavailableInStrictModeFailsLoudWhenListenersDeclared() {
        // Production posture (default): require-engine=true. If listeners are declared
        // but the kernel did not bind an EventEngine, lifecycle start fails so the
        // operator sees the misconfiguration immediately rather than discovering
        // unfired handlers later.
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
        ctx.register(GoodListenerBean.class);
        ctx.refresh();

        ExerisEventListenerRegistrar registrar = new ExerisEventListenerRegistrar(ctx, Optional::empty, strict());
        registrar.afterSingletonsInstantiated();

        assertThatThrownBy(registrar::start)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no kernel EventEngine is available")
                .hasMessageContaining("require-engine=false");

        assertThat(registrar.isRunning()).isFalse();

        ctx.close();
    }

    @Test
    void engineUnavailableWithoutListenersIsAlwaysTolerated() {
        // No @ExerisEventListener methods declared: the registrar has nothing to wire,
        // so a missing engine is irrelevant for this bean even in strict mode. Hot
        // paths (publisher, type registry) still fail loud at first call.
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
        ctx.refresh();

        ExerisEventListenerRegistrar registrar = new ExerisEventListenerRegistrar(ctx, Optional::empty, strict());
        registrar.afterSingletonsInstantiated();
        registrar.start();

        assertThat(registrar.isRunning()).isTrue();
        assertThat(registrar.boundListenerCount()).isZero();

        ctx.close();
    }

    private static EventEngineSupplier supplier(EventBus bus) {
        EventEngine engine = mock(EventEngine.class);
        when(engine.bus()).thenReturn(bus);
        return () -> Optional.of(engine);
    }

    private static ExerisEventProperties strict() {
        return new ExerisEventProperties(true);
    }

    private static ExerisEventProperties tolerant() {
        return new ExerisEventProperties(false);
    }

    static class GoodListenerBean {
        final AtomicInteger invocations = new AtomicInteger();
        final AtomicReference<EventDescriptor> lastDescriptor = new AtomicReference<>();

        @ExerisEventListener(eventTypes = {"payment.completed"})
        public void onPaymentCompleted(EventDescriptor descriptor, EventPayload payload) {
            invocations.incrementAndGet();
            lastDescriptor.set(descriptor);
        }

        @ExerisEventListener(eventTypes = {"payment.failed"})
        public void onPaymentFailed(EventDescriptor descriptor, EventPayload payload) {
            invocations.incrementAndGet();
        }
    }

    static class BadListenerBean {
        @ExerisEventListener(eventTypes = {"foo"})
        public String wrongSignature(String wrong) {
            return wrong;
        }
    }
}
