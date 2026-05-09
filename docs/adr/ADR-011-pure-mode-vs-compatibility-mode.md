# ADR-011: Pure Mode vs Compatibility Mode

| Attribute       | Value                                                                                                            |
|:----------------|:-----------------------------------------------------------------------------------------------------------------|
| **Status**      | **PROPOSED** (drafted 2026-05-08)                                                                                |
| **Deciders**    | Arkadiusz Przychocki                                                                                             |
| **Date**        | 2026-05-08                                                                                                       |
| **Scope**       | spring (binds every `exeris-spring-runtime-*` module)                                                            |
| **Owning Repo** | `exeris-spring-runtime`                                                                                          |
| **Driven By**   | ADR-006 (Spring-Free Kernel Boundary), ADR-010 (Host Runtime Model), Phase 0–1 spring-runtime delivery scope     |
| **Compliance**  | [Module Boundaries](../architecture/module-boundaries.md), [Kernel Integration Seams](../architecture/kernel-integration-seams.md) |

## Context and Problem Statement

Spring applications run on a Spring-shaped runtime by default: servlet containers (Tomcat/Jetty/Undertow), reactive engines (Reactor/Netty), JDBC connection pools (HikariCP), and `ThreadLocal`-anchored APIs (`SecurityContextHolder`, `RequestContextHolder`). All of these conflict with Exeris's runtime ownership model:

- Servlet/reactive containers want to own the request lifecycle. Exeris owns the request lifecycle (per ADR-010 Host Runtime Model).
- HikariCP wants to own the JDBC connection pool. Exeris's `PersistenceEngine` owns it.
- `ThreadLocal`-anchored Spring APIs are incompatible with Virtual Threads at scale (carrier-pinning hazards before JEP 491; allocation pressure with VT churn after).

Two failure modes are equally bad:

1. **"Make it just work" — accept Spring's ThreadLocal/servlet idioms on the request path.** This silently inverts ownership: Exeris becomes a façade, Spring runs the show, and the No Waste Compute contract is gone.
2. **"Reject Spring features that don't fit"** — usable for greenfield apps but unhelpful for the large body of existing Spring code that depends on `SecurityContextHolder.getContext()` or `@Transactional` propagation rules.

The platform needs a rule that supports both audiences cleanly without letting them contaminate each other.

## 🏁 The Decision

**Spring-runtime exposes two operational modes, and every change must declare which mode it targets.**

- **Pure Mode (default)** — Exeris-native request path. No servlet runtime. No reactive bridge. No `ThreadLocal` on hot paths. `ScopedValue` carries context. Performance-first. This is the default; it activates with no operator action.
- **Compatibility Mode (opt-in)** — Spring legacy idioms supported through narrow, clearly-marked bridges. Activated by `exeris.runtime.web.mode=compatibility`. Each compatibility feature is isolated in `*.compat.*` sub-packages and carries the `@CompatibilityMode` marker annotation. Narrow `ThreadLocal` bridging (e.g., for `SecurityContextHolder`) is permitted only here, must be cleared in `finally`, and must not leak into pure-mode paths.

**Concrete obligations:**

1. **Every meaningful change declares mode.** PRs touch one of `PURE_MODE`, `COMPATIBILITY_MODE`, or `MIXED`. Reviewers can reject "MIXED with no boundary" outright.
2. **Pure-mode code may not import from `*.compat.*`.** Enforced by `CompatibilityIsolationGuardTest` (ArchUnit). One-way visibility: compat may import pure, never vice versa.
3. **`@CompatibilityMode` marker required on compat features.** Marker annotation lives in `exeris-spring-runtime-bom` (or appropriate shared module). Static analysis flags compat-mode features missing the marker.
4. **Configuration property is the only activator.** `exeris.runtime.web.mode=compatibility` (default `pure`) chooses the active mode at bootstrap. There is no per-request mode switching, no auto-fallback, no feature-detection that silently activates compat.
5. **Pure-mode classpath bans (per `PureModeClasspathGuardTest`):** `org.apache.tomcat.embed:*`, `org.eclipse.jetty:*`, `io.undertow:*`, `io.netty:*`, `io.projectreactor:*`, `jakarta.servlet:jakarta.servlet-api`, `com.zaxxer:HikariCP`. Compat mode may permit a documented subset under explicit opt-in.
6. **Compatibility-mode allocation cost is documented, never hidden.** A compat feature that adds heap churn or context-propagation cost relative to pure mode states the cost in the feature's javadoc. Operators are told what they're trading.

