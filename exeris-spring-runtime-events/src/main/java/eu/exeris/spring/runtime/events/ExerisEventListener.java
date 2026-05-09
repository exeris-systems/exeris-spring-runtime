/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.events;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method on a Spring bean as a subscriber to Exeris kernel events.
 *
 * <p>The annotated method MUST have the exact signature
 * {@code void method(eu.exeris.kernel.spi.events.EventDescriptor descriptor,
 * eu.exeris.kernel.spi.events.EventPayload payload)}. {@link ExerisEventListenerRegistrar}
 * enforces this at startup.
 *
 * <h2>Payload Ownership</h2>
 * <p>The {@code EventPayload} passed to handler methods is owned by the kernel dispatch
 * loop. Handlers MUST NOT call {@code close()} on the payload and MUST NOT retain a
 * reference past the method return — the kernel may release backing memory immediately
 * after the handler completes. If a handler needs to consume the payload asynchronously
 * it must call {@code payload.retain()} and arrange a matching {@code close()}.
 *
 * <h2>Spring Event Boundary</h2>
 * <p>This annotation is intentionally distinct from
 * {@code org.springframework.context.event.EventListener}. Spring application events and
 * Exeris kernel events are two separate buses by design — wiring them together would
 * invert ownership of the kernel event path.
 *
 * @since 0.1.0
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ExerisEventListener {

    /**
     * Event type names the annotated method subscribes to. Each name must be registered
     * in the kernel {@code EventRegistry} before the listener registrar runs; unknown
     * names fail fast at startup.
     */
    String[] eventTypes();
}
