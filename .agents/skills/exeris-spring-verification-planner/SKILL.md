---
name: exeris-spring-verification-planner
description: Plan verification depth (unit / module-integration / runtime-integration / architecture-guard / optional performance) based on change risk and architecture impact. Highlights merge-blocking gaps in evidence — particularly missing host-runtime ownership proofs and missing mode distinctions.
---

# exeris-spring-verification-planner

## Purpose
Plan verification depth based on change risk and architecture impact.

## Verification Layers
- unit tests
- module integration tests
- runtime integration tests
- architecture guard tests
- optional performance validation

## Planning Rules
1. Local adapter-only changes -> at least unit + impacted module tests.
2. Mode/ownership path changes -> include runtime integration and architecture guard.
3. Any host-runtime claim change -> require explicit runtime ownership evidence.
4. Request-path overhead changes -> consider targeted performance checks.

## Output
- verification classification
- concrete suites/files to run
- merge-blocking gaps
