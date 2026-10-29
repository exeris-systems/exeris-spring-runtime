/*
 * Copyright (C) 2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.spring.runtime.web.test;

import eu.exeris.kernel.community.security.CommunitySecurityProvider;
import eu.exeris.kernel.community.testkit.security.TestJwt;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.security.AuthenticationResult;
import eu.exeris.kernel.spi.security.SecurityProvider;
import eu.exeris.kernel.spi.security.StorageContext;

/**
 * Kernel {@link SecurityProvider} that trusts the kernel testkit's {@link TestJwt} signing key, so a
 * test booting the real kernel security subsystem can present a token the edge will verify.
 *
 * <p>The Community provider the kernel discovers by default verifies against an empty key set and
 * therefore denies every token; there is no configuration key that hands it a key set or a JWKS
 * endpoint. This provider outranks it by priority and delegates to the same Community verification
 * pipeline — {@link CommunitySecurityProvider} with the Community claims mapper — constructed over
 * {@link TestJwt#keySet()}, {@link TestJwt#EXPECTED_ISSUER} and {@link TestJwt#EXPECTED_AUDIENCE}.
 * Signature, issuer, audience and expiry checks are therefore the kernel's own, not re-implemented here.
 *
 * <p>Registered through {@code META-INF/services}, so every kernel boot in this module's tests that
 * starts the {@code security} subsystem selects it. A token not signed by the testkit key is denied
 * exactly as the default provider would deny it.
 */
public final class TestJwtSecurityProvider implements SecurityProvider {

    private final SecurityProvider delegate = new CommunitySecurityProvider(
            TestJwt.keySet(), TestJwt.EXPECTED_ISSUER, TestJwt.EXPECTED_AUDIENCE);

    @Override
    public String providerId() {
        return "jwt-testkit";
    }

    @Override
    public String providerName() {
        return "ExerisSpringRuntimeTest/TestJwt";
    }

    /** Above the Community provider's {@code 0}, so discovery selects this one. */
    @Override
    public int priority() {
        return 50;
    }

    @Override
    public AuthenticationResult authenticate(LoanedBuffer rawToken) {
        return delegate.authenticate(rawToken);
    }

    @Override
    public StorageContext systemStorageContext() {
        return delegate.systemStorageContext();
    }
}
