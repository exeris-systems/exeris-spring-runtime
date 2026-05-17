/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.graph;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Phase 4C graph-bridge properties (ADR-030).
 *
 * <p>Two-property activation matrix mirroring Phase 4A / 4B autoconfig discipline:
 *
 * <ul>
 *   <li>{@code enabled} (default {@code false}) — explicit opt-in for the bridge. The
 *       autoconfig's {@code @ConditionalOnProperty} is gated on this; applications that
 *       do not opt in pay zero cost.</li>
 *   <li>{@code requireEngine} (default {@code true}) — when the autoconfig activates and
 *       the kernel did not provide a {@link eu.exeris.kernel.spi.graph.GraphEngine} (no
 *       {@code GraphProvider} on the classpath, or the kernel scope never opened), the
 *       template fails loud at first use. Set to {@code false} only in dev/test
 *       environments where the kernel graph provider is intentionally absent — the
 *       template stays constructed but every method throws {@code IllegalStateException}
 *       until an engine becomes available.</li>
 * </ul>
 *
 * <p>The two-property split distinguishes three operator states:
 *
 * <table>
 *   <caption>Activation state matrix</caption>
 *   <tr><th>{@code enabled}</th><th>{@code requireEngine}</th><th>{@code GraphEngine} bound?</th><th>State</th></tr>
 *   <tr><td>{@code false}</td><td>—</td><td>—</td><td>Feature unused (default)</td></tr>
 *   <tr><td>{@code true}</td><td>{@code true}</td><td>yes</td><td>Feature active</td></tr>
 *   <tr><td>{@code true}</td><td>{@code true}</td><td>no</td><td>Fail loud at first use (recommended for prod)</td></tr>
 *   <tr><td>{@code true}</td><td>{@code false}</td><td>no</td><td>Template constructed but unusable (dev/test only)</td></tr>
 * </table>
 *
 * @since 0.7.0
 * @see <a href="../../../../../../../../../docs/adr/ADR-030-phase-4c-spring-side-seam-for-kernel-graph-spi.md">ADR-030</a>
 */
@ConfigurationProperties(prefix = "exeris.runtime.graph")
public record ExerisGraphProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("true") boolean requireEngine) {
}
