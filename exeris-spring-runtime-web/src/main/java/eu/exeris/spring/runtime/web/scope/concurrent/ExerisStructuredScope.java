/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.web.scope.concurrent;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.StructuredTaskScope.Joiner;
import java.util.concurrent.StructuredTaskScope.Subtask;

import eu.exeris.spring.runtime.web.scope.ExerisRequestScope;
import eu.exeris.spring.runtime.web.scope.RequestScope;

/**
 * Wrapper around JDK 26 {@link StructuredTaskScope} (JEP 525) that transparently propagates the
 * bound {@link RequestScope} across every forked virtual thread, per ADR-029 (Phase 3B-α).
 *
 * <h2>API adaptation note (vs ADR-029 listing)</h2>
 * <p>ADR-029 §"API at a glance" was drafted mirroring an earlier preview of JDK
 * {@code StructuredTaskScope} (the deprecated nested {@code ShutdownOnFailure}/
 * {@code ShutdownOnSuccess} class hierarchy). JDK 26 finalised the API on the
 * {@code open(Joiner)} factory pattern; the nested-class spelling was removed. This wrapper
 * mirrors the finalised API while preserving ADR-029's intent — keep a Spring-side surface that
 * shields callers from JDK preview-API iteration. The two factory methods
 * ({@link #failFast()} and {@link #firstSuccess()}) cover the two policy semantics ADR-029
 * named ({@code ShutdownOnFailure} and {@code ShutdownOnSuccess} respectively); a follow-up
 * ADR-029 amendment will align the docs.
 *
 * <h2>Result types per policy</h2>
 * <ul>
 *   <li>{@link #failFast()} — uses {@link Joiner#awaitAllSuccessfulOrThrow()}. {@code join()}
 *       returns {@link Void} (callers read individual subtask outcomes from their retained
 *       {@link Subtask} handles); any subtask failure throws from {@code join()}.</li>
 *   <li>{@link #firstSuccess()} — uses {@link Joiner#anySuccessfulOrThrow()}. {@code join()}
 *       returns the value of the first successful fork; if all fail, throws with the last
 *       failure as cause.</li>
 * </ul>
 *
 * <h2>Disabled-path semantics</h2>
 * <p>If no {@link RequestScope} is bound at construction time (the dispatcher property is
 * disabled, or the code runs outside an Exeris HTTP request path), the wrapper forwards forks
 * without rebinding {@link ExerisRequestScope#carrier()}. No allocation overhead in that case.
 *
 * @param <T> the type produced by each forked subtask
 * @param <R> the result type produced by {@link #join()} per the policy
 * @since 0.6.0
 */
public final class ExerisStructuredScope<T, R> implements AutoCloseable {

    private final StructuredTaskScope<T, R> delegate;
    private final RequestScope captured;

    private ExerisStructuredScope(StructuredTaskScope<T, R> delegate) {
        this.delegate = delegate;
        // Capture at construction; subsequent forks rebind to this snapshot. May be null if the
        // outer scope is unbound — in which case rebinding is a no-op (no allocation).
        this.captured = ExerisRequestScope.current().orElse(null);
    }

    /**
     * Failure-policy: await all subtasks; on first failure, all others are interrupted and the
     * failure is thrown from {@link #join()}. ADR-029 calls this the "{@code ShutdownOnFailure}"
     * policy; the name {@code failFast()} maps to JDK 26
     * {@link Joiner#awaitAllSuccessfulOrThrow()} semantics.
     */
    public static <T> ExerisStructuredScope<T, Void> failFast() {
        return new ExerisStructuredScope<>(
                StructuredTaskScope.open(Joiner.<T>awaitAllSuccessfulOrThrow()));
    }

    /**
     * Success-policy: as soon as any subtask succeeds, all others are interrupted and that
     * value is returned from {@link #join()}. If all subtasks fail, {@code join()} throws.
     * ADR-029 calls this the "{@code ShutdownOnSuccess}" policy; the name {@code firstSuccess()}
     * maps to JDK 26 {@link Joiner#anySuccessfulOrThrow()} semantics.
     */
    public static <T> ExerisStructuredScope<T, T> firstSuccess() {
        return new ExerisStructuredScope<>(
                StructuredTaskScope.open(Joiner.<T>anySuccessfulOrThrow()));
    }

    /**
     * All-success policy: await all subtasks; if any fails, {@link #join()} throws. If all
     * succeed, {@link #join()} returns the {@link List} of their result values (not the
     * subtask handles).
     *
     * <p>This is the strictest of the three policies — there is no graceful partial-failure
     * path here. Use {@link #failFast()} when result-value aggregation isn't needed; reach for
     * a hand-rolled {@link Joiner} on top of {@code StructuredTaskScope.open(...)} if you need
     * true partial-success aggregation.
     */
    public static <T> ExerisStructuredScope<T, List<T>> allSuccessful() {
        return new ExerisStructuredScope<>(
                StructuredTaskScope.open(Joiner.<T>allSuccessfulOrThrow()));
    }

    /**
     * Fork a subtask. The {@code task} is wrapped so that {@link ExerisRequestScope}'s typed
     * accessors return the same values inside the forked virtual thread as in the enclosing
     * scope at construction time — no manual rebinding required at the call site.
     *
     * <p>The returned {@link Subtask} is the JDK type by design: wrapping it would add a
     * per-fork allocation without semantic value.
     */
    public <U extends T> Subtask<U> fork(Callable<? extends U> task) {
        Objects.requireNonNull(task, "task");
        return delegate.fork(rebinding(task));
    }

    /**
     * Wait for the policy to complete (all-success for {@link #failFast()},
     * first-success for {@link #firstSuccess()}). Returns the {@code R} value per the policy.
     */
    public R join() throws InterruptedException {
        return delegate.join();
    }

    @Override
    public void close() {
        delegate.close();
    }

    /**
     * Diagnostic accessor: the {@link RequestScope} captured at construction, if any.
     */
    public Optional<RequestScope> capturedScope() {
        return Optional.ofNullable(captured);
    }

    // The {@code (U) task.call()} casts below are unchecked because {@code Callable<? extends U>}
    // erases its bound; the cast is safe because {@code ? extends U} is assignable to {@code U}.
    @SuppressWarnings("unchecked")
    private <U> Callable<U> rebinding(Callable<? extends U> task) {
        if (captured == null) {
            return () -> (U) task.call();
        }
        return () -> ExerisRequestScope.callWith(captured, () -> (U) task.call());
    }
}
