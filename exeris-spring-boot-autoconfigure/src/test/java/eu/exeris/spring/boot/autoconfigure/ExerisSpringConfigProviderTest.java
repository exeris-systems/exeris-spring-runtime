/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.boot.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * Focused unit coverage for {@link ExerisSpringConfigProvider} — particularly the
 * {@code flow.*} kernel config key alias bridge (Phase 4B Step 4 closure) that lets
 * kernel-side {@code configProvider.getBoolean("flow.persistenceEnabled")} resolve
 * to the Spring property {@code exeris.runtime.flow.persistence-enabled}.
 *
 * <p>The cross-restart runtime IT in the flow module exercises the same code path
 * end-to-end against a live kernel, but only for {@code Boolean}. These unit cases
 * lock the alias logic itself: camelCase-first, kebab-case-fallback, the
 * {@link String}/{@link Integer}/{@link Long}/{@link Boolean} accessor symmetry, and
 * the {@code camelToKebab} conversion across all hump shapes the kernel might add.
 *
 * @since 0.5.0
 */
class ExerisSpringConfigProviderTest {

    // ===========================================================================
    // flowKernelKeyAlias — Boolean (the path the cross-restart IT exercises)
    // ===========================================================================

    @Test
    void flowKernelAlias_resolvesCamelCaseFormFromSpringProperty() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("exeris.runtime.flow.persistenceEnabled", "true");

        ExerisSpringConfigProvider provider = new ExerisSpringConfigProvider(env);

