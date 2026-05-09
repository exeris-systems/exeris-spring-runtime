# ADR-021: Gateway-Class Workloads Out of Compatibility Scope

| Attribute       | Value                                                                                                                                          |
|:----------------|:-----------------------------------------------------------------------------------------------------------------------------------------------|
| **Status**      | **ACCEPTED** (drafted and accepted 2026-05-09; single decider — no future gating event; ratified by the PR that introduces this file)          |
| **Deciders**    | Arkadiusz Przychocki                                                                                                                           |
| **Date**        | 2026-05-09                                                                                                                                     |
| **Scope**       | spring (binds `exeris-spring-runtime-web` Compatibility Mode contract; introduces `exeris-spring-runtime-gateway` artefact slot)               |
| **Owning Repo** | `exeris-spring-runtime`                                                                                                                        |
| **Driven By**   | ADR-006 (Spring-Free Kernel Boundary), ADR-010 (Host Runtime Model), ADR-011 (Pure Mode vs Compatibility Mode); downstream migration review (2026-05-09) that surfaced a Compatibility-Mode-coverage misclassification of Spring Cloud Gateway MVC |
| **Compliance**  | [Module Boundaries](../architecture/module-boundaries.md), [Phase 0 Invariants](../phases/phase-0-invariants.md), [Phase 1 Invariants](../phases/phase-1-invariants.md) |

## Context and Problem Statement

Phase 2 Compatibility Mode (`exeris-spring-runtime-web` `*.compat.*`) bridges Spring MVC's `@RestController` / `@RequestMapping` style onto the Exeris-owned request path. It does so deliberately *without* a servlet runtime — `org.springframework.http.server.ServerHttpRequest`/`ServerHttpResponse` (from `spring-web`) replace the servlet API, and `CompatibilityIsolationGuardTest#compatPackage_mustNotDependOnSpringWebMvc` blocks any drift into `org.springframework.web.servlet..`.

A separate class of Spring workload — **API gateways, edge proxies, and request-forwarding routers** — does not fit into that bridge. Spring Cloud Gateway (the canonical example) ships in two forms:

- **Spring Cloud Gateway MVC** (`spring-cloud-starter-gateway-mvc`) — uses `RouterFunction<ServerResponse>` / `HandlerFunctionAdapter` / `RouterFunctionMapping` from `org.springframework.web.servlet.function..`. These types are servlet-bound: `RouterFunctionMapping` registers itself with `DispatcherServlet`. Tomcat embed and `jakarta.servlet-api` arrive transitively.
- **Spring Cloud Gateway (reactive)** (`spring-cloud-starter-gateway`) — uses `RouteLocator` / `GlobalFilter` / `GatewayFilter` chain on top of WebFlux. Pulls Reactor + Netty.

Both forms are banned by Phase 0 / Phase 1 invariants on the Pure Mode classpath:

