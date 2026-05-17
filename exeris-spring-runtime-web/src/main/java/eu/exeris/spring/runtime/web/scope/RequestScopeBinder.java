/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.web.scope;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

import eu.exeris.spring.runtime.web.ExerisServerRequest;

/**
 * Bridge between {@link ExerisServerRequest} ingress and {@link ExerisRequestScope} binding.
 * Inject one instance into the HTTP dispatcher; the dispatcher calls
 * {@link #bind(ExerisServerRequest, Runnable)} once per request, and the binder either
 * resolves a {@link RequestScope} via the supplied {@link RequestScopeResolver} and wraps the
 * action in {@link ExerisRequestScope#runWith(RequestScope, Runnable)}, or — when the property
 * is disabled — just runs the action without binding.
 *
 * <p>This indirection keeps the dispatcher itself decoupled from the scope subsystem: the
 * dispatcher sees a single {@link RequestScopeBinder} parameter (always non-null) and never
 * touches {@code ScopedValue} directly. The disabled-path implementation is the JIT-friendly
 * {@link #noop()} which inlines to a direct {@code action.run()} call.
 *
 * @since 0.6.0
 */
@FunctionalInterface
public interface RequestScopeBinder {

    /**
     * Run {@code action} either inside an active {@link RequestScope} binding (when enabled
     * and a resolver is available) or directly (the disabled path).
     */
    void bind(ExerisServerRequest request, Runnable action);

    /**
     * Pass-through binder: no scope binding, zero allocation. The default for the disabled
     * property path and the test path.
     */
    static RequestScopeBinder noop() {
        return (request, action) -> action.run();
    }

    /**
     * Resolving binder: builds a {@link RequestScope} from the request via {@code resolver},
     * then runs {@code action} inside {@link ExerisRequestScope#runWith(RequestScope, Runnable)}.
     * If the resolver returns {@code null} (its contract forbids this), falls back to
     * {@link RequestScope#empty()} and logs a WARN exactly once so the bug surfaces in operator
     * logs without flooding them.
     */
    static RequestScopeBinder resolving(RequestScopeResolver resolver) {
        Objects.requireNonNull(resolver, "resolver");
        AtomicBoolean nullReturnWarned = new AtomicBoolean(false);
        System.Logger logger = System.getLogger(RequestScopeBinder.class.getName());
        return (request, action) -> {
            Objects.requireNonNull(request, "request");
            Objects.requireNonNull(action, "action");
            RequestScope scope = resolver.resolve(request);
            if (scope == null) {
                if (nullReturnWarned.compareAndSet(false, true)) {
                    logger.log(System.Logger.Level.WARNING,
                            "RequestScopeResolver " + resolver.getClass().getName()
                                    + " returned null from resolve(...); its contract forbids this. "
                                    + "Falling back to RequestScope.empty() and proceeding. "
                                    + "Subsequent null returns from this resolver will be silently substituted "
                                    + "(WARN logged once per JVM to avoid flooding operator logs).");
                }
                scope = RequestScope.empty();
            }
            ExerisRequestScope.runWith(scope, action);
        };
    }
}
