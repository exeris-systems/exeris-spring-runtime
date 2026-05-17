/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.graph;

import eu.exeris.kernel.spi.graph.GraphSession;

/**
 * Callback shape for {@link ExerisGraphTemplate#execute(ExerisGraphSessionCallback)}.
 *
 * <p>Custom {@code @FunctionalInterface} (not {@code java.util.function.Function}) per ADR-030
 * obligation 3: {@code withSession(GraphSession)} declares {@code throws Exception} so
 * application code can propagate kernel {@code RuntimeException}s without unchecked-cast
 * boilerplate at every call site while still accommodating any future checked-exception
 * introduction in the kernel SPI.
 *
 * <p>Kernel-side notes:
 * <ul>
 *   <li>{@code GraphSession.beginTransaction()} / {@code commit()} / {@code rollback()} declare
 *       only {@code GraphQueryException} (which extends {@code RuntimeException}), so for the
 *       Step 2/3 SPI surface the {@code throws Exception} on this callback is forward-looking
 *       headroom, not a current necessity.</li>
 *   <li>The template's {@code execute(...)} catches application exceptions, closes the session
 *       in a {@code finally} block, and re-throws — see the template's Javadoc for the precise
 *       contract.</li>
 * </ul>
 *
 * @param <T> the result type the callback produces
 * @since 0.7.0
 */
@FunctionalInterface
public interface ExerisGraphSessionCallback<T> {

    T withSession(GraphSession session) throws Exception;
}
