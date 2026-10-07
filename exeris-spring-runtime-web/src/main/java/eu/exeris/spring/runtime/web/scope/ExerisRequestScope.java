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
 * Static facade for {@code ScopedValue<RequestScope>}-backed request-scoped state.
 *
 * <p>The {@code ScopedValue} carrier itself is managed statically inside this class; callers bind via
 * {@link #runWith(RequestScope, Runnable)} / {@link #callWith(RequestScope, ScopedValue.CallableOp)}
 * and read via the typed accessor methods. The dispatcher binds the scope around
 * {@code HttpHandler.handle} invocations when {@code exeris.runtime.context.scope.enabled=true};
 * structured concurrency scopes inherit bindings across forks.
 *
 * <p><b>Concurrency:</b> This class rebinds explicitly and carries no fan-out helper. Bindings
 * reach a {@code StructuredTaskScope} fork on their own, but not a plain virtual thread, which
 * observes nothing bound. Off the request thread, rebind with
 * {@link #runWith(RequestScope, Runnable)} / {@link #callWith(RequestScope, ScopedValue.CallableOp)}.
 *
 * @implSpec {@code ScopedValue} is the only carrier. Per the runtime ownership model and architecture
 *           guard {@code RequestScopeArchitectureTest#scopePackageMustNotUseThreadLocal}, this package
 *           does not use {@code ThreadLocal} as a carrier.
 * @since 0.6
 * @see "ADR-029: Kernel-Independent Request Scope and Structured Concurrency Helpers"
 * @see RequestScope
 * @see RequestScopeResolver
 */
public final class ExerisRequestScope {

    private static final ScopedValue<RequestScope> SCOPE = ScopedValue.newInstance();

    private ExerisRequestScope() {
        // utility class
    }

    // ---------------------------------------------------------------- read API

    /**
     * Returns the currently bound {@link RequestScope}, if bound.
     *
     * @return current request scope if bound, otherwise empty
     */
    public static Optional<RequestScope> current() {
        return SCOPE.isBound() ? Optional.of(SCOPE.get()) : Optional.empty();
    }

    /**
     * Returns the tenant ID of the currently bound request scope, if present.
     *
     * @return tenant ID if a scope is bound and has a tenant, otherwise empty
     */
    public static Optional<UUID> tenantId() {
        return current().map(RequestScope::tenantId);
    }

    /**
     * Returns the correlation ID of the currently bound request scope, if present.
     *
     * @return correlation ID if a scope is bound, otherwise empty
     */
    public static Optional<String> correlationId() {
        return current().map(RequestScope::correlationId);
    }

    /**
     * Returns a typed attribute from the currently bound request scope, if present.
     *
     * @param <T> attribute type
     * @param key attribute key
     * @param type expected attribute type class
     * @return attribute value if present and matching type, otherwise empty
     */
    public static <T> Optional<T> attribute(String key, Class<T> type) {
        return current().flatMap(scope -> scope.attribute(key, type));
    }

    /**
     * Returns the tenant ID of the currently bound request scope, throwing if absent.
     *
     * @return bound tenant ID
     * @throws IllegalStateException if called outside a bound scope or with no tenant
     */
    public static UUID requireTenantId() {
        return tenantId().orElseThrow(() -> new IllegalStateException(
                "ExerisRequestScope.requireTenantId() called outside a bound request scope or "
                        + "with no tenant. Either bind a RequestScope via the dispatcher "
                        + "(exeris.runtime.context.scope.enabled=true + RequestScopeResolver bean) "
                        + "or use tenantId() for the Optional-returning variant."));
    }

    /**
     * Returns the correlation ID of the currently bound request scope, throwing if absent.
     *
     * @return bound correlation ID
     * @throws IllegalStateException if called outside a bound scope or with no correlation ID
     */
    public static String requireCorrelationId() {
        return correlationId().orElseThrow(() -> new IllegalStateException(
                "ExerisRequestScope.requireCorrelationId() called outside a bound request scope "
                        + "or with no correlation ID."));
    }

    // ---------------------------------------------------------------- bind API

    /**
     * Runs {@code action} with {@code scope} bound as the current {@link RequestScope}.
     *
     * @param scope the request scope to bind
     * @param action the action to execute
     */
    public static void runWith(RequestScope scope, Runnable action) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(action, "action");
        ScopedValue.where(SCOPE, scope).run(action);
    }

    /**
     * Calls {@code action} with {@code scope} bound as the current {@link RequestScope}.
     *
     * @param <T> the return type
     * @param <X> the exception type
     * @param scope the request scope to bind
     * @param action the action to execute
     * @return the result of executing {@code action}
     * @throws X if the action throws an exception
     * @implNote Uses the JDK 25 GA LTS {@link ScopedValue.CallableOp} type (JEP 525).
     */
    public static <T, X extends Throwable> T callWith(RequestScope scope,
                                                       ScopedValue.CallableOp<T, X> action) throws X {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(action, "action");
        return ScopedValue.where(SCOPE, scope).call(action);
    }

}
