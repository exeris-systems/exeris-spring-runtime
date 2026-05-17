/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.web.scope;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Static facade for {@code ScopedValue<RequestScope>}-backed request-scoped state, per ADR-029
 * (Phase 3B-α — kernel-independent request scope and structured concurrency helpers).
 *
 * <p>The {@code ScopedValue} carrier itself is {@code static final} (package-private exposure
 * via {@link #carrier()} for the structured-concurrency wrapper) — callers bind via
 * {@link #runWith(RequestScope, Runnable)} / {@link #callWith(RequestScope, ScopedValue.CallableOp)}
 * and read via the typed accessor methods. The dispatcher binds the scope around
 * {@code HttpHandler.handle} invocations when {@code exeris.runtime.context.scope.enabled=true};
 * the structured-concurrency wrapper rebinds inside each fork.
 *
 * <h2>No {@code ThreadLocal}</h2>
 * <p>Per the {@code CLAUDE.md} §"Pure Mode vs Compatibility Mode" narrative ban on
 * {@code ThreadLocal} on hot paths and the architecture guard
 * {@code RequestScopeArchitectureTest#scopePackageMustNotUseThreadLocal}, this package
 * does not use {@code ThreadLocal} as a carrier. {@code ScopedValue} is the only carrier.
 *
 * @since 0.6.0
 * @see RequestScope
 * @see RequestScopeResolver
 * @see eu.exeris.spring.runtime.web.scope.concurrent.ExerisStructuredScope
 */
public final class ExerisRequestScope {

    private static final ScopedValue<RequestScope> SCOPE = ScopedValue.newInstance();

    private ExerisRequestScope() {
        // utility class
    }

    // ---------------------------------------------------------------- read API

    public static Optional<RequestScope> current() {
        return SCOPE.isBound() ? Optional.of(SCOPE.get()) : Optional.empty();
    }

    public static Optional<UUID> tenantId() {
        return current().map(RequestScope::tenantId);
    }

    public static Optional<String> correlationId() {
        return current().map(RequestScope::correlationId);
    }

    public static <T> Optional<T> attribute(String key, Class<T> type) {
        return current().flatMap(scope -> scope.attribute(key, type));
    }

    public static UUID requireTenantId() {
        return tenantId().orElseThrow(() -> new IllegalStateException(
                "ExerisRequestScope.requireTenantId() called outside a bound request scope or "
                        + "with no tenant. Either bind a RequestScope via the dispatcher "
                        + "(exeris.runtime.context.scope.enabled=true + RequestScopeResolver bean) "
                        + "or use tenantId() for the Optional-returning variant."));
    }

    public static String requireCorrelationId() {
        return correlationId().orElseThrow(() -> new IllegalStateException(
                "ExerisRequestScope.requireCorrelationId() called outside a bound request scope "
                        + "or with no correlation ID."));
    }

    // ---------------------------------------------------------------- bind API

    /**
     * Run {@code action} with {@code scope} bound as the current {@link RequestScope}. Used by
     * the dispatcher around {@code HttpHandler.handle} invocations.
     */
    public static void runWith(RequestScope scope, Runnable action) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(action, "action");
        ScopedValue.where(SCOPE, scope).run(action);
    }

    /**
     * Call {@code action} with {@code scope} bound as the current {@link RequestScope}. Uses the
     * JDK 26 {@link ScopedValue.CallableOp} type (the {@code Callable} overload was removed in
     * the JEP 525 finalisation).
     */
    public static <T, X extends Throwable> T callWith(RequestScope scope,
                                                       ScopedValue.CallableOp<T, X> action) throws X {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(action, "action");
        return ScopedValue.where(SCOPE, scope).call(action);
    }

    /**
     * Public accessor for the {@code ScopedValue} carrier, intended for use by the
     * structured-concurrency sub-package
     * ({@code eu.exeris.spring.runtime.web.scope.concurrent}) — which lives in a sibling
     * package and would otherwise need reflection to bridge the carrier across the package
     * boundary. The method is necessarily {@code public} because Java does not propagate
     * package-private visibility to sub-packages.
     *
     * <p>Production application code should use the typed accessor methods on this class
     * ({@link #current()}, {@link #tenantId()}, {@link #correlationId()},
     * {@link #attribute(String, Class)}) rather than reading the carrier directly. Reaching
     * for the carrier is an internal-wiring concern; if you find yourself calling
     * {@code carrier()} from application code, the typed surface is missing an accessor —
     * file an issue rather than working around it.
     */
    public static ScopedValue<RequestScope> carrier() {
        return SCOPE;
    }
}
