# Kernel Integration Seams

This is the primary engineering reference for how Exeris kernel SPI interfaces
map to Spring integration points. Engineers implementing features in this
repository must read this document first.

**Kernel version:** 0.5.0-SNAPSHOT  
**Kernel package root:** `eu.exeris.kernel.spi.*`

---

## Overview

The Exeris kernel exposes integration through six primary SPI categories.
Each one has a corresponding integration strategy in this repository.

```
Kernel SPI               →    Integration Layer               →    Spring Side
─────────────────────────────────────────────────────────────────────────────────
HttpHandler              →    ExerisHttpDispatcher            →    Spring bean handler
HttpExchange             →    ExerisServerRequest/Response    →    HandlerMethod args (Phase 2)
ConfigProvider           →    ExerisSpringConfigProvider      →    Spring Environment
SubsystemProvider        →    SpringSubsystemBridge           →    ApplicationContext lifecycle
KernelProviders          →    ExerisContextHolder             →    ScopedValue propagation
TelemetryProvider        →    ExerisActuatorTelemetryBridge   →    Micrometer MeterRegistry
PersistenceProvider      →    ExerisPersistenceAdapter        →    DataSource / @Transactional
```

---

## Seam 1: HTTP Handler Bridge

**Kernel SPI:** `eu.exeris.kernel.spi.http.HttpHandler`

```java
@FunctionalInterface
public interface HttpHandler {
    void handle(HttpExchange exchange) throws HttpException;
}
```

**Contract:**
- Called on a virtual thread (one per request).
- Must use exactly one completion path: either call `exchange.respond(response)` once and return, or throw `HttpException` and let the engine respond.
- Must never both respond and throw for the same request.
- Must not use `ThreadLocal`. Context propagation via `ScopedValue`.
- Throwing `HttpException` → engine sends error response.

**Integration strategy (Pure Mode):**

`ExerisHttpDispatcher` implements `HttpHandler` and is registered with the kernel's
`HttpServerEngine`. On each invocation it:

1. Reads `exchange.request()` to get `HttpRequest` (header map, method, path, body `LoanedBuffer`).
2. Resolves the matching Spring-managed handler bean via `ExerisRouteRegistry`.
3. Invokes the handler bean's designated method.
4. Completes exactly once: writes result/error to `exchange.respond(response)` and returns, or throws `HttpException` for engine-owned error response.

No `DispatcherServlet`, no `HandlerMapping`, no servlet API involved in pure mode.

```
HttpExchange arrives on VT
    → ExerisHttpDispatcher.handle(exchange)
        → ExerisRouteRegistry.resolve(path, method)
        → handler.invoke(ExerisServerRequest)
        → ExerisResponseBuilder → HttpResponse
        → exchange.respond(response)       ← ownership of LoanedBuffer transferred to engine
```

**Integration strategy (Compatibility Mode, Phase 2):**

`ExerisCompatDispatcher` wraps the above and additionally:

1. Uses Spring handler infrastructure directly (`RequestMappingHandlerMapping` + `RequestMappingHandlerAdapter`) without `DispatcherServlet` as a canonical runtime component.
2. Adapts `HttpRequest`/`HttpResponse` through compatibility request/response adapters for controller invocation.
3. Maps supported return types (for Phase 2 scope, including `ResponseEntity`) back to `HttpResponse`.

This path is in `eu.exeris.spring.runtime.web.compat.*` and activates only when
`exeris.runtime.web.mode=compatibility` is set.

---

## Seam 2: HTTP Exchange — Request and Response

**Kernel SPI:**
- `eu.exeris.kernel.spi.http.HttpExchange`
- `eu.exeris.kernel.spi.http.HttpRequest`
- `eu.exeris.kernel.spi.http.HttpResponse`
- `eu.exeris.kernel.spi.memory.LoanedBuffer` (body carrier)

**Key facts about `HttpRequest`:**
- Fully parsed before `handle()` is called.
- `body()` returns a `LoanedBuffer` — an off-heap memory reference.
- The handler must release or transfer ownership of the body buffer.

**Key facts about `HttpResponse`:**
- `body()` returns a `LoanedBuffer` — the engine takes ownership on respond.
- Caller must NOT release the buffer after calling `exchange.respond(response)`.

