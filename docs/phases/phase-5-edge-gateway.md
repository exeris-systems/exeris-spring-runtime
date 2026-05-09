# Phase 5: Edge Gateway on Pure Mode (Opt-In Preview)

**Status:** Planned (1.0 preview, default-off; graduation in 1.0.x on real adoption)
**Depends on:** Phase 1 closed (Pure Mode ingress proven on the wire)
**Milestone:** M5
**Mode:** Gateway Mode — opt-in, never default; **not** a Spring Cloud Gateway compatibility bridge
**Governing ADRs:** ADR-006 (The Wall), ADR-010 (Host Runtime Model), ADR-011 (Pure vs Compat), [ADR-021](../adr/ADR-021%20Gateway-Class%20Workloads%20Out%20of%20Compatibility%20Scope.md) (Gateway-class out of Compat)

---

## Why this phase exists

ADR-021 establishes that gateway-class workloads (request-forwarding routers, edge proxies, filter pipelines) are out of scope for Pure Mode and Compatibility Mode in `exeris-spring-runtime-web`. Two failure modes drove that decision:

- Spring Cloud Gateway MVC pulls `jakarta.servlet..` and registers in `DispatcherServlet` — banned by Phase 0/1 classpath guards.
- Spring Cloud Gateway (reactive) pulls Netty + Reactor + WebFlux — same bans.

There remains a real need for **Exeris-owned edge ingress that forwards to backends with a controlled filter pipeline**. Phase 5 delivers that as a separate artefact — `exeris-spring-runtime-gateway` — designed for the Exeris hot path from day one, **without** promising Spring Cloud Gateway DSL parity.

---

## Goal

> Edge ingress enters through Exeris. A bounded route-and-filter pipeline runs on the Pure Mode hot path. The HTTP forwarder uses an Exeris-friendly client. No Tomcat. No DispatcherServlet. No Netty. No Reactor. No WebFlux.

Phase 5 succeeds if a Spring application can stand up an edge gateway on Exeris-owned ingress that **forwards traffic to upstream services with route predicates, header rewriting, retry, and basic resilience** — all without leaving the Pure Mode invariants.

---

## Bounded Scope (in vs out)

This is the most important table in the document. Scope drift here is what would re-create the SCG problem.

| Capability | In scope (M5) | Out of scope (deferred or never) |
|:---|:---|:---|
| Path / method / host predicate routing | ✅ In | — |
| Header rewriting (request + response) | ✅ In | — |
| Synchronous HTTP forwarder using kernel-friendly client | ✅ In | — |
| Per-route retry with bounded attempts | ✅ In | — |
| Per-route circuit breaker (Resilience4j integration) | ✅ In | — |
| Per-route rate limiting (kernel telemetry-backed counter) | ✅ In | — |
| Path rewriting / strip-prefix | ✅ In | — |
| Per-route auth predicate (`Authorization` shape check) | ✅ In | OAuth/OIDC token introspection — out (use `exeris-spring-runtime-actuator` + service mesh) |
| Telemetry: per-route latency / error / forward count via `TelemetrySink` | ✅ In | — |
| Spring Cloud Gateway DSL compatibility | ❌ Out | Forever. Different runtime, different contract. |
| Spring Cloud Gateway YAML route configs (`spring.cloud.gateway.routes`) | ❌ Out | Forever. Use Phase 5's own config shape. |
| `RouteLocator` / `GlobalFilter` / `GatewayFilter` interfaces | ❌ Out | Forever. Equivalent contract is provided by `ExerisGatewayRoute` / `ExerisGatewayFilter`, not Spring Cloud Gateway types. |
| WebSocket / SSE forwarding | 🟡 Deferred | Phase 5.x; depends on kernel transport surface |
| HTTP/2 ↔ HTTP/1.1 protocol translation | 🟡 Deferred | Phase 5.x |
| Request/response body modification beyond header changes | 🟡 Deferred | Phase 5.x; needs careful `LoanedBuffer` semantics |
| Backpressure across forwarder hops | 🟡 Deferred | Phase 5.x; coordinates with kernel PAQS |
| `MultipartFile` forwarding | 🟡 Deferred | Phase 5.x |

The "Forever out" rows are non-negotiable. If a workload requires Spring Cloud Gateway's DSL, filter ecosystem, or YAML route format, it runs on native Spring Cloud Gateway as a documented exception (per ADR-021), not inside Exeris.

---

## Architecture