## Mode boundaries by module

| Module                               | Pure Mode                                  | Compatibility Mode                                                |
|:-------------------------------------|:-------------------------------------------|:------------------------------------------------------------------|
| `exeris-spring-boot-autoconfigure`   | Wires Exeris bootstrap into Spring refresh | (no compat-only logic)                                            |
| `exeris-spring-runtime-web`          | `ExerisHttpDispatcher` over `HttpExchange` | Narrow `*.compat.servlet.*` bridge for legacy `HttpServletRequest` |
| `exeris-spring-runtime-tx`           | `ScopedValue`-bound tx context             | `*.compat.threadlocal.*` for `TransactionSynchronizationManager` |
| `exeris-spring-runtime-data`         | `PersistenceEngine`-backed `ExerisDataSource` (compat-only) | (data is largely compat by nature; pure path stays minimal)       |
| `exeris-spring-runtime-actuator`     | Read-only Micrometer bridge over `TelemetrySink` | (no compat-only logic)                                            |

## Consequences

### ✅ Positive Outcomes

- **[+] Greenfield apps get the full No-Waste-Compute path.** Pure mode is the default; new applications inherit the performance contract automatically.
- **[+] Legacy Spring code keeps working.** Apps that depend on `SecurityContextHolder`, `RequestContextHolder`, or `@Transactional` synchronization can opt into compat mode and migrate incrementally.
- **[+] No silent contamination.** `CompatibilityIsolationGuardTest` makes "compat snuck into pure" a build failure.
- **[+] Operators can see what they're getting.** A grep for `@CompatibilityMode` shows the full surface of compat-only behaviour.

### ⚠️ Trade-offs

- **[-] Two mental models.** Reviewers and contributors must internalise the mode split. CLAUDE.md and CONTRIBUTING.md call it out repeatedly; new contributors will need explicit pointers.
- **[-] Some features land twice.** Where pure and compat both need a feature (e.g., security context propagation), there will be a pure-mode `ScopedValue`-based implementation AND a compat-mode `ThreadLocal`-bridged implementation. Code-volume cost is real, deliberate, and bounded.
- **[-] Migration path required.** Apps currently running on Spring servlet/reactive defaults need a documented path to pure mode. Phase 1–2 docs cover this; the path exists but is not free.

### 📋 What is NOT in this ADR

- This ADR does not define the full set of compat-mode features. Each compat bridge lands in its own PR with its own javadoc rationale. This ADR defines the rule, not the catalogue.
- This ADR does not require operators to migrate to pure mode. Compat mode is a supported, first-class mode — not a deprecation step.

## Cross-references

- ADR-006 (Spring-Free Kernel Boundary) — defines why the kernel itself never sees mode; mode is a spring-runtime concern.
- ADR-010 (Host Runtime Model) — defines that Exeris owns the runtime; this ADR defines how Spring legacy idioms are accommodated within that ownership model.
- ADR-017 (JDBC Compact Scope) — depends on this ADR's mode model; compat-only `ExerisDataSource` is a compat-mode feature.
- `docs/architecture/module-boundaries.md` — operationalises the per-module mode split.
- `docs/phases/phase-0-bootstrap-spike.md` through `phase-4-events-flow.md` — phased delivery of mode-aware behaviour.

## Engineering Protocol

Once this decision is ACCEPTED, the existing `CompatibilityIsolationGuardTest`, `PureModeClasspathGuardTest`, and `WallIntegrityTest` suites are the canonical enforcement. Any new compat-mode bridge must:

1. Live in a `*.compat.*` sub-package.
2. Carry the `@CompatibilityMode` marker.
3. Document its allocation/context-propagation cost in its primary javadoc.
4. Be covered by both an architecture test (asserts isolation) and a unit/integration test (asserts behaviour).

PRs that violate the mode split must cite this ADR and propose a superseding decision before the violation is allowed to merge.