**Integration rule: no body copy in pure mode.**

The `ExerisServerRequest` thin view must provide body access via `LoanedBuffer.segment()`
without copying to `byte[]` or `InputStream`. Codec integration must operate on
`MemorySegment` directly.

Body copy to heap is only acceptable in:
- Compatibility mode (explicitly documented per usage).
- Mulipart handling where byte[] is structurally unavoidable (with allocation counted).

---

## Seam 3: Configuration Bridge

**Kernel SPI:** `eu.exeris.kernel.spi.config.ConfigProvider`

Loaded via `ServiceLoader` by `KernelBootstrap` in `exeris-kernel-core`.
The provider with the highest `priority()` value wins.

**Integration strategy:**

`ExerisSpringConfigProvider implements ConfigProvider` maps Spring `Environment`
property sources to the kernel config model.

```
ServiceLoader.load(ConfigProvider.class)
    → finds ExerisSpringConfigProvider (priority = 150)
    → ExerisSpringConfigProvider.kernelSettings()
        → reads exeris.runtime.* from Spring Environment
        → returns KernelSettings record
```

Priority convention:
- Community `ConfigProvider`: priority 100
- `ExerisSpringConfigProvider`: priority 150 (takes precedence over community defaults)

The `ExerisSpringConfigProvider` is registered in:
`META-INF/services/eu.exeris.kernel.spi.config.ConfigProvider`

This makes Spring the configuration source for the kernel — without making the kernel
aware of Spring. The kernel only knows about `ConfigProvider`.

---

## Seam 4: Lifecycle / Subsystem Bridge

**Kernel SPI:** `eu.exeris.kernel.spi.bootstrap.SubsystemProvider` / `Subsystem`

**Two complementary strategies:**

### Strategy A — Spring coordinates Exeris bootstrap (preferred for Phase 0/1)

Spring `ApplicationContext` starts first. `ExerisRuntimeLifecycle implements SmartLifecycle`
triggers `KernelBootstrap.start()` at the appropriate Spring lifecycle phase.

```
Spring refresh() completes
    → SmartLifecycle.start() ordered after bean wiring
    → ExerisRuntimeLifecycle.start()
        → KernelBootstrap.bootstrap(configProvider)
        → ServiceLoader discovers SubsystemProviders
        → all subsystems initialise in DAG order
        → KERNEL READY
    → ExerisHttpDispatcher registered with HttpServerEngine
    → HttpServerEngine.start() binds port
```

Shutdown:
```
JVM shutdown hook / Spring close()
    → SmartLifecycle.stop() in reverse order
    → HttpServerEngine.stop() (no new connections)
    → in-flight requests drain (timeout)
    → KernelBootstrap.shutdown()
    → all subsystems cleaned up in reverse DAG order
```

### Strategy B — Exeris as a SubsystemProvider (advanced, Phase 3+)

A Spring-facing subsystem can implement `SubsystemProvider` to register itself into the
kernel DAG (e.g., a Spring-managed event bus as an `EventProvider`). This uses
`ServiceLoader` canonically — Spring does not replace discovery but may supply beans
that implement kernel SPI contracts.

---

## Seam 5: Context Propagation

**Kernel SPI:** `eu.exeris.kernel.spi.context.KernelProviders`

`KernelProviders` contains `ScopedValue` slots for all runtime providers. They are bound
once during bootstrap and inherited by all virtual threads in scope.

**Key slots relevant to this integration:**

| Slot | Type | Spring integration |
|:-----|:-----|:------------------|
| `KernelProviders.TELEMETRY_SINKS` | `List<TelemetrySink>` | Read by actuator bridge |
| `KernelProviders.PERSISTENCE_ENGINE` | `PersistenceEngine` | Read by tx/data modules |
| `KernelProviders.HTTP_PROVIDER` | `HttpProvider` | Accessed by web module for engine ref |

**ScopedValue vs ThreadLocal rule:**

This integration layer must NEVER use `ThreadLocal` on any path that runs on virtual threads.
Violation: Thread pinning + context leakage across VT continuations.

Spring's `SecurityContextHolder`, `TransactionSynchronizationManager`, and `RequestContextHolder`
all default to `ThreadLocal`. These must be bridged only in compatibility mode and must be
explicitly isolated per request scope.

