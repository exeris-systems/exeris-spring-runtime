---
title: "Review rules for exeris-spring-runtime"
type: reference
visibility: public
owning-repo: exeris-spring-runtime
status: active
last-verified: 2026-10-07
---

# Review rules for `exeris-spring-runtime`

The `repo-routine` extension of `exeris-systems/.github`'s `docs-guardrails-review.md`, applied **after** its parts and under its severity tags, output format, and verdict schema. It adds checks and raises severities; it lowers nothing and skips nothing. One review, one verdict, one publisher.

**The criteria are not authored here.** They are owned by the policies under `.agents/policies/`, which `AGENTS.md` names, and most have a review skill under `.agents/skills/` that says how to apply them. This file names the questions a reviewer must reach for and the severity each answer carries. Read the policy a rule names before applying it.

## What this repository is answerable for

`exeris-spring-runtime` is the host-runtime integration layer enabling Spring applications to execute on top of the Exeris kernel execution model. The characteristic defect here is quiet ownership inversion — allowing Spring or legacy servlet/reactive frameworks to take over transport ingress, lifecycle, memory, or SPI discovery.

## Step S — rules of this repository

S1. **Runtime ownership** (`runtime-ownership.md`). Spring is the application framework; Exeris is the runtime owner. Any change that delegates transport ingress to servlet/Netty/Reactor, replaces `ServiceLoader` with Spring DI, or inverts persistence to JDBC-first → `[HARD BLOCK]`.

S2. **The Wall** (`the-wall.md`). The kernel (`exeris-kernel-spi`, `exeris-kernel-core`) and capability tier (`exeris-caps-*`) must remain completely Spring-free. Any Spring type (`org.springframework.*`), annotation, or DI lookup in kernel touchpoints, or a cap reaching into Spring internals → `[HARD BLOCK]`.

S3. **Mode clarity and isolation** (`mode-discipline.md`). Every PR must declare its mode (`PURE_MODE`, `COMPATIBILITY_MODE`, `MIXED`). Any pure-mode code importing from `*.compat.*`, or silent compatibility activation under pure mode → `[HARD BLOCK]`. Compatibility-mode `ThreadLocal` bridging not isolated or not cleared in `finally` → `[HARD BLOCK]`.

S4: **Hot-path zero-copy performance** (`hot-path-performance.md`). Body copying from `LoanedBuffer` to `byte[]` / `InputStream` on the primary pure request path, per-request wrapper DTO allocations, or `LoanedBuffer` double-release / leak → `[HARD BLOCK]`. Reflection-heavy dispatch paths without caching → `[PERFORMANCE]`.

S5: **Module boundaries** (`module-boundaries.md`). Banned dependency edges (`autoconfigure → web/tx/data`, `web → data`, `data → web`, `tx → web`, `actuator → web`), fat autoconfigure classes (>100 lines of logic), or banned pure-mode classpath dependencies → `[HARD BLOCK]`.

S6: **JDK baseline & binary neutrality** (`jdk-baseline.md`). Java 25 (LTS) baseline, class-file 69.0. Reintroducing `--enable-preview` at compile/test time or raising compiler release ahead of the kernel → `[HARD BLOCK]`.

S7: **Bootstrap invariance** (`bootstrap-invariance.md`). Breaking the invariant startup sequence (Spring `refresh()` → `ExerisRuntimeLifecycle.start()` → `KernelBootstrap.bootstrap()` → handlers register → HTTP binds), or splitting lifecycle across multiple coordinators → `[HARD BLOCK]`.

S8: **ADR triggers and documentation honesty** (`adr-triggers.md`). Structural changes to ownership, mode boundaries, module contracts, or compatibility guarantees without an ADR or ADR amendment, or claiming pure mode while implementation is compatibility-driven → `[HARD BLOCK]`.

## Where this does not apply, and what it costs

Not to correctness, scope, or the pull request's body and commits. The shared `code` and `pr` parts judge those, and they are not restated here.

The reviewer runs `repo-checks` for the two scripts `.agents/manifest.yaml` names: `hook-deny-check.sh` and `eval-consistency-check.sh`. Architecture guard tests (`WallIntegrityTest`, `ModuleBoundaryTest`, `PureModeClasspathGuardTest`, `CompatibilityIsolationGuardTest`) run during the build checks.
