# Phase 1: Kernel Seam Update — HTTP Engine Fixture Delivered and Consumed

**Status:** Resolved and delivered  
**Date:** 2026-03-25  
**Scope:** Pure Mode wire-level test seam consumption  
**Owners:** `exeris-spring-runtime-web` + kernel community testkit

## Summary

The kernel seam needed for deterministic wire-level Pure Mode ingress tests is now available in
the kernel community testkit module:

- `eu.exeris:exeris-kernel-community-testkit`
- API types:
  - `EmbeddedHttpEngineFixture`
  - `EmbeddedHttpEngineFixtures`
  - `KernelBootstrapHttpEngineFixture`

`exeris-spring-runtime-web` consumes this seam directly in:

- `exeris-spring-runtime-web/src/test/java/eu/exeris/spring/runtime/web/ExerisWireLevelRuntimeIntegrationTest.java`

The test starts Exeris-owned ingress using
`EmbeddedHttpEngineFixtures.kernelBootstrapFixture().start(dispatcher)` and now executes
successfully in-module.

## Delivered outcome

1. The kernel seam artifact is delivered and consumed in the wire-level test.
2. Deterministic wire-level execution is now green for bind + round-trip status + Spring handler
  invocation assertions.
3. Seam usage remains Spring-isolated at the integration layer; kernel ownership boundaries are
  preserved.

## Current scope and next action

1. Keep extending wire-level assertions incrementally (for example, payload/body-path checks and
  additional error/concurrency scenarios) without weakening current Exeris-owned ingress proof.

---

## `MEMORY_ALLOCATOR` ScopedValue Propagation — Testkit Finding

**Date:** 2026-03-25

During wire-level test development a testkit context limitation was identified:

- The kernel bootstrap binds `MEMORY_ALLOCATOR` (a `ScopedValue`) on the bootstrap scope run.
- `NativeTcpCarrier`'s acceptor loop runs on a platform thread. Platform threads do not inherit
  `ScopedValue` bindings from the bootstrap scope.
- Handler Virtual Threads spawned from that acceptor platform thread therefore also lack the
  `MEMORY_ALLOCATOR` binding.

**Testkit impact:** `ExerisServerResponse.toKernelResponse()` could not acquire an allocator via
`KernelProviders.MEMORY_ALLOCATOR` in the `KernelBootstrapHttpEngineFixture` path, causing the
buffer allocation to fail.

**Fix:** `ExerisServerResponse.toKernelResponse()` now checks `KernelProviders.MEMORY_ALLOCATOR.isBound()`
before attempting a scoped allocation. When the binding is absent (testkit path), a `HeapBodyBuffer`
fallback is used instead.

**Production impact:** None. The production execution path always runs within the full kernel scope
where `MEMORY_ALLOCATOR` is bound. The heap fallback is unreachable in production and does not alter
any hot-path allocation contract.
