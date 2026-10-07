# Policy: Mode Discipline

Every meaningful change in `exeris-spring-runtime` must explicitly declare its mode: `PURE_MODE`, `COMPATIBILITY_MODE`, or `MIXED`.

## The Rule

- **Pure Mode (Default)**:
  - Exeris-native request dispatch and execution path.
  - Zero servlet or reactive runtime dependencies.
  - Context carrier uses Java `ScopedValue`. `ThreadLocal` is strictly banned on hot request paths.
  - Performance-first: zero unnecessary buffer copies and zero wrapper allocations.
- **Compatibility Mode (Opt-in)**:
  - Enabled explicitly via `exeris.runtime.web.mode=compatibility`.
  - Code must reside strictly within `*.compat.*` sub-packages.
  - All compatibility entry points must carry the `@CompatibilityMode` annotation.
  - Compatibility mode never activates automatically or implicitly when pure mode is configured.
  - Narrow `ThreadLocal` bridging (e.g., Spring `SecurityContextHolder`) is permitted only here, must be isolated, and must be cleaned up in a `finally` block.
- **Mixed Mode**:
  - Applies when a change touches both paths. The developer must document which code paths are pure and which are compatibility, ensuring zero cross-contamination.
- **Strict Boundary**: Pure-mode code must NEVER import from `*.compat.*`.
- **Enforcement**: Guarded by `CompatibilityIsolationGuardTest`.
