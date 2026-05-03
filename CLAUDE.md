# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this repo is

`exeris-spring-runtime` is a **host-runtime integration layer**, not a Spring Boot starter. The governing principle:

> Spring is the application framework. Exeris is the runtime owner.

Spring owns DI, config binding, bean lifecycle. Exeris owns transport ingress, request lifecycle, backpressure, off-heap memory, provider discovery (`ServiceLoader`), and the telemetry hot path. Any change that quietly inverts this ownership (servlet/Netty/Reactor on the request path, IoC replacing `ServiceLoader`, JDBC-first persistence) is an architectural defect, not a style issue.

The kernel (`exeris-kernel-spi`, `exeris-kernel-core`) is consumed as binary dependencies and **must remain Spring-free**. This is "The Wall."

## Build & test

Java **26 with `--enable-preview`** is required (kernel uses preview features → class file minor version 65535). Test JVM args are wired in the root POM (`-XX:+UnlockExperimentalVMOptions -XX:+UseZGC --enable-preview`).

```bash
mvn -s .github/maven-settings.xml clean install        # full reactor build
mvn -s .github/maven-settings.xml -pl exeris-spring-runtime-web -am test    # one module + deps
mvn -s .github/maven-settings.xml -pl exeris-spring-runtime-web test \
    -Dtest=ExerisHttpDispatcherTest                    # single test class
mvn -s .github/maven-settings.xml -pl exeris-spring-runtime-web test \
    -Dtest=ExerisHttpDispatcherTest#methodName         # single test method
mvn -s .github/maven-settings.xml clean deploy         # publish to GitHub Packages
```

The `.github/maven-settings.xml` flag is required for snapshot resolution from GitHub Packages — `GITHUB_TOKEN` must be exported with `read:packages` scope. Kernel snapshots come from `maven.pkg.github.com/exeris-systems/exeris-kernel`.

## Module layout (and what belongs where)

| Module | Role | Hard rules |
|---|---|---|
| `exeris-spring-runtime-bom` / `-build-config` | Version + plugin config | No source. |
| `exeris-spring-boot-autoconfigure` | Boot wiring, properties, `SmartLifecycle`, `@Conditional*` | **Thin.** No transport, no request processing, no tx, no persistence. Classes >100 lines of logic are a smell. |
| `exeris-spring-runtime-web` | `HttpHandler` impl bridging Exeris `HttpExchange` ↔ Spring handler beans | Pure mode: no `jakarta.servlet.*`, no `io.projectreactor.*`, no body copy from `LoanedBuffer` to `byte[]` on primary path. May depend on `spring-web` model only — never `spring-webmvc`. |
| `exeris-spring-runtime-tx` | `PlatformTransactionManager` over `PersistenceConnection` | No `ThreadLocal` as tx context carrier; use `ScopedValue`. No `DataSource`/HikariCP ownership. |
| `exeris-spring-runtime-data` | Optional persistence bridge (high scrutiny) | Each public class needs an ADR/Phase-3 reference comment. No HikariCP, no JPA/Hibernate as a first-class path. |
| `exeris-spring-runtime-actuator` | Health, info, Micrometer bridge over `TelemetrySink` | Read-only / observability only. Never owns a data-plane path; never redefines `TelemetrySink`. |

Banned dependency edges: `autoconfigure → web/tx/data`, `web → data`, `data → web`, `tx → web`, `actuator → web (data-plane)`. Banned at the kernel boundary: any Spring type inside `eu.exeris.kernel.spi.*` or `eu.exeris.kernel.core.*`.

Banned from the runtime classpath in pure mode: `org.apache.tomcat.embed:*`, `org.eclipse.jetty:*`, `io.undertow:*`, `io.netty:*`, `io.projectreactor:*`, `jakarta.servlet:jakarta.servlet-api`, `com.zaxxer:HikariCP`. Each module ships a `PureModeClasspathGuardTest` that asserts these are absent.

## Pure Mode vs Compatibility Mode

Every meaningful change must declare its mode: `PURE_MODE`, `COMPATIBILITY_MODE`, or `MIXED`.

- **Pure Mode** (default): Exeris-native request path, no servlet/reactive runtime, performance-first. `ScopedValue` for context — `ThreadLocal` is banned on hot paths.
- **Compatibility Mode** (opt-in, activated by `exeris.runtime.web.mode=compatibility`): isolated in `*.compat.*` sub-packages, must carry a `@CompatibilityMode` marker, never activates automatically when pure mode is running. Narrow `ThreadLocal` bridging (e.g., for `SecurityContextHolder`) is allowed only here, must be cleared in `finally`, and must not leak into pure-mode paths.

