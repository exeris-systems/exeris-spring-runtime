# Reference: Architecture and Kernel Integration Seams

`exeris-spring-runtime` bridges the Exeris Kernel SPI to Spring framework components. Consult `docs/architecture/kernel-integration-seams.md` for complete class-level documentation.

## Kernel SPI Seams

| Kernel SPI | Bridge Class | Owning Module | Key Discipline |
|:---|:---|:---|:---|
| `HttpHandler` / `HttpExchange` | `ExerisHttpDispatcher`, `ExerisServerRequest`/`Response`, `ExerisRouteRegistry` | `exeris-spring-runtime-web` | Pure mode: zero body-copy from `LoanedBuffer`, codecs operate on `MemorySegment` directly. |
| `ConfigProvider` | `ExerisSpringConfigProvider` | `exeris-spring-boot-autoconfigure` | Registered via `META-INF/services`, priority 150. |
| `SubsystemProvider` / bootstrap | `ExerisRuntimeLifecycle` (`SmartLifecycle`) | `exeris-spring-boot-autoconfigure` | Orchestrates Spring refresh → Kernel bootstrap → `HttpServerEngine.start()`. |
| `KernelProviders` | `ExerisContextHolder` | `exeris-spring-runtime-web` | Backed by `ScopedValue` slots; no `ThreadLocal` on hot path. |
| `TelemetrySink` → Micrometer | `ExerisActuatorTelemetryBridge` (`MeterBinder`) | `exeris-spring-runtime-actuator` | Read-only bridge; never owns request data-plane. |
| `HttpRoutePolicy` | `ExerisHttpSecurity` + `ExerisRoutePolicyCompiler` | `exeris-spring-runtime-web` | Compiled at startup; kernel enforces, runtime compiles (ADR-063). |
| `PersistenceEngine` / `ConnectionFactory` | `ExerisPlatformTransactionManager`, `ExerisDataSource` | `exeris-spring-runtime-tx` / `-data` | `PlatformTransactionManager` over `PersistenceConnection`. No HikariCP ownership. |
| `GraphEngine` / `GraphSession` | `ExerisGraphTemplate` + `@ExerisGraphQuery` | `exeris-spring-runtime-graph` | Phase 4C (ADR-030). Default-off via `exeris.runtime.graph.enabled`. No Spring Data Neo4j. |

## Invariant Bootstrap Sequence

The bootstrap order is strictly sequenced:
1. Spring Application Context `refresh()`.
2. `ExerisRuntimeLifecycle.start()` invoked by Spring `SmartLifecycle`.
3. `KernelBootstrap.bootstrap()` discovers SPI providers via `ServiceLoader`.
4. Kernel Bootstrap DAG executes:
   - `FOUNDATION`: Memory (sequential)
   - `SERVICES`: Crypto & Persistence & Graph & Transport (parallel via `StructuredTaskScope`)
   - `RUNTIME`: Events & Flow & HTTP (parallel)
   - `KERNEL READY`
5. Web route handlers register with the kernel.
6. `HttpServerEngine` binds to network port.

Shutdown reverses this sequence exactly.
