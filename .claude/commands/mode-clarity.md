---
description: Enforce Pure / Compatibility mode clarity — every meaningful change declares mode (`PURE_MODE`, `COMPATIBILITY_MODE`, `MIXED`); pure-mode code does not import from `*.compat.*`.
argument-hint: PR diff or change crossing the pure/compat boundary to audit
---

Audit this change for Pure / Compatibility mode clarity.

Mode rules (per repo `CLAUDE.md`):
- Every meaningful change MUST declare its mode: `PURE_MODE`, `COMPATIBILITY_MODE`, or `MIXED`.
- **Pure Mode** (default): Exeris-native request path; no servlet/reactive runtime; performance-first. `ScopedValue` for context — `ThreadLocal` banned on hot paths.
- **Compatibility Mode** (opt-in via `exeris.runtime.web.mode=compatibility`): isolated in `*.compat.*` sub-packages; must carry `@CompatibilityMode` marker; never activates automatically when pure mode is running. Narrow `ThreadLocal` bridging (e.g. `SecurityContextHolder`) allowed only here, must be cleared in `finally`, must not leak into pure-mode paths.
- **`MIXED`**: the change touches both paths. Not a blanket escape hatch — document which code paths are pure and which are compat, and verify the two cannot cross-contaminate (no pure-mode import of `*.compat.*`, no silent compat activation under pure mode).
- Pure-mode code MUST NOT import from `*.compat.*`.
- Architecture tests (`*ArchitectureTest`, `*BoundaryTest`, `CompatibilityIsolationGuardTest`) enforce this — keep them green.

Change:
$ARGUMENTS

Please review:
1. Does the PR explicitly declare its mode (`PURE_MODE` / `COMPATIBILITY_MODE` / `MIXED`)?
2. If touching pure-mode code: any new import from `*.compat.*`? Hard reject.
3. If adding compatibility code: is it in `*.compat.*` sub-packages with `@CompatibilityMode` marker?
4. Does compatibility-mode `ThreadLocal` bridging clear in `finally`? Does it stay narrow (e.g. just `SecurityContextHolder`)?
5. Does `CompatibilityIsolationGuardTest` (and friends) still pass?
6. Does the change risk activating compatibility behaviour silently when pure mode is running?
7. Minimal correction if mode clarity is at risk.

Compatibility-mode allocation cost MUST be measured and documented, never silently applied to pure-mode paths.
