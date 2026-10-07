# Policy: Verification Guardrails and Test Discipline

All code changes in `exeris-spring-runtime` must satisfy proportional verification across the four testing layers.

## The Rule

- **Layer 1: Unit Tests**: Adapter logic, codecs, route pattern matching, properties parsing. Fast and isolated.
- **Layer 2: Module Integration**: Spring bean wiring and SPI adapter coordination with real collaborators.
- **Layer 3: Runtime Integration**: Full Exeris host runtime tests where Spring context starts on top of the Exeris kernel engine, ingress runs through Exeris, and shutdown is clean.
- **Layer 4: Architecture Guards**:
  - `WallIntegrityTest`: Enforces zero Spring leakage into kernel SPI and core.
  - `ModuleBoundaryTest`: Validates banned dependency edges between modules.
  - `PureModeClasspathGuardTest`: Confirms banned servlet/reactive libraries are excluded in pure mode.
  - `CompatibilityIsolationGuardTest`: Asserts pure mode code never imports from `*.compat.*`.
- **Do Not Weaken Guard Tests**: Failures in architecture tests indicate real architectural defects. Never delete, skip, or weaken an architecture assertion to make a change pass.
