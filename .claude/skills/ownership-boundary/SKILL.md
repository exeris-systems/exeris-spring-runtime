---
name: ownership-boundary
description: Enforce ownership truth — Spring is application framework; Exeris is runtime owner. Refuse inversion to servlet/reactive/JDBC-first runtime and split-brain lifecycle.
disable-model-invocation: true
---

<!-- DO NOT EDIT. Generated from .agents/workflows/ownership-boundary.md by agents_render.py
     (exeris-systems/exeris-agents; agents-md-schema.md rule 7). Edit the source. -->
Audit this change against ownership truth.

Ownership principle:
> Spring is the application framework. Exeris is the runtime owner.

- Spring owns: DI, config binding, bean lifecycle.
- Exeris owns: transport ingress, request lifecycle, backpressure, off-heap memory, provider discovery (`ServiceLoader`), telemetry hot path.

Bootstrap order is invariant:
Spring `refresh()` → `ExerisRuntimeLifecycle.start()` → `KernelBootstrap.bootstrap()` (`ServiceLoader` discovers providers, DAG initialises, `KERNEL READY`) → handlers register → `HttpServerEngine` binds. Shutdown reverses exactly.

Change:
$ARGUMENTS

Please review:
1. Does this change route request path through servlet / Netty / Reactor instead of Exeris `HttpHandler` / `HttpExchange`?
2. Does this change replace `ServiceLoader`-based provider discovery with Spring IoC lookup?
3. Does this change assume JDBC-first persistence ownership instead of `PersistenceEngine` / `ConnectionFactory` over `PersistenceConnection`?
4. Does this change introduce a fake "host-runtime" claim (e.g. starter framing instead of host-runtime integration)?
5. Does this change introduce a hidden fallback path that lets servlet/reactive runtime own request lifecycle?
6. Does this change break the bootstrap order invariant?
7. Does this change split kernel lifecycle across two coordinators (split-brain)?
8. Minimal correction if ownership truth is at risk.
