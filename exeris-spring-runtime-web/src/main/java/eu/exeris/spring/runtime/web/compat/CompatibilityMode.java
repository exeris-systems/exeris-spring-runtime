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
 * <p>Apply to top-level Compat entry classes: dispatcher, MVC bridge, autoconfiguration.
 * Inner mechanics (argument resolvers, return-value handlers, filters) inherit their
 * Compat status from their package location and need not be marked individually.
 *
 * @see <a href="../../../../../../../docs/adr/ADR-011-pure-mode-vs-compatibility-mode.md">ADR-011 — Pure Mode vs Compatibility Mode</a>
 * @since 0.1.0
 */
@Target({ ElementType.TYPE, ElementType.METHOD })
@Retention(RetentionPolicy.CLASS)
public @interface CompatibilityMode {
}
