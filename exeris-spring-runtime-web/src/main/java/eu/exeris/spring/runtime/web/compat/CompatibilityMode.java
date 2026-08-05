/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.web.compat;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a type or method as part of the Spring Compatibility Mode bridge.
 *
 * <p>This is a discoverability marker — applied to top-level entry classes of the
 * compatibility surface so that reviewers, IDEs, and tooling can identify the opt-in
 * Compat path at a glance. It carries no runtime semantics.
 *
 * <p>The architectural isolation invariants of Compatibility Mode are enforced by
 * {@code CompatibilityIsolationGuardTest} and the {@code *.compat.*} package
 * convention — not by this annotation. The annotation exists to satisfy ADR-011's
 * obligation to flag compat-mode features explicitly, complementing (not replacing)
 * the package and guard-test enforcement.
 *
 * <p><b>Apply to every type in a {@code *.compat.*} package</b>, entry points and inner
 * mechanics alike — dispatcher, MVC bridge, argument resolvers, return-value handlers,
 * filters, events, exceptions. ADR-011 states the benefit this buys: "a grep for
 * {@code @CompatibilityMode} shows the full surface of compat-only behaviour". A partial
 * application defeats exactly that, because the grep then under-reports and the reader
 * cannot tell an unmarked compat class from a pure-mode one without checking its package.
 * {@code CompatibilityIsolationGuardTest#everyCompatClass_carriesTheCompatibilityModeMarker}
 * enforces it.
 *
 * <p>This Javadoc previously said the opposite — that inner mechanics "need not be marked
 * individually" — and practice followed the Javadoc: 3 of 26 compat classes carried the
 * marker. That was drift from the accepted decision, not a narrower convention, and it is
 * corrected here.
 *
 * @see <a href="../../../../../../../docs/adr/ADR-011-pure-mode-vs-compatibility-mode.md">ADR-011 — Pure Mode vs Compatibility Mode</a>
 * @since 0.1.0
 */
@Target({ ElementType.TYPE, ElementType.METHOD })
@Retention(RetentionPolicy.CLASS)
public @interface CompatibilityMode {
}
