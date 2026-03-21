# Contributing to exeris-spring-runtime

Thank you for contributing.

This repository is integration-critical: it must keep Spring ergonomics while preserving Exeris runtime ownership and kernel isolation.

## 1) Architecture first

Before making changes, read in this order:

1. `docs/adr/ADR-010-host-runtime-model.md`
2. `docs/architecture/module-boundaries.md`
3. `docs/architecture/kernel-integration-seams.md`
4. Relevant phase plan in `docs/phases/phase-*.md`
5. `.github/copilot-instructions.md`

If documents conflict:
- ADRs win on architecture intent.
- module boundaries + integration seams win on structural contracts.
- phase docs define current delivery target unless superseded by ADR.

## 2) Mandatory guardrails

- Spring remains the application framework; Exeris remains runtime owner.
- No Spring leakage into Exeris kernel SPI/Core.
- Pure Mode and Compatibility Mode must stay explicit and separated.
- Autoconfigure must stay thin (wiring/lifecycle/conditions only).
- No hidden fallback to servlet/reactive/JDBC-first ownership while claiming host-runtime mode.

## 3) Mode declaration required

Each meaningful change must state one of:

- `PURE_MODE`
- `COMPATIBILITY_MODE`
- `MIXED`

If unclear, resolve architecture intent before implementation.

## 4) Module boundaries

- `exeris-spring-boot-autoconfigure`: boot wiring, properties, lifecycle coordination
- `exeris-spring-runtime-web`: request bridge, routing, codecs, error mapping
- `exeris-spring-runtime-tx`: transaction boundary bridge
- `exeris-spring-runtime-data`: optional persistence compatibility (high scrutiny)
- `exeris-spring-runtime-actuator`: operational visibility only

Do not move runtime logic into autoconfigure.

## 5) ThreadLocal policy

- In pure mode and foundational runtime paths, `ThreadLocal` context models are banned.
- In compatibility-scoped bridges, narrowly isolated `ThreadLocal` usage is allowed only when required by Spring internals, and must be:
  - virtual-thread scoped,
  - cleared deterministically in `finally`,
  - isolated from pure-mode execution paths,
  - explicitly documented.

## 6) Verification expectations

Choose test depth based on change scope:

- Unit tests for local adapter/wiring logic.
- Module integration tests for real Spring wiring.
- Runtime integration tests for Exeris-owned ingress/lifecycle claims.
- Architecture guard tests for Wall and module-boundary regressions.

If request path behavior is affected, include overhead/performance validation where practical.

## 7) Documentation and ADR discipline

Update docs in the same PR when behavior or guarantees change.

Trigger ADR action when changes affect long-lived architecture direction, including:
- ownership model,
- mode semantics,
- module responsibility contracts,
- compatibility guarantees.

## 8) Pull request checklist

- [ ] Mode impact declared (`PURE_MODE` / `COMPATIBILITY_MODE` / `MIXED`)
- [ ] Ownership model preserved (no inversion)
- [ ] The Wall preserved (no Spring in kernel SPI/Core)
- [ ] Module placement follows `module-boundaries.md`
- [ ] Tests added/updated for the affected layer
- [ ] Docs/ADR updated if contracts or claims changed