        assertThat(provider.getBoolean("flow.persistenceEnabled"))
                .as("Kernel asks for `flow.persistenceEnabled` (camelCase); alias must "
                        + "find it under `exeris.runtime.flow.persistenceEnabled`")
                .contains(true);
    }

    @Test
    void flowKernelAlias_resolvesKebabCaseFormFromSpringProperty() {
        // This is the Spring-idiomatic property form. MockEnvironment does NOT
        // automatically attach Spring Boot's ConfigurationPropertySources (which is
        // what enables relaxed binding via Environment.getProperty), so the alias
        // must explicitly try the kebab-cased fallback. This case is the regression
        // guard for that fallback.
        MockEnvironment env = new MockEnvironment()
                .withProperty("exeris.runtime.flow.persistence-enabled", "true");

        ExerisSpringConfigProvider provider = new ExerisSpringConfigProvider(env);

        assertThat(provider.getBoolean("flow.persistenceEnabled"))
                .as("Kernel asks for `flow.persistenceEnabled`; alias must fall back to "
                        + "the kebab-cased `exeris.runtime.flow.persistence-enabled` "
                        + "(MockEnvironment does not provide relaxed binding)")
                .contains(true);
    }

    @Test
    void flowKernelAlias_camelCaseWinsOverKebabWhenBothPresent() {
        // Pathological but deterministic: when both forms are set, the camelCase
        // (which exactly matches the kernel-side key shape) wins.
        MockEnvironment env = new MockEnvironment()
                .withProperty("exeris.runtime.flow.persistenceEnabled", "true")
                .withProperty("exeris.runtime.flow.persistence-enabled", "false");

        ExerisSpringConfigProvider provider = new ExerisSpringConfigProvider(env);

        assertThat(provider.getBoolean("flow.persistenceEnabled")).contains(true);
    }

    @Test
    void flowKernelAlias_returnsEmptyForNonFlowKey() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("exeris.runtime.events.persistence-enabled", "true");

        ExerisSpringConfigProvider provider = new ExerisSpringConfigProvider(env);

        // Non-flow.* kernel key — alias must not engage; lookup returns empty.
        assertThat(provider.getBoolean("events.persistenceEnabled")).isEmpty();
    }

    @Test
    void flowKernelAlias_returnsEmptyWhenNeitherFormSet() {
        MockEnvironment env = new MockEnvironment();

        ExerisSpringConfigProvider provider = new ExerisSpringConfigProvider(env);

        assertThat(provider.getBoolean("flow.persistenceEnabled")).isEmpty();
    }

    @Test
    void flowKernelAlias_directLookupBeatsAlias() {
        // If the kernel-side key is set directly (rare in normal use), it should
        // win over the Spring-namespaced alias. This preserves the existing
        // "direct first, alias second" precedence pattern used for the legacy HTTP
        // alias.
        MockEnvironment env = new MockEnvironment()
                .withProperty("flow.persistenceEnabled", "true")
                .withProperty("exeris.runtime.flow.persistenceEnabled", "false");

        ExerisSpringConfigProvider provider = new ExerisSpringConfigProvider(env);

        assertThat(provider.getBoolean("flow.persistenceEnabled")).contains(true);
    }

    // ===========================================================================
    // Typed-accessor symmetry — get/getString/getInt/getLong all consult the alias
    // ===========================================================================

    @Test
    void flowKernelAlias_appliesToGetString() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("exeris.runtime.flow.engineName", "Community/HeapFlow-test");

        ExerisSpringConfigProvider provider = new ExerisSpringConfigProvider(env);

        assertThat(provider.getString("flow.engineName")).contains("Community/HeapFlow-test");
    }

    @Test
    void flowKernelAlias_appliesToGetInt() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("exeris.runtime.flow.maxConcurrentFlows", "512");

        ExerisSpringConfigProvider provider = new ExerisSpringConfigProvider(env);

        assertThat(provider.getInt("flow.maxConcurrentFlows")).contains(512);
    }

    @Test
    void flowKernelAlias_appliesToGetLong() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("exeris.runtime.flow.timeoutDurationNanos", "10000000000");

        ExerisSpringConfigProvider provider = new ExerisSpringConfigProvider(env);

        assertThat(provider.getLong("flow.timeoutDurationNanos")).contains(10_000_000_000L);
    }

    @Test
    void flowKernelAlias_appliesToTypedGetForString() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("exeris.runtime.flow.engineName", "test-engine");

        ExerisSpringConfigProvider provider = new ExerisSpringConfigProvider(env);

        // This is the asymmetry the PR review flagged: get(key, String.class) must
        // try the flow alias after the (empty) legacy HTTP alias, not return empty.
        assertThat(provider.get("flow.engineName", String.class)).contains("test-engine");
    }

    @Test
    void flowKernelAlias_appliesToTypedGetForInteger() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("exeris.runtime.flow.maxConcurrentFlows", "256");

        ExerisSpringConfigProvider provider = new ExerisSpringConfigProvider(env);

        // Same asymmetry for Integer — get(key, Integer.class) must not stop at
        // the (empty) legacy HTTP int alias.
        assertThat(provider.get("flow.maxConcurrentFlows", Integer.class)).contains(256);
    }

    @Test
    void flowKernelAlias_appliesToTypedGetForBoolean() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("exeris.runtime.flow.persistenceEnabled", "true");

        ExerisSpringConfigProvider provider = new ExerisSpringConfigProvider(env);

        assertThat(provider.get("flow.persistenceEnabled", Boolean.class)).contains(true);
    }

    // ===========================================================================
    // camelToKebab — exercised indirectly through the kebab-case fallback path
    // ===========================================================================

    @Test
    void camelToKebab_handlesSingleHump() {
        // persistenceEnabled → persistence-enabled
        MockEnvironment env = new MockEnvironment()
                .withProperty("exeris.runtime.flow.persistence-enabled", "true");

        ExerisSpringConfigProvider provider = new ExerisSpringConfigProvider(env);

        assertThat(provider.getBoolean("flow.persistenceEnabled")).contains(true);
    }

    @Test
    void camelToKebab_handlesMultipleHumps() {
        // maxConcurrentFlows → max-concurrent-flows
        MockEnvironment env = new MockEnvironment()
                .withProperty("exeris.runtime.flow.max-concurrent-flows", "128");

        ExerisSpringConfigProvider provider = new ExerisSpringConfigProvider(env);

        assertThat(provider.getInt("flow.maxConcurrentFlows")).contains(128);
    }

    @Test
    void camelToKebab_handlesAllLowercaseUnchanged() {
        // suffix is already kebab-equivalent (no humps); both forms reduce to the
        // same string. The implementation guards against a redundant second lookup
        // via the `kebab.equals(suffix)` check.
        MockEnvironment env = new MockEnvironment()
                .withProperty("exeris.runtime.flow.enabled", "true");

        ExerisSpringConfigProvider provider = new ExerisSpringConfigProvider(env);

        assertThat(provider.getBoolean("flow.enabled")).contains(true);
    }

    // ===========================================================================
    // Null-environment safety — same shape the kernel constructs in fixture paths
    // ===========================================================================

    @Test
    void nullEnvironment_returnsEmptyForAllAccessorsIncludingFlowAlias() {
        ExerisSpringConfigProvider provider = new ExerisSpringConfigProvider(null);

        assertThat(provider.getString("flow.engineName")).isEmpty();
        assertThat(provider.getInt("flow.maxConcurrentFlows")).isEmpty();
        assertThat(provider.getLong("flow.timeoutDurationNanos")).isEmpty();
        assertThat(provider.getBoolean("flow.persistenceEnabled")).isEmpty();
        assertThat(provider.get("flow.persistenceEnabled", Boolean.class)).isEmpty();
    }
}
