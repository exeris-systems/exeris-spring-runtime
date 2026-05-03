# Phase 1: Kernel Seam Request — GitHub Issue Draft (exeris-kernel)

> Archival note (2026-03-25): resolved. The requested seam is now delivered via
> `eu.exeris:exeris-kernel-community-testkit` and consumed in
> `ExerisWireLevelRuntimeIntegrationTest`. This draft is retained for historical context.
> Follow-up note: seam consumption is delivered and active; there is no longer an active fixture
> startup blocker in the migrated wire-level test path.

## Title
Provide embeddable HTTP engine fixture seam for deterministic wire-level integration tests

## Summary
Phase 1 Pure Mode wire-level verification in `exeris-spring-runtime` is blocked by a kernel bootstrap seam gap.
During runtime lifecycle bootstrap, `HttpKernelProviders.httpServerEngine()` throws `NoSuchElementException: ScopedValue not bound`, so tests cannot deterministically obtain a started `HttpServerEngine` and bound port for real ingress assertions.

This currently blocks enabling the disabled test in this repo:
- `exeris-spring-runtime-web/src/test/java/eu/exeris/spring/runtime/web/ExerisWireLevelRuntimeIntegrationTest.java`
- `@Disabled("Blocked by kernel boot seam: HttpKernelProviders HTTP_SERVER_ENGINE ScopedValue is not bound during lifecycle bootstrap callback")`

## Reproduction
1. Run the wire-level runtime test path in `exeris-spring-runtime-web` (currently disabled pending kernel seam).
2. Follow lifecycle startup path where integration code reaches `HttpKernelProviders.httpServerEngine()`.
3. Observe failure: `NoSuchElementException: ScopedValue not bound`.
4. Result: no deterministic started engine handle or bound-port visibility for round-trip assertions.

Reference integration path:
- `exeris-spring-boot-autoconfigure/src/main/java/eu/exeris/spring/boot/autoconfigure/ExerisRuntimeLifecycle.java`

## Expected/Requested API seam
Provide a kernel-owned embeddable fixture (or equivalent public seam) that allows deterministic test-time access to started HTTP engine state.

Minimal API sketch (illustrative):

```java
package eu.exeris.kernel.testing.http;

import eu.exeris.kernel.spi.http.HttpHandler;
import eu.exeris.kernel.spi.http.HttpServerEngine;

public interface EmbeddedHttpEngineFixture extends AutoCloseable {
    void start(HttpHandler handler);
    HttpServerEngine engine();
    int boundPort();
    boolean isRunning();
    @Override
    void close();
}
```

Required guarantees:
- `engine()` is available deterministically after `start(...)`.
- `boundPort()` is observable without internal/reflection access.
- `close()` performs deterministic cleanup for CI.

## Constraints
- Keep kernel SPI/Core Spring-free (The Wall preserved).
- Do not invert ownership to Spring; Exeris remains ingress/lifecycle owner.
- Avoid compatibility-mode expansion in this seam request.
- Keep seam narrow and test-focused (no broad framework coupling).

## Acceptance criteria
1. Kernel provides embeddable HTTP engine fixture seam (or equivalent API) with deterministic `start`, `engine`, `boundPort`, and `close` behavior.
2. `exeris-spring-runtime` can enable `ExerisWireLevelRuntimeIntegrationTest` without custom/internal hooks.
3. Enabled test proves: real bind, HTTP client round-trip through Exeris ingress, Spring `@ExerisRoute` invocation via dispatcher, and clean stop.
4. CI runs are deterministic (no bootstrap timing/scoped-context flake).

## Impact
- Unblocks M1 closure gate for wire-level Pure Mode ingress proof.
- Improves architecture honesty by replacing dispatcher-only evidence with real ingress/runtime ownership evidence.
- Reduces risk of host-runtime overclaim in milestone reporting.

## Links
- Blocker context doc: `docs/phases/phase-1-kernel-seam-request-http-engine-fixture.md`
- Milestone status: `docs/phases/phase-1-milestone-status.md`
- Disabled test: `exeris-spring-runtime-web/src/test/java/eu/exeris/spring/runtime/web/ExerisWireLevelRuntimeIntegrationTest.java`
- Lifecycle call path: `exeris-spring-boot-autoconfigure/src/main/java/eu/exeris/spring/boot/autoconfigure/ExerisRuntimeLifecycle.java`