- `WallIntegrityTest#noClassAnywhere_mustNotImportServletApi` — `jakarta.servlet..` (MVC form)
- `WallIntegrityTest#noClassAnywhere_mustNotImportReactorOrNetty` — `io.netty..`, `reactor..` (reactive form)
- `PureModeClasspathGuardTest` — same bans replicated per Pure Mode module
- `CompatibilityIsolationGuardTest#compatPackage_mustNotDependOnSpringWebMvc` — `org.springframework.web.servlet..` (MVC form's `RouterFunctionMapping` lives here)

**This is not a `RouterFunction`-vs-`@RequestMapping` mismatch alone.** Even if Compatibility Mode added a `RouterFunction` resolution path, the gateway *runtime* — filter pipeline, route locator, backend HTTP client (`RestClient` / `WebClient`), circuit breakers, rate limiters, request-body modification — sits on top of the framework's own runtime, not on `RequestMappingHandlerAdapter`. Pulling Spring Cloud Gateway's class set onto the Exeris path would mean reimplementing the gateway runtime, not bridging beans. That is a separate product, not a Compatibility Mode extension.

### What triggered this ADR

A downstream migration review (2026-05-09) classified Spring Cloud Gateway MVC as "covered by existing Compatibility Mode, no upstream change required" for an api-gateway service planned to run on the Exeris runtime. A direct check against `CompatibilityIsolationGuardTest`, `PureModeClasspathGuardTest`, and the `org.springframework.web.servlet.function..` package surface falsified both clauses of that premise: the MVC variant pulls servlet API, registers in `DispatcherServlet`, and uses a class set the compat package is explicitly forbidden from importing. The same misclassification pattern is foreseeable for any future migration, so the architectural answer needs to live as a public ADR — not as a corrected note inside the originating product's planning history.

## 🏁 The Decision

**Gateway-class workloads — request-forwarding routers, edge proxies, filter-pipeline middlewares — are out of scope for Pure Mode and Compatibility Mode in `exeris-spring-runtime-web`. They are addressed, if at all, by a dedicated artefact: `exeris-spring-runtime-gateway` (Phase 5).**

**Concrete obligations:**

1. **Compatibility Mode does not bridge `RouterFunction`-style dispatch.** The `compat.*` package remains scoped to `@RequestMapping` / `@RestController` and the argument-resolver / return-value-handler set already implemented. `org.springframework.web.servlet..` (including `org.springframework.web.servlet.function..`) stays banned by `CompatibilityIsolationGuardTest`.
2. **Spring Cloud Gateway (both flavours) is not a supported dependency on the Exeris path.** Adding `spring-cloud-starter-gateway` or `spring-cloud-starter-gateway-mvc` to a Pure Mode module is a build failure via `PureModeClasspathGuardTest` (servlet API or Netty/Reactor); attempting to coexist with Exeris-owned ingress in the same JVM is an ownership inversion regression.
3. **Edge / gateway functionality, when needed in Exeris-owned form, is delivered by `exeris-spring-runtime-gateway`** — a Phase 5 artefact that ships its own routing primitives, filter chain, and HTTP forwarder on the Exeris Pure Mode path. It is **not** a Spring Cloud Gateway compatibility bridge; it does not promise route-DSL parity. Scope and exit criteria live in `docs/phases/phase-5-edge-gateway.md`.
4. **Mode declaration extends to gateway workloads.** A change introducing gateway-style behaviour declares mode `GATEWAY_MODE` (Phase 5) in addition to the existing `PURE_MODE` / `COMPATIBILITY_MODE` / `MIXED` taxonomy from ADR-011. Gateway Mode is a peer of Pure Mode (Exeris-owned ingress; no servlet/reactive runtime), not a Compatibility Mode extension.
5. **Workloads that need full Spring Cloud Gateway today run outside the Exeris runtime.** A documented exception: a service that hard-depends on Spring Cloud Gateway DSL, filter ecosystem, or upstream-supplied route YAML may run on its native stack (Tomcat for MVC variant; Netty for reactive variant) as a separate process. It does not mix with an Exeris-owned process; it does not invert ownership inside one. Migration off this exception is opt-in when `exeris-spring-runtime-gateway` reaches a sufficient scope for the workload.

## Mode boundary at a glance

| Workload class | Mode | Module | Notes |
|:---|:---|:---|:---|
| Greenfield Pure Mode handlers | `PURE_MODE` | `exeris-spring-runtime-web` (`@ExerisRoute`) | Phase 1 |
| Spring MVC `@RestController` / `@RequestMapping` | `COMPATIBILITY_MODE` | `exeris-spring-runtime-web` (`*.compat.*`) | Phase 2 — covered |
| Spring MVC `RouterFunction<ServerResponse>` / Spring Cloud Gateway MVC | **Out of scope** | — | Use native stack OR Phase 5 |
| Spring WebFlux / Spring Cloud Gateway (reactive) | **Out of scope** | — | Use native stack OR Phase 5 |
| Edge gateway / forwarder on Exeris Pure Mode | `GATEWAY_MODE` | `exeris-spring-runtime-gateway` | Phase 5 — planned |

## Consequences

### ✅ Positive Outcomes

- **[+] Compatibility Mode keeps a bounded, achievable scope.** Phase 2 closes against a deliverable Compat surface — `@RestController` style — without absorbing the Spring Cloud Gateway runtime as a hidden cost.
- **[+] No quiet servlet/Netty regression.** A future PR that adds Spring Cloud Gateway as a dependency will fail `PureModeClasspathGuardTest` or `CompatibilityIsolationGuardTest`. The ADR makes the failure expected behaviour, not "the test is wrong".
- **[+] Gateway capability becomes its own product, not a debt accumulator inside `web`.** `exeris-spring-runtime-gateway` ships with its own contract (route predicates, filter chain primitives, forwarder) that can be designed for the Exeris hot path from day one, instead of bolted onto `compat.*`.
- **[+] Migration plans that misclassified gateway-class workloads have a clean unwinding path.** The affected workload either runs native Spring Cloud Gateway on its own stack as a documented exception, or waits on Phase 5. Either choice respects the runtime ownership model.

### ⚠️ Trade-offs

- **[~] Loss of "100% on Exeris runtime" claim while a workload uses native Spring Cloud Gateway.** This is honest: an SCG process is a separate runtime owner; calling its inclusion "Exeris-owned" would be a host-runtime overclaim per the kernel-spring ownership boundary policy.
- **[~] Phase 5 enters the roadmap as a 1.0 preview.** Default-off, gated by adoption; graduates inside 1.0.x. This adds a new bounded preview scope; it does not block the 1.0 GA story (Pure Mode + bounded Compat).

### ❌ Negative Outcomes

- **[−] Downstream products that planned `exeris.runtime.web.mode=compatibility` for gateway-class workloads need rework.** Supersession of any such decision is a downstream-side change owned by the affected product's own planning trail. The first observed instance was costless to unwind because no real route had landed yet — late corrections will be more expensive.

## Compliance / Enforcement

- `CompatibilityIsolationGuardTest` (`exeris-spring-runtime-web` test scope) — already enforces no `org.springframework.web.servlet..` in `compat.*`. No new rule needed.
- `PureModeClasspathGuardTest` (per Pure Mode module) — already enforces no `jakarta.servlet..`, no `io.netty..`, no `reactor..`, no `org.springframework.web.reactive..`, no `org.springframework.web.servlet.DispatcherServlet`. No new rule needed.
- `WallIntegrityTest` (`exeris-spring-boot-autoconfigure` test scope) — same.

The ADR's contribution is **the architectural rationale** that turns a Spring Cloud Gateway dependency-add from "test-failing PR" into "ADR-violating PR". Reviewers and routine ADR scanners cite ADR-021 by number when blocking such PRs.

## References

- ADR-006 — Spring-Free Kernel Boundary (The Wall): `exeris-docs/adr/ADR-006 Spring-Free Kernel Boundary.md`
- ADR-010 — Host Runtime Model: `docs/adr/ADR-010 Host Runtime Model.md`
- ADR-011 — Pure Mode vs Compatibility Mode: `docs/adr/ADR-011-pure-mode-vs-compatibility-mode.md`
- Phase 0 Invariants: `docs/phases/phase-0-invariants.md`
- Phase 1 Invariants: `docs/phases/phase-1-invariants.md`
- Phase 5 Edge Gateway (planned): `docs/phases/phase-5-edge-gateway.md`