```
client request
  → Exeris HttpServerEngine (Pure Mode ingress)
  → ExerisHttpDispatcher
  → ExerisGatewayDispatcher                    (replaces app-handler dispatch when route matches)
      → ExerisGatewayRouteRegistry.resolve(method, path, host)
      → ExerisGatewayFilterChain (pre-filters, in declared order)
      → ExerisHttpForwarder.forward(upstream)  (synchronous, virtual-thread-friendly)
      → ExerisGatewayFilterChain (post-filters)
      → ExerisServerResponse → exchange.respond(...)
```

`ExerisGatewayDispatcher` is **not** a replacement for `ExerisHttpDispatcher`; it is a sibling that activates per route. A single application can run Pure Mode handler beans alongside gateway routes.

### Key contracts (sketch)

```java
package eu.exeris.spring.runtime.gateway;

@FunctionalInterface
public interface ExerisGatewayFilter {
    void apply(ExerisGatewayContext ctx, ExerisGatewayFilterChain chain);
}

public record ExerisGatewayRoute(
        String id,
        ExerisGatewayPredicate predicate,
        URI upstream,
        List<ExerisGatewayFilter> filters,
        ExerisRetryPolicy retry,
        ExerisCircuitBreakerPolicy circuitBreaker,
        ExerisRateLimitPolicy rateLimit
) { }

public interface ExerisGatewayRouteRegistry {
    Optional<ExerisGatewayRoute> resolve(HttpMethod method, String path, String host);
}

public interface ExerisHttpForwarder {
    ExerisServerResponse forward(ExerisServerRequest request, ExerisGatewayRoute route);
}
```

These types live in `exeris-spring-runtime-gateway` (new artefact, see "New module"). They are **not** named after or shaped after Spring Cloud Gateway's interfaces — that distance is intentional.

---

## New module

`exeris-spring-runtime-gateway` joins the Maven reactor.

| Aspect | Rule |
|:---|:---|
| Package root | `eu.exeris.spring.runtime.gateway.*` |
| Mode marker | Each component carries `@GatewayMode` (analogous to `@CompatibilityMode` in Phase 2) |
| Allowed deps | `exeris-spring-runtime-web` (read-only — `ExerisServerRequest/Response`, dispatcher hooks); `spring-context`; Resilience4j core (NOT `resilience4j-reactor`); JDK `HttpClient` or `RestClient` for forwarder; `spring-boot-autoconfigure` |
| Banned deps | Same as Pure Mode: `jakarta.servlet..`, `io.netty..`, `reactor..`, `org.springframework.web.reactive..`, `org.springframework.web.servlet..`, `spring-cloud-starter-gateway*`, `org.apache.tomcat.embed:*`, `org.eclipse.jetty:*`, `io.undertow:*` |
| Activation | `exeris.runtime.gateway.enabled=true` (default false). When false, the artefact compiles in but no gateway dispatcher is created and the route registry is empty. |
| Coexistence with `web` | The gateway dispatcher hooks before `ExerisRouteRegistry` resolution: if a gateway route matches, the request is forwarded; otherwise, normal `web` dispatch proceeds. |

A module-local `PureModeClasspathGuardTest` ships in this module too (4 ArchUnit rules × the same banned-package list).

---

## Deliverables

| # | Deliverable | Module | Notes |
|:-:|:------------|:-------|:------|
| 1 | `ExerisGatewayRoute`, `ExerisGatewayPredicate`, `ExerisGatewayContext`, `ExerisGatewayFilter`, `ExerisGatewayFilterChain` | `gateway` | Public contract types |
| 2 | `ExerisGatewayRouteRegistry` (immutable, O(1) resolution) | `gateway` | Built once at startup; no per-request mutation |
| 3 | `ExerisGatewayDispatcher` (Pure Mode dispatcher hook) | `gateway` | Sibling of `ExerisHttpDispatcher`, not a replacement |
| 4 | `ExerisHttpForwarder` (synchronous, virtual-thread-friendly) | `gateway` | Backed by JDK `HttpClient` or Spring `RestClient` — no `WebClient` |
| 5 | Filter set: `HeaderRewriteFilter`, `StripPrefixFilter`, `AuthShapeFilter` | `gateway` | Minimal, additive |
| 6 | Retry / circuit-breaker / rate-limit policies (Resilience4j core) | `gateway` | No reactive Resilience4j |
| 7 | `ExerisGatewayAutoConfiguration` with `@ConditionalOnProperty("exeris.runtime.gateway.enabled", havingValue="true")` | `gateway` | Default-off |
| 8 | `@GatewayMode` marker annotation | shared | Sibling of `@CompatibilityMode` |
| 9 | Module-local `PureModeClasspathGuardTest` | `gateway` (test) | Per-module guard |
| 10 | `ExerisGatewayWireLevelIntegrationTest` (real bind, route, forward, response) | `gateway` (test) | Wire-level proof analogous to Phase 1's wire-level test |
| 11 | Allocation budget test for forward path | `gateway` (test) | Hard budget — number set after first measurement on representative payload |
| 12 | Phase 5 invariants document | docs | Analogous to Phase 0/1 invariants pages |