**Compatibility mode context bridge (Phase 2/3):**

`ExerisThreadLocalBridge` wraps handler invocations in compatibility mode to:
1. Bind `SecurityContext` to `ThreadLocal` for the duration of the handler call.
2. Clear it deterministically after handler return or exception.
3. This is isolated in `eu.exeris.spring.runtime.web.compat.context.*`.

---

## Seam 6: Telemetry Bridge

**Kernel SPI:**
- `eu.exeris.kernel.spi.telemetry.TelemetryProvider`
- `eu.exeris.kernel.spi.telemetry.TelemetrySink`
- `eu.exeris.kernel.spi.telemetry.KernelEvent`

**Integration strategy:**

`ExerisActuatorTelemetryBridge implements MeterBinder` reads from the `TelemetrySink`
available via `KernelProviders.TELEMETRY_SINKS` and bridges kernel metrics (counters,
gauges, latencies) to Micrometer `MeterRegistry`.

```
Micrometer MeterRegistry (Actuator)
    ← ExerisActuatorTelemetryBridge.bindTo(registry)
        ← KernelProviders.TELEMETRY_SINKS.get()
            → TelemetrySink.counters() etc.
```

The `TelemetrySink` is the Exeris telemetry model. The actuator module must not replace it,
redefine event envelopes, or bypass `KernelEvent` encoding. It may only read and translate.

---

## Seam 7: Persistence Bridge (Phase 3)

**Kernel SPI:**
- `eu.exeris.kernel.spi.persistence.PersistenceProvider`
- `eu.exeris.kernel.spi.persistence.PersistenceEngine`
- `eu.exeris.kernel.spi.persistence.ConnectionFactory`
- `eu.exeris.kernel.spi.persistence.PersistenceConnection`
- `eu.exeris.kernel.spi.persistence.QueryExecutor`
- `eu.exeris.kernel.spi.persistence.QueryResult`
- `eu.exeris.kernel.spi.security.StorageContext`

**Integration strategy (Phase 3):**

Two-level approach:

**Level 1 — Native Exeris Repositories (preferred):**

Application code implements repository interfaces against `PersistenceEngine` + `QueryExecutor`
directly. No `DataSource`. No `JdbcTemplate`. Spring provides the bean lifecycle; Exeris
provides the execution semantics.

**Level 2 — JDBC Compatibility Bridge (last resort, ADR required):**

`ExerisDataSource implements javax.sql.DataSource` wraps `ConnectionFactory` to provide
JDBC compatibility for frameworks that cannot otherwise adapt. This is explicitly the
compatibility layer and must never be presented as the canonical persistence path.

**Transaction integration:**

`ExerisPlatformTransactionManager implements PlatformTransactionManager` binds
`PersistenceConnection` to the current virtual thread scope for the duration of a
Spring `@Transactional` method, using `ScopedValue` rather than `TransactionSynchronizationManager`
`ThreadLocal` where possible. See `exeris-spring-runtime-tx` for the implementation plan.

---

## Summary Table

| Kernel SPI | Spring concept | Integration class | Module | Phase |
|:-----------|:--------------|:-----------------|:-------|:------|
| `HttpHandler` | Handler bean invocation | `ExerisHttpDispatcher` | `web` | 1 |
| `HttpExchange` | Request/Response model | `ExerisServerRequest`, `ExerisServerResponse` | `web` | 1 |
| `ConfigProvider` | `Environment` / `@ConfigurationProperties` | `ExerisSpringConfigProvider` | `autoconfigure` | 0 |
| `SubsystemProvider` | `SmartLifecycle` | `ExerisRuntimeLifecycle` | `autoconfigure` | 0/1 |
| `KernelProviders` | Context propagation | `ExerisContextHolder` (ScopedValue) | `web` | 1 |
| `TelemetryProvider` / `TelemetrySink` | Micrometer / Actuator | `ExerisActuatorTelemetryBridge` | `actuator` | 1/2 |
| `PersistenceEngine` / `ConnectionFactory` | Repository / DataSource | `ExerisPersistenceAdapter` | `data` | 3 |
| `PlatformTransactionManager` (Spring) | `StorageContext` | `ExerisPlatformTransactionManager` | `tx` | 3 |