Pure-mode code must not import from `*.compat.*`. Architecture tests (`*ArchitectureTest`, `*BoundaryTest`, `CompatibilityIsolationGuardTest`) enforce this — keep them green.

## Kernel SPI seams (where the bridge happens)

Read `docs/architecture/kernel-integration-seams.md` before touching any of these:

| Kernel SPI | Bridge class | Module |
|---|---|---|
| `HttpHandler` / `HttpExchange` | `ExerisHttpDispatcher`, `ExerisServerRequest/Response`, `ExerisRouteRegistry` (`@ExerisRoute` + `ExerisRequestHandler` beans) | `web` |
| `ConfigProvider` (registered via `META-INF/services`, priority 150) | `ExerisSpringConfigProvider` | `autoconfigure` |
| `SubsystemProvider` / bootstrap | `ExerisRuntimeLifecycle` (`SmartLifecycle`) — Spring refresh → kernel bootstrap → `HttpServerEngine.start()` | `autoconfigure` |
| `KernelProviders` (`ScopedValue` slots) | `ExerisContextHolder` | `web` |
| `TelemetrySink` → Micrometer | `ExerisActuatorTelemetryBridge` (`MeterBinder`) | `actuator` |
| `PersistenceEngine` / `ConnectionFactory` | `ExerisPlatformTransactionManager`, `ExerisDataSource` (compat-only JDBC adapter) | `tx` / `data` |

Bootstrap order is invariant: Spring `refresh()` → `ExerisRuntimeLifecycle.start()` → `KernelBootstrap.bootstrap()` (`ServiceLoader` discovers providers, DAG initialises, `KERNEL READY`) → handlers register → `HttpServerEngine` binds. Shutdown reverses exactly. The kernel's own bootstrap DAG is `Config → Memory → Exceptions → {Security, Persistence} → {Graph, Transport} → {Events, Flow} → READY`.

## Hot-path discipline

Code in `web` (and any tx/data adjacent to request flow) sits next to the kernel's hot path:

- No per-request wrapper DTO allocation in pure mode.
- No body copy from `LoanedBuffer` to `byte[]` / `InputStream` on the primary path — codecs operate on `MemorySegment` directly.
- `LoanedBuffer` ownership: handler must release or transfer; after `exchange.respond(response)` the engine owns the response body — caller must NOT release it.
- `HttpHandler.handle` must complete exactly once: respond OR throw `HttpException`, never both.
- Compatibility-mode allocation cost must be measured and documented, never silently applied to pure-mode paths.

## Documentation precedence

When docs disagree, the source-of-truth order is:
1. `docs/adr/*` — long-lived architectural intent
2. `docs/architecture/module-boundaries.md` and `kernel-integration-seams.md` — structural contracts
3. `docs/phases/phase-*.md` — current delivery scope
4. `.github/copilot-instructions.md` — repo-wide review behaviour

Phase semantics: **Phase 0** bootstrap coexistence + Wall integrity; **Phase 1** Exeris-owned ingress; **Phase 2** opt-in Spring compatibility; **Phase 3** tx/context/persistence; **Phase 4** events/flow.

When changes affect ownership model, mode semantics, module contracts, or compatibility guarantees → **trigger an ADR**, don't just edit code.

## Testing expectations

Four layers, choose what the change requires:
1. **Unit** — adapter/wiring/codec/error-mapping logic.
2. **Module integration** — real Spring wiring with collaborators.
3. **Runtime integration** — end-to-end Exeris-hosted: context starts, kernel starts, ingress is Exeris's, Spring handler invoked, response returns through Exeris, shutdown is clean. (Wire-level socket E2E for Phase 1 ingress is still being built — see `docs/phases/phase-1-milestone-status.md`.)
4. **Architecture guards** — ArchUnit-style: `WallIntegrityTest`, `ModuleBoundaryTest`, `*ClasspathGuardTest`, `CompatibilityIsolationGuardTest`. These must stay green; failures here indicate a real architectural regression, not a test problem.

## Conventions to honour

- Package roots are strict: `eu.exeris.spring.boot.autoconfigure.*`, `eu.exeris.spring.runtime.{web,tx,data,actuator}.*`. Nothing under `eu.exeris.spring.*` may sit inside `eu.exeris.kernel.*`.
- Constructor injection over field injection. Immutable config objects. Explicit lifecycle sequencing.
- Don't promote convenience by hiding cost — if compatibility adds heap churn or ownership ambiguity, say so in the code/docs.
- Don't expand `autoconfigure` into runtime logic; don't dump cross-cutting helpers into one module.