---

## Activation

```yaml
# application.yml — enable Exeris-owned edge gateway
exeris:
  runtime:
    gateway:
      enabled: true
      routes:
        - id: accounts
          predicate:
            method: [GET, POST]
            path: /api/accounts/**
          upstream: http://accounts-service:8080
          filters:
            - strip-prefix: 2
            - header-rewrite:
                set: { X-Forwarded-For: "${client.remote-address}" }
          retry:
            max-attempts: 2
            backoff: 50ms
          circuit-breaker:
            failure-rate-threshold: 50
            window-size: 20
          rate-limit:
            requests-per-second: 100
```

This shape is **not** Spring Cloud Gateway's YAML format. It is intentionally Exeris's own — simpler, fewer features, but every feature is owned and tested on the Exeris hot path.

---

## Exit Criteria

Phase 5 closes (preview-graduates inside 1.0.x) when all of the following hold:

1. `exeris-spring-runtime-gateway` compiles, tests green, no banned classpath imports.
2. Wire-level test demonstrates: client request → Exeris ingress → gateway route → upstream forward → upstream response → client receives response. End-to-end through real sockets at both legs.
3. Per-route retry, circuit breaker, and rate limiting verified by integration tests with controlled upstream failure modes.
4. Gateway forward path allocation budget is enforced (number TBD after first measurement; the budget exists so regression is loud).
5. `exeris.runtime.gateway.enabled=false` (default) leaves the runtime indistinguishable from Phase 1 + Phase 2 — no gateway types active, no startup overhead.
6. Phase 5 invariants documented (Gateway Mode is a peer of Pure Mode; no servlet/reactive/Netty; the artefact is not an SCG-DSL bridge).
7. At least one downstream service has run Phase 5 in production for a representative period (graduation criterion shared with Phase 4A/4B).

---

## Risks

| Risk | Mitigation |
|:-----|:-----------|
| Scope drift toward Spring Cloud Gateway DSL parity | ADR-021 is the architectural anchor; "Forever out" rows in the scope table are non-negotiable. PRs that re-introduce SCG types fail classpath guards. |
| `RestClient` / JDK `HttpClient` blocking semantics under load | Pin to virtual-thread-only callers; benchmark forward path under concurrent load before graduation; document any pinning hazard. |
| Gateway dispatcher coupling into `web` module | Gateway lives in its own module; `web` does not depend on `gateway`; gateway depends on `web` read-only (`ExerisServerRequest/Response`). |
| `LoanedBuffer` ownership across forwarder hops | The forwarder must observe Phase 1 invariant 5 (`LoanedBuffer` ownership transfer). If the response body is forwarded, ownership transfers to the engine via `exchange.respond()`. Body modification is deferred (out of scope) precisely because this is subtle. |
| Operators activate gateway by mistake in production | Default-off via `exeris.runtime.gateway.enabled`; activation requires explicit operator action; bootstrap log line announces gateway mode loudly. |
| Confusion with Phase 2 Compatibility Mode | Marker annotation `@GatewayMode` and a separate auto-configuration class signal at code-review and review-tool time that this is not Compat. ADR-021 cited from package-info. |

---

## Out-of-Phase Items (kept here so they don't drift back in)

These are items that **look** like Phase 5 but are not:

- **Spring Cloud Gateway compatibility shim** — explicitly forbidden by ADR-021. Workloads that need SCG run native SCG outside Exeris.
- **Reactive forwarder using `WebClient`** — would require Reactor on Pure Mode classpath. Banned.
- **Servlet-based filter chain** — would require `jakarta.servlet`. Banned.
- **YAML compatibility layer that maps `spring.cloud.gateway.routes` to `exeris.runtime.gateway.routes`** — would re-introduce SCG vocabulary as a contract surface. Workloads with SCG YAML configs migrate by hand or stay on native SCG.
