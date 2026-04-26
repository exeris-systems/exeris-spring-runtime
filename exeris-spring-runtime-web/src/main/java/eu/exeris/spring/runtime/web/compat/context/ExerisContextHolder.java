/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.web.compat.context;

import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.persistence.PersistenceEngine;
import eu.exeris.kernel.spi.security.PrincipalContext;
import eu.exeris.kernel.spi.security.StorageContext;

import java.util.Optional;

/**
 * Safe accessor facade for {@link KernelProviders} ScopedValue slots.
 *
 * <p>All methods perform an {@code isBound()} check before reading the ScopedValue.
 * Callers receive {@link Optional#empty()} when the requested slot is not bound
 * (e.g., when called on a non-kernel thread or before kernel bootstrap).
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>MUST only be called from kernel-owned Virtual Threads (handler VTs) in production.</li>
 *   <li>MUST NOT be called during Spring context startup (slots are not yet bound).</li>
 *   <li>Never throws {@link java.util.NoSuchElementException}.</li>
 *   <li>No {@code ThreadLocal} — all reads are {@code ScopedValue} reads.</li>
 * </ul>
 *
 * <h2>Mode</h2>
 * <p>Compatibility Mode accessor for compat-package bridges that need safe,
 * optional reads of kernel-bound provider references from Spring-facing code.
 * This type is not a shared Pure Mode API and should remain confined to the
 * compatibility layer.
 *
 * @since 0.1.0
 */
public final class ExerisContextHolder {

    private ExerisContextHolder() {
        // utility — never instantiated
    }

    // =========================================================================
    // Persistence
    // =========================================================================

    /**
     * Returns {@code true} if {@link KernelProviders#PERSISTENCE_ENGINE} is bound
     * in the current ScopedValue scope.
     */
    public static boolean isPersistenceBound() {
        return KernelProviders.PERSISTENCE_ENGINE.isBound();
    }

    /**
     * Returns the active {@link PersistenceEngine}, or {@link Optional#empty()} if
     * the kernel persistence slot is not bound in the current scope.
     */
    public static Optional<PersistenceEngine> persistenceEngine() {
        return KernelProviders.PERSISTENCE_ENGINE.isBound()
                ? Optional.of(KernelProviders.PERSISTENCE_ENGINE.get())
                : Optional.empty();
    }

    // =========================================================================
    // Storage Context (tenant isolation)
    // =========================================================================

    /**
     * Returns {@code true} if {@link KernelProviders#STORAGE_CONTEXT} is bound
     * in the current ScopedValue scope.
     */
    public static boolean isStorageContextBound() {
        return KernelProviders.STORAGE_CONTEXT.isBound();
    }

    /**
     * Returns the active {@link StorageContext}, or {@link Optional#empty()} if
     * the storage context slot is not bound in the current scope.
     */
    public static Optional<StorageContext> storageContext() {
        return KernelProviders.STORAGE_CONTEXT.isBound()
                ? Optional.of(KernelProviders.STORAGE_CONTEXT.get())
                : Optional.empty();
    }

    // =========================================================================
    // Principal Context (authentication)
    // =========================================================================

    /**
     * Returns {@code true} if {@link KernelProviders#PRINCIPAL_CONTEXT} is bound
     * in the current ScopedValue scope.
     */
    public static boolean isPrincipalBound() {
        return KernelProviders.PRINCIPAL_CONTEXT.isBound();
    }

    /**
     * Returns the active {@link PrincipalContext}, or {@link Optional#empty()} if
     * the principal context slot is not bound in the current scope.
     */
    public static Optional<PrincipalContext> principal() {
        return KernelProviders.PRINCIPAL_CONTEXT.isBound()
                ? Optional.of(KernelProviders.PRINCIPAL_CONTEXT.get())
                : Optional.empty();
    }
}
