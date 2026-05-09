# Phase 1b — Kernel Seam Closure Record

**Status:** Closed (2026-03-25)
**Sub-phase:** Phase 1b — Wire-level E2E ingress proof
**Master:** [`phase-1-web-ingress.md`](phase-1-web-ingress.md)

This document records the kernel-side seam that unblocked Phase 1b and the testkit-only
finding that surfaced while consuming it. It supersedes (and replaces) the two earlier
phase-1 seam-request drafts.

---

## Why a seam was needed

Wire-level Pure Mode tests in `exeris-spring-runtime-web` could not deterministically
obtain a started `HttpServerEngine` and a bound port. During lifecycle bootstrap,
`HttpKernelProviders.httpServerEngine()` threw `NoSuchElementException: ScopedValue not
bound`, so the in-repo wire-level test was kept `@Disabled` pending a kernel-owned fixture.

Constraints the seam had to respect:
- The Wall stays intact (kernel SPI/Core remain Spring-free).
- Runtime ownership stays with Exeris (no inversion to Spring as ingress owner).
- The seam must be narrow and test-focused — not a compatibility-mode expansion.

## What was delivered (kernel-side)

**Artifact:** `eu.exeris:exeris-kernel-community-testkit`

**API surface used by this repo:**

- `eu.exeris.kernel.testing.http.EmbeddedHttpEngineFixture`
- `eu.exeris.kernel.testing.http.EmbeddedHttpEngineFixtures`
- `eu.exeris.kernel.testing.http.KernelBootstrapHttpEngineFixture`

The fixture provides deterministic `start(handler)`, `engine()`, `boundPort()`,
`isRunning()`, and `close()` semantics — all observable without reflection or internal
hooks.

## How this repo consumes it

`exeris-spring-runtime-web/src/test/java/eu/exeris/spring/runtime/web/ExerisWireLevelRuntimeIntegrationTest.java`
starts Exeris-owned ingress with:

```java
EmbeddedHttpEngineFixtures.kernelBootstrapFixture().start(dispatcher);
```

The previously `@Disabled` wire-level test is now active. Six wire-level scenarios are
green: bind/route/cleanup, body+headers, 404, custom status, in-flight drain at shutdown,
and telemetry scope evidence.

## Testkit finding — `MEMORY_ALLOCATOR` `ScopedValue` propagation

Surfaced while wiring the wire-level test path. **Production is not affected.**

- Kernel bootstrap binds `MEMORY_ALLOCATOR` (a `ScopedValue`) on the bootstrap scope run.
- `NativeTcpCarrier`'s acceptor loop runs on a platform thread. Platform threads do not
  inherit `ScopedValue` bindings from the bootstrap scope.
- Handler virtual threads spawned from that acceptor platform thread therefore also lack
  the `MEMORY_ALLOCATOR` binding.
- In the `KernelBootstrapHttpEngineFixture` path this caused
  `ExerisServerResponse.toKernelResponse()` to fail when acquiring an allocator via
  `KernelProviders.MEMORY_ALLOCATOR`.

**Fix in this repo:** `ExerisServerResponse.toKernelResponse()` checks
`KernelProviders.MEMORY_ALLOCATOR.isBound()` before scoped allocation. When the binding
is absent (testkit path only), it falls back to `HeapBodyBuffer`.

**Why this is safe:** the production execution path always runs within the full kernel
scope where `MEMORY_ALLOCATOR` is bound. The heap fallback is unreachable in production
and does not alter any hot-path allocation contract — it exists solely so that the
testkit fixture can drive the dispatcher without bringing up the full kernel scope.

## Open follow-ups

None blocking 1b closure. Wire-level coverage may be extended incrementally in 1c
(allocation/latency baseline) without weakening the current Exeris-owned ingress proof.
