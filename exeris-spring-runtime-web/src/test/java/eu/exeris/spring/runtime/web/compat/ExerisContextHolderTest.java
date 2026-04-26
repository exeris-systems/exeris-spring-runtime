/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.web.compat;

import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.persistence.ConnectionInterceptor;
import eu.exeris.kernel.spi.persistence.EngineStats;
import eu.exeris.kernel.spi.persistence.PersistenceConnection;
import eu.exeris.kernel.spi.persistence.PersistenceEngine;
import eu.exeris.kernel.spi.persistence.PersistenceHealthStatus;
import eu.exeris.kernel.spi.security.ImmutablePrincipal;
import eu.exeris.kernel.spi.security.ImmutableStorageContext;
import eu.exeris.kernel.spi.security.StorageContext;
import eu.exeris.spring.runtime.web.compat.context.ExerisContextHolder;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ExerisContextHolder}.
 */
class ExerisContextHolderTest {

    // =========================================================================
    // Unbound cases — called outside kernel VT scope
    // =========================================================================

    @Test
    void isPersistenceBound_whenUnbound_returnsFalse() {
        assertThat(ExerisContextHolder.isPersistenceBound()).isFalse();
    }

    @Test
    void persistenceEngine_whenUnbound_returnsEmpty() {
        assertThat(ExerisContextHolder.persistenceEngine()).isEmpty();
    }

    @Test
    void isStorageContextBound_whenUnbound_returnsFalse() {
        assertThat(ExerisContextHolder.isStorageContextBound()).isFalse();
    }

    @Test
    void storageContext_whenUnbound_returnsEmpty() {
        assertThat(ExerisContextHolder.storageContext()).isEmpty();
    }

    @Test
    void isPrincipalBound_whenUnbound_returnsFalse() {
        assertThat(ExerisContextHolder.isPrincipalBound()).isFalse();
    }

    @Test
    void principal_whenUnbound_returnsEmpty() {
        assertThat(ExerisContextHolder.principal()).isEmpty();
    }

    // =========================================================================
    // Bound cases — ScopedValue.where().run() simulates kernel VT scope
    // =========================================================================

    @Test
    void storageContext_whenBound_returnsValue() {
        StorageContext ctx = ImmutableStorageContext.GLOBAL;
        ScopedValue.where(KernelProviders.STORAGE_CONTEXT, ctx).run(() -> {
            assertThat(ExerisContextHolder.isStorageContextBound()).isTrue();
            assertThat(ExerisContextHolder.storageContext()).contains(ctx);
        });
    }

    @Test
    void principal_whenBound_returnsValue() {
        ImmutablePrincipal principal = ImmutablePrincipal.system(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                Set.of("ROLE_SYSTEM"));
        ScopedValue.where(KernelProviders.PRINCIPAL_CONTEXT, principal).run(() -> {
            assertThat(ExerisContextHolder.isPrincipalBound()).isTrue();
            assertThat(ExerisContextHolder.principal()).contains(principal);
        });
    }

    @Test
    void persistenceEngine_whenBound_returnsValue() {
        PersistenceEngine stubEngine = new StubPersistenceEngine();
        ScopedValue.where(KernelProviders.PERSISTENCE_ENGINE, stubEngine).run(() -> {
            assertThat(ExerisContextHolder.isPersistenceBound()).isTrue();
            assertThat(ExerisContextHolder.persistenceEngine()).containsSame(stubEngine);
        });
    }

    @Test
    void afterScopedValueScope_isUnboundAgain() {
        // Confirm that isBound() returns false again after exiting ScopedValue scope
        ScopedValue.where(KernelProviders.STORAGE_CONTEXT, ImmutableStorageContext.GLOBAL)
                .run(() -> { /* enter scope */ });
        assertThat(ExerisContextHolder.isStorageContextBound()).isFalse();
    }

    // =========================================================================
    // Minimal stub — identity only, all operations unsupported
    // =========================================================================

    private static final class StubPersistenceEngine implements PersistenceEngine {
        @Override
        public PersistenceConnection openConnection() {
            throw new UnsupportedOperationException("stub");
        }
        @Override
        public PersistenceConnection openConnection(StorageContext storageContext) {
            throw new UnsupportedOperationException("stub");
        }
        @Override
        public PersistenceHealthStatus healthCheckDetailed() {
            throw new UnsupportedOperationException("stub");
        }
    
        @Override
        public void registerInterceptor(ConnectionInterceptor interceptor) {
            throw new UnsupportedOperationException("stub");
        }
        @Override
        public EngineStats stats() {
            throw new UnsupportedOperationException("stub");
        }
        @Override
        public boolean canServiceRequest() {
            throw new UnsupportedOperationException("stub");
        }
        @Override
        public void close() {
            // no-op
        }
    }
}
