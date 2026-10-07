# Policy: Bootstrap Invariance and Lifecycle Coordination

The startup sequence coordinates Spring ApplicationContext lifecycle with Exeris Kernel initialization without split-brain coordination.

## The Rule

- **Strict Bootstrap Order**:
  1. Spring `refresh()` completes bean definitions and initialization.
  2. `ExerisRuntimeLifecycle.start()` (`SmartLifecycle`) triggers kernel bootstrap.
  3. `KernelBootstrap.bootstrap()` resolves SPI providers via `ServiceLoader`.
  4. Kernel DAG runs:
     - `FOUNDATION`: Memory (sequential)
     - `SERVICES`: Crypto, Persistence, Graph, Transport (parallel via `StructuredTaskScope`)
     - `RUNTIME`: Events, Flow, HTTP (parallel)
     - `KERNEL READY`
  5. Route handlers register with Exeris HTTP engine.
  6. `HttpServerEngine` binds to network ingress.
- **Shutdown Reversal**: Graceful shutdown unwinds the sequence in reverse order.
- **No Split-Brain**: Lifecycle coordination remains unified under `ExerisRuntimeLifecycle`. Kernel components must not be started piecemeal or out-of-order by individual Spring beans.
