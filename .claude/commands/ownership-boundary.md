---
description: Enforce ownership truth — Spring = application framework; Exeris = runtime owner. Refuse inversion to servlet/reactive/JDBC-first runtime, split-brain lifecycle, fake host-runtime claims, hidden fallback ownership.
argument-hint: PR diff or runtime-ownership-affecting change to audit
---

Audit this change against ownership truth.

Ownership principle (per repo `CLAUDE.md`):
> Spring is the application framework. Exeris is the runtime owner.

- Spring owns: DI, config binding, bean lifecycle.
- Exeris owns: transport ingress, request lifecycle, backpressure, off-heap memory, provider discovery (`ServiceLoader`), telemetry hot path.

A change that quietly inverts this ownership (servlet/Netty/Reactor on the request path, IoC replacing `ServiceLoader`, JDBC-first persistence) is an architectural defect, not a style issue.

Bootstrap order is invariant: Spring `refresh()` → `ExerisRuntimeLifecycle.start()` → `KernelBootstrap.bootstrap()` (`ServiceLoader` discovers providers, DAG initialises, `KERNEL READY`) → handlers register → `HttpServerEngine` binds. Shutdown reverses exactly.

Kernel bootstrap DAG (per `exeris-kernel/docs/subsystems/bootstrap.md`):
```
FOUNDATION: Memory (sequential) → SERVICES: Crypto & Persistence & Graph & Transport (parallel via StructuredTaskScope) → RUNTIME: Events & Flow & HTTP (parallel) → KERNEL READY
```

Change:
$ARGUMENTS

Please review:
1. Does this change route request-path through servlet / Netty / Reactor instead of Exeris `HttpHandler` / `HttpExchange`?
2. Does this change replace `ServiceLoader`-based provider discovery with Spring IoC lookup?
3. Does this change assume JDBC-first persistence ownership instead of `PersistenceEngine` / `ConnectionFactory` over `PersistenceConnection`?
4. Does this change introduce a fake "host-runtime" claim (e.g. Spring Boot starter framing instead of host-runtime integration framing)?
5. Does this change introduce a hidden fallback path that lets servlet/reactive runtime own request lifecycle even when pure mode is configured?
6. Does this change break the bootstrap order invariant (Spring refresh → ExerisRuntimeLifecycle → KernelBootstrap → handlers → HTTP bind)?
7. Does this change split kernel lifecycle across two coordinators (split-brain)?
8. Minimal correction if ownership truth is at risk.

Don't promote convenience by hiding cost — if compatibility adds heap churn or ownership ambiguity, say so in the code/docs.
