# Phase 0 Invariants

**Status:** Locked-in (Phase 0 closed)
**Source of authority:** ADR-010, `docs/architecture/module-boundaries.md`,
`docs/architecture/kernel-integration-seams.md`. This page enumerates the
non-negotiable invariants that Phase 0 established and that all subsequent phases
must preserve.

A change that breaks any item below is an architectural regression, not a style
issue, and requires a superseding ADR — not a workaround in code.

---

## 1. The Wall (kernel ↔ Spring isolation)

- No type in `eu.exeris.kernel.spi..` may import `org.springframework..`.
- No type in `eu.exeris.kernel.core..` may import `org.springframework..`.
- The kernel is consumed only as a binary dependency; it is never modified to
  accommodate Spring.
- **Guard:** `eu.exeris.spring.arch.WallIntegrityTest` (autoconfigure module).

## 2. Runtime ownership

- Spring is the *application framework* (DI, config binding, bean lifecycle).
- Exeris is the *runtime owner* (transport ingress, request lifecycle, off-heap
  memory, provider discovery, telemetry hot path).
- After `ExerisRuntimeLifecycle.start()` returns, Exeris owns the transport
  layer. Spring does not directly manage sockets, connections, or the request
  execution path.
- **Guard:** runtime ownership is a review invariant; no servlet/reactive
  container is permitted on the classpath (see §5).

## 3. Bootstrap order

The bootstrap sequence is invariant:

```
Spring ApplicationContext.refresh()
  → SmartLifecycle.start()                 (phase = Integer.MAX_VALUE - 100)
  → ExerisRuntimeLifecycle.start()
      → KernelBootstrap.boot(...)
          → ServiceLoader → ConfigProvider → ExerisSpringConfigProvider (priority 150)
          → kernel DAG: Config → Memory → Exceptions → {Security, Persistence}
                          → {Graph, Transport} → {Events, Flow} → READY
      → store KernelBootstrap for stop() / isRunning()
```

Shutdown reverses this exactly:

```
SmartLifecycle.stop(callback)
  → transport.closeIngress()                (no new connections)
  → drain in-flight requests                (≤ shutdown.timeoutSeconds)
  → KernelBootstrap.shutdown()
  → callback.run()
```

- **Guards:** `ExerisBootstrapIntegrationTest` (start/stop, thread liveness,
  concurrent stop-during-start, timeout enforcement).

## 4. Provider discovery is `ServiceLoader`-first

- Kernel providers are discovered via `ServiceLoader`, never via Spring beans.
- `ExerisSpringConfigProvider` is registered at
  `META-INF/services/eu.exeris.kernel.spi.config.ConfigProvider`.
- Priority is `150` when a Spring `Environment` is bound and `0` otherwise, so
  the Spring-backed provider wins during normal application startup while
  deferring to community/kernel-default providers in isolated fixtures.
- IoC must never replace `ServiceLoader` for SPI discovery.
- **Guard:** `configProviderPriority_is150` in `ExerisBootstrapIntegrationTest`.

## 5. Pure-Mode classpath baseline

The runtime classpath in Pure Mode must not contain any of:

- `org.apache.tomcat.embed:*`, `org.eclipse.jetty:*`, `io.undertow:*`
- `io.netty:*`, `io.projectreactor:*`
- `jakarta.servlet:jakarta.servlet-api`
- `com.zaxxer:HikariCP`

- **Guards:** `PureModeClasspathGuardTest` (per-module) and three rules in
  `WallIntegrityTest` covering servlet, Tomcat/Jetty/Undertow, and
  Reactor/Netty.

## 6. Module placement

- `exeris-spring-boot-autoconfigure` is a **thin** wiring layer. No transport,
  no request processing, no transactions, no persistence logic lives here.
- Banned dependency edges (compile-time): `autoconfigure → web/tx/data`,
  `web → data`, `data → web`, `tx → web`, `actuator → web (data plane)`.
- Package roots are strict: `eu.exeris.spring.boot.autoconfigure.*`,
  `eu.exeris.spring.runtime.{web,tx,data,actuator}.*`. Nothing under
  `eu.exeris.spring.*` may sit inside `eu.exeris.kernel.*`.
- **Guards:** `ModuleBoundaryTest`, `WallIntegrityTest#autoconfigure_mustNotImportWebRuntimeClasses`.

## 7. Mode declaration

Every meaningful change declares its mode: `PURE_MODE`, `COMPATIBILITY_MODE`,
or `MIXED`. Pure-mode code must not import from `*.compat.*`. Compatibility
behaviour must never activate automatically when Pure Mode is configured.

## 8. Lifecycle bean phase

`ExerisRuntimeLifecycle.getPhase()` returns `Integer.MAX_VALUE - 100`. It
starts after virtually all application beans and stops before them — so
application code can finish using Exeris resources before the runtime tears
down. Changing this constant requires an ADR.

---

## How invariants are enforced

| Invariant | Primary guard |
|:---|:---|
| Kernel SPI / Core Spring-free | `WallIntegrityTest` |
| Autoconfigure stays thin | `WallIntegrityTest`, `ModuleBoundaryTest` |
| No servlet/Netty/Reactor on classpath | `PureModeClasspathGuardTest`, `WallIntegrityTest` |
| Bootstrap order, start/stop, timeouts | `ExerisBootstrapIntegrationTest` |
| `ConfigProvider` priority 150 | `ExerisBootstrapIntegrationTest#configProviderPriority_is150` |
| Module dependency edges | `ModuleBoundaryTest` |

These tests must stay green. A failure in any of them indicates a real
architectural regression; the test is not the bug.
