---
title: "exeris-spring-runtime: host-runtime integration layer"
type: reference
visibility: public
owning-repo: exeris-spring-runtime
status: active
last-verified: 2026-10-07
---

# exeris-spring-runtime

Guardrails for AI assistants working inside this repository — the architectural contract a session must respect, and an index to where each rule lives. Human onboarding is in [`README.md`](README.md) and [`CONTRIBUTING.md`](CONTRIBUTING.md).

## Mission and Scope

`exeris-spring-runtime` is an **independent Tier 1 product** in the Exeris ecosystem — a host-runtime integration layer, not a Spring Boot starter:

> **Spring is the application framework. Exeris is the runtime owner.**

- **Spring owns**: Dependency injection (DI), configuration binding, and bean lifecycle.
- **Exeris owns**: Transport ingress, request lifecycle, backpressure, off-heap memory, provider discovery via Java `ServiceLoader`, and the telemetry hot path.
- **Two consumers only**:
  1. Customers migrating existing Spring applications onto the Exeris kernel.
  2. `budgetHQ` — dogfooding Spring-on-Exeris under real customer load.

Coordinates: groupId `eu.exeris`, packages `eu.exeris.spring.*`. License: Apache-2.0.

## Operating Contract

**Non-negotiable, whatever the task:**

- **The Wall**: The kernel (`exeris-kernel-spi`, `exeris-kernel-core`) and capability tier (`exeris-caps-*`) must remain completely Spring-free ([policy](.agents/policies/the-wall.md)).
- **Runtime Ownership**: Never invert ownership to servlet/Netty/Reactor ingress, Spring IoC for SPI discovery, or JDBC-first persistence ([policy](.agents/policies/runtime-ownership.md)).
- **Mode Discipline**: Declare `PURE_MODE`, `COMPATIBILITY_MODE`, or `MIXED`. Pure mode is native, zero-copy, and uses `ScopedValue`. Compatibility mode is isolated in `*.compat.*` and marked `@CompatibilityMode`. Pure code never imports `*.compat.*` ([policy](.agents/policies/mode-discipline.md)).
- **Hot-Path Zero-Copy**: In pure mode, codecs operate on `MemorySegment` directly. Never copy `LoanedBuffer` into `byte[]` on request hot paths. Handlers must observe `LoanedBuffer` lifecycle ownership ([policy](.agents/policies/hot-path-performance.md)).
- **Module Boundaries**: Respect module roles. `exeris-spring-boot-autoconfigure` is thin (<100 lines of logic per class). Banned edges: `autoconfigure → web/tx/data`, `web → data`, `data → web`, `tx → web`, `actuator → web` ([policy](.agents/policies/module-boundaries.md)).
- **JDK Baseline**: Java 25 (LTS) floor, class-file 69.0. `--enable-preview` strictly banned ([policy](.agents/policies/jdk-baseline.md)).
- **Bootstrap Invariance**: Strict sequence: Spring `refresh()` → `ExerisRuntimeLifecycle.start()` → `KernelBootstrap.bootstrap()` → handlers register → HTTP binds. Reverse on shutdown ([policy](.agents/policies/bootstrap-invariance.md)).
- **ADR Triggers**: Structural changes require an ADR. Follow `ACCEPTED` vs `PROPOSED` conventions ([policy](.agents/policies/adr-triggers.md)).
- **Verification Guardrails**: Maintain four testing layers. Architecture guard tests must never be weakened or bypassed ([policy](.agents/policies/verification-guardrails.md)).

## Architecture and Reference Entry Points

1. [`docs/architecture/kernel-integration-seams.md`](docs/architecture/kernel-integration-seams.md) — class-level bridge definitions.
2. [`docs/architecture/module-boundaries.md`](docs/architecture/module-boundaries.md) — structural contracts and banned edges.
3. [`docs/adr/`](docs/adr/) — architectural decisions binding this repository; see [`adr-map.md`](.agents/references/adr-map.md).
4. [`docs/architecture/spring-boot-4-matrix.md`](docs/architecture/spring-boot-4-matrix.md) — Spring Boot 3/4 baseline and matrix compatibility.

## `.agents/` — Canonical Semantic Source

All agent semantics are authored once under [`.agents/`](.agents) and nowhere else:

| Path | Contents |
| :-- | :-- |
| [`.agents/policies/`](.agents/policies) | Non-negotiables: ownership, the wall, modes, hot path, module boundaries, JDK, bootstrap, ADRs, verification. |
| [`.agents/references/`](.agents/references) | ADR map, architecture seams, build and testing guide. |
| [`.agents/skills/`](.agents/skills) | Triage, wall check, mode clarity, module boundary, ownership boundary, performance, and verification skills. |
| [`.agents/agents/`](.agents/agents) | Role profiles: router, architect, implementer, performance, docs-adr, verification. |
| [`.agents/workflows/`](.agents/workflows) | Audit workflows: wall-check, module-boundary, mode-clarity, runtime-path-perf, ownership-boundary. |
| [`.agents/schemas/`](.agents/schemas), [`hooks/`](.agents/hooks), [`evals/`](.agents/evals) | Decision schemas, L0 deny hooks, and behaviour scenarios. |
| [`.agents/manifest.yaml`](.agents/manifest.yaml) | Composition manifest importing `exeris-agents` 2.1.0 vendored under `.agents/vendor/`. |

## Verification and Commands

```bash
mvn -s .github/maven-settings.xml clean install
mvn -s .github/maven-settings.xml -Pmatrix-sb3 test
```

Report outcomes honestly. Always run architecture guard tests and verify that The Wall and pure-mode classpath isolation remain intact.

## Provider Adapters

[`.claude/`](.claude) holds Claude Code adapters generated by `agents_render.py` from `.agents/`. Never edit generated adapters directly. [`CLAUDE.md`](CLAUDE.md) points here. Other adapters (`copilot`, `gemini`, `cursor`, `antigravity`, `codex`) are deferred in `manifest.yaml`.
