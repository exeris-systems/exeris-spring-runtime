---
name: mode-clarity
description: Enforce Pure versus Compatibility mode clarity — every change declares mode (PURE_MODE, COMPATIBILITY_MODE, MIXED); pure code does not import from *.compat.*.
disable-model-invocation: true
---

<!-- DO NOT EDIT. Generated from .agents/workflows/mode-clarity.md by agents_render.py
     (exeris-systems/exeris-agents; agents-md-schema.md rule 7). Edit the source. -->
Audit this change for Pure Mode versus Compatibility Mode clarity.

Mode rules:
- Every meaningful change must declare its mode: `PURE_MODE`, `COMPATIBILITY_MODE`, or `MIXED`.
- **Pure Mode** (default): Exeris-native request path; no servlet/reactive runtime; performance-first. `ScopedValue` for context — `ThreadLocal` banned on hot paths.
- **Compatibility Mode** (opt-in via `exeris.runtime.web.mode=compatibility`): isolated in `*.compat.*` sub-packages; carries `@CompatibilityMode` marker; never activates automatically when pure mode is running. Narrow `ThreadLocal` bridging (e.g. `SecurityContextHolder`) allowed only here, must be cleared in `finally`.
- **MIXED**: touches both paths. Document which paths are pure and which are compat.
- Pure-mode code MUST NOT import from `*.compat.*`.
- Architecture tests (`CompatibilityIsolationGuardTest`) enforce this.

Change:
$ARGUMENTS

Please review:
1. Does the change explicitly declare its mode?
2. If touching pure-mode code: any import from `*.compat.*`? Hard block.
3. If adding compatibility code: is it in `*.compat.*` with `@CompatibilityMode` marker?
4. Does compatibility-mode `ThreadLocal` bridging clear in `finally`?
5. Does `CompatibilityIsolationGuardTest` pass?
6. Does the change risk activating compatibility behaviour silently when pure mode is running?
7. Minimal correction if mode clarity is at risk.
