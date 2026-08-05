# ADR-063: `ExerisHttpSecurity` — Spring-shaped route rules compile onto the kernel's authorization decision

| Attribute       | Value                                                                                                                                                                                                                          |
|:----------------|:---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **Status**      | **ACCEPTED** (drafted and accepted 2026-08-05; single decider — no future gating event; ratified by the PR that introduces this file; convention documented in `CLAUDE.md` §"ADR status convention in this repo")                |
| **Deciders**    | Arkadiusz Przychocki                                                                                                                                                                                                           |
| **Date**        | 2026-08-05                                                                                                                                                                                                                     |
| **Scope**       | spring/security (`exeris-spring-runtime-web`; binds `HttpKernelProviders.HTTP_ROUTE_POLICY`; no kernel SPI changes)                                                                                                             |
| **Owning Repo** | `exeris-spring-runtime`                                                                                                                                                                                                        |
| **Driven By**   | Kernel ADR-061 §Engineering Protocol 5 — *"A follow-up ADR is required before any `exeris-spring-runtime` binding"* — and ADR-041's supplement, which closed the compat fail-open gaps but left per-path rules unsupported       |
| **Compliance**  | [ADR-011](ADR-011-pure-mode-vs-compatibility-mode.md), [ADR-041](ADR-041-compat-resource-server-security-under-none.md), [Kernel Integration Seams](../architecture/kernel-integration-seams.md), [Compat Spring Security support](../architecture/compat-spring-security-support.md) |

## Context and Problem Statement

A brownfield Spring application declares its authorization as per-path rules in a
`SecurityFilterChain`: `requestMatchers("/api/orders/**").hasRole("USER")`, `anyRequest().authenticated()`.
Exeris owns HTTP ingress and runs with no servlet container, so there is no `FilterChainProxy` and the
chain never executes. Since 0.7.0 the runtime **fails startup** when it finds one (ADR-041 supplement),
which stops the fail-open but leaves the application with nowhere to put per-path rules.

Kernel 0.11.0 (ADR-061) supplied the missing primitive. `HttpRoutePolicy.requirementFor(method, path)`
returns a `RouteRequirement`, `RouteAuthorizationEnforcer.decide(...)` yields `ADMIT` /
`UNAUTHENTICATED` / `FORBIDDEN`, and the enforcer was deliberately placed in **Core** rather than in the
Community driver so that — in that ADR's own words — *"a future `exeris-spring-runtime` DSL translates
onto it instead of building a second authorization mechanism"*. It then declared the DSL out of its own
scope and gated it on this document.

The question this ADR answers is therefore not *whether* to support per-path rules, but **where the
decision lives** — and, given the answer, which Spring-shaped constructs can honestly be offered on top
of it.

## 🏁 The Decision

**`ExerisHttpSecurity` is a compiler, not an enforcement mechanism.**

An application declares rules in an `ExerisHttpSecurity` bean. `exeris-spring-runtime-web` compiles them
**once, at startup**, into a single `HttpRoutePolicy` and binds it into
`HttpKernelProviders.HTTP_ROUTE_POLICY`. Every authorization decision is then taken by the kernel on the
admission path, before `ExerisHttpDispatcher` or `ExerisCompatDispatcher` is invoked.

```
ExerisHttpSecurity bean (application)
    → compiled at startup → HttpRoutePolicy
        → bound into HttpKernelProviders.HTTP_ROUTE_POLICY
            → kernel admission path: RouteAuthorizationEnforcer.decide(...)
                → ADMIT → dispatcher → Spring handler
                → UNAUTHENTICATED → 401     (never reaches Spring)
                → FORBIDDEN       → 403     (never reaches Spring)
```

The runtime contributes translation and validation. It contributes no decision.

### Obligations

1. **No second decision layer.** Neither dispatcher evaluates route rules. A refused request is refused
   by the kernel, and the dispatchers never see it. Guarded by an ArchUnit rule asserting that no
   `*.web.*` production class references `RouteAuthorizationEnforcer` outside the compilation seam.
2. **Compile once.** `requirementFor` runs on the admission path of every request and ADR-061 requires it
   to be allocation-free, returning pre-built `RouteRequirement` instances. The compiled form is built at
   startup and is immutable; no `PathPattern` parsing, map boxing, or `RouteRequirement` construction
   happens per request. This is a contract requirement, not an optimisation.
3. **The unmatched route has no default.** The DSL requires an explicit answer. See §"The unmatched
   route" — this is the obligation most likely to cause a production incident if left implicit.
4. **Scope-shaped predicates only.** See §"Roles are not offered at the edge".
5. **Mode-neutral.** The policy governs ingress for Pure and Compatibility Mode alike. It is not a compat
   feature and does not live in `*.compat.*`.
6. **Absent bean means absent policy.** With no `ExerisHttpSecurity` bean the slot is left unbound and the
   kernel applies no per-route requirement — identical to a kernel with no policy at all. Declaring
   nothing changes nothing.

## Roles are not offered at the edge

`RouteRequirement` has four kinds — `PERMIT_ALL`, `AUTHENTICATED`, `ANY_SCOPE`, `ALL_SCOPES` — and
`RouteAuthorizationEnforcer` evaluates them solely through `PrincipalContext.hasScope(...)`. There is no
role kind, and the enforcer never consults `PrincipalContext.roles()`.

So `hasRole("USER")` **cannot be expressed** in the contract this ADR binds to. The DSL exposes
`hasAnyScope(...)` / `hasAllScopes(...)` / `authenticated()` / `permitAll()` and stops there.

The rejected alternative is a `hasRole(x)` that compiles to a scope requirement by convention — say,
requiring a scope named `ROLE_x`. It would look like Spring, and it would install a second authority
model at the edge whose relationship to Spring's own `hasRole` is a naming convention nobody agreed to.
When the two disagree — and they would, the moment an application's authorities come from anywhere but a
literal `scope` claim — the failure is an authorization decision taken on the wrong basis. A DSL that
refuses to express something is recoverable; one that expresses it wrongly is not.

Role checks therefore stay with `@PreAuthorize`, which reads Spring's own `Authentication` authorities
inside the handler. **The two layers answer different questions**: the edge decides whether a caller may
reach a path at all, method security decides whether this principal may perform this operation. That is a
defensible split, not a workaround — but it is a real reduction against a `SecurityFilterChain`, and
[`compat-spring-security-support.md`](../architecture/compat-spring-security-support.md) states it as one.

If demand appears, the honest path is a role kind in `RouteRequirement` — a kernel change, a kernel ADR,
and not something to simulate from this side.

## The unmatched route

`HttpRoutePolicy.unmatched()` is fail-closed: a route the policy does not describe still demands identity.
That is the right default for a security contract, and it is also a trap this runtime must not let an
application walk into unwarned.

The kernel **does not exempt its own endpoints**. A deployment that binds a policy but no
`HTTP_SERVER_HANDLER` still serves the driver's `/health`, `/health/live`, `/health/ready`, `/db/ping`
and `/db/roundtrip` — and ADR-061 is explicit that those are routes like any other, deliberately, because
a driver-local notion of "public" would be a second answer to the question the policy contract now owns.

Spring's idiomatic closing line is `anyRequest().authenticated()`. It maps directly onto fail-closed
unmatched. An application that writes the thing it has always written therefore denies orchestrator
probes against a healthy process, and the symptom is a rollout that never becomes ready.

**Decision:** the DSL requires an explicit unmatched answer — there is no default — and when the answer is
fail-closed and the probe endpoints are not declared, **startup fails** with a message naming them. This
is the same posture as the ADR-041 supplement: a migration that would quietly break must fail loudly at
startup rather than at 3am on a rollout.

## Authority parity is deferred, not claimed

The runtime honours an application-supplied `Converter<Jwt, ? extends AbstractAuthenticationToken>` when
building the Spring `Authentication` (ADR-041), which is how a Keycloak deployment maps
`realm_access.roles` onto authorities. The kernel derives its `PrincipalContext` through
`ClaimsMapper`, whose Community implementation reads `scope`, `scp` and `roles` — fixed claim names.

`ClaimsMapper` is an SPI and its Javadoc calls it *"the only application-customisable mapping point in the
identity pipeline"*. But `CommunityOidcIdentityProvider` constructs `new CommunityClaimsMapper()`
directly, so today there is no route by which an application — or this runtime — supplies its own.

**Consequence, stated rather than papered over:** for a token whose authorities do not come from a literal
`scope`/`scp` claim, the scopes the kernel sees and the authorities Spring sees are derived by two
different mappings from the same token. Edge rules and `@PreAuthorize` can therefore disagree. The blast
radius is bounded — both layers fail closed, so disagreement produces a refusal, not an escalation — but
an operator debugging "why is this 403 when the user has the role" needs to know there are two mappings.

The fix is a kernel slice making `ClaimsMapper` reachable, after which this runtime should supply one
derived from the same converter the Spring side uses, so both layers read one mapping. Until then the DSL
is honest about what it can enforce: scopes as the identity provider emits them.

## Scope and Non-Goals

- **In scope:** the `ExerisHttpSecurity` declaration surface, compilation to `HttpRoutePolicy`, binding
  into the kernel slot, startup validation, and the mapping document for migrating applications.
- **Out of scope — `hasRole` at the edge.** See above. Requires a kernel `RouteRequirement` kind.
- **Out of scope — CORS, CSRF, security headers, form login, session policy.** None is expressible as a
  route requirement; CSRF and session policy are moot under stateless bearer auth, and the rest are edge
  concerns today. Recorded in the support matrix.
- **Out of scope — custom filters in a chain.** There is no chain, so there are no filter positions.
- **Out of scope — supplying a `ClaimsMapper`.** Blocked upstream; see above.
- **No kernel change.** This ADR consumes ADR-061's contract exactly as shipped.

## Alternatives Considered

1. **Enforce in `ExerisCompatDispatcher` after admission.** Rejected. It would put a second authorization
   layer inside the Spring path that can disagree with the kernel's, and would leave every kernel-direct
   SKU on a different mechanism from the Spring-hosted one — the outcome ADR-061 placed its enforcer in
   Core to prevent.
2. **Support Spring's `SecurityFilterChain` by running a `FilterChainProxy` ourselves.** Rejected. It
   requires the servlet API on the request path, which the Pure Mode classpath bans and which inverts
   runtime ownership. This was the "Droga 2" considered when P0 was scoped and set aside then; nothing has
   changed to revive it.
3. **A configuration-file rule surface** (`exeris.runtime.security.rules[0]=…`). Rejected, and not ours to
   revive: ADR-014 §3 rejected config-driven role policy for its drift surface and ADR-061 honours that.
   Rules stay in code.
4. **Ship `hasRole` mapped onto a `ROLE_`-prefixed scope.** Rejected — see §"Roles are not offered at the
   edge".
5. **Do nothing; document that per-path rules are unsupported.** Rejected. It is the current state, and it
   leaves the primary commercial path — brownfield migration — with an unanswerable question, since the
   0.7.0 fail-fast now refuses the chain outright.

## Consequences

### ✅ Positive

- **[+] One decision layer for both consumers.** A Spring-hosted deployment and a kernel-direct SKU are
  authorized by the same Core enforcer, so a rule means the same thing in both.
- **[+] Refused requests never enter Spring.** A 401/403 is decided before the dispatcher, so no handler,
  no argument resolver and no `@Transactional` advice runs for a caller who will be refused.
- **[+] The last unanswerable migration question gets an answer.** With this and the ADR-041 supplement,
  the documented `SecurityConfig` migration path is complete for path rules and bearer authentication.

### ⚠️ Trade-offs

- **[-] Not a drop-in for `SecurityFilterChain`.** The header is "one file, rewritten against a documented
  mapping", not "works unchanged". `hasRole` at the edge, custom filters, CORS and headers have no
  equivalent. This is the position ADR-041's supplement already took; this ADR makes it concrete.
- **[-] Two places to look.** Path rules in `ExerisHttpSecurity`, operation rules in `@PreAuthorize`. A
  reviewer auditing "who can call this" reads both.
- **[-] Startup can now fail on a security misconfiguration** — undeclared unmatched answer, or fail-closed
  without probe routes. Deliberate, and consistent with the rest of the compat security surface.
- **[-] A divergence exists until the `ClaimsMapper` slice lands.** Bounded and fail-closed, but real.

## Compliance / Verification

- **Mode:** mode-neutral. `PureModeClasspathGuardTest` and `CompatibilityIsolationGuardTest` stay green;
  the compilation seam lives outside `*.compat.*`.
- **Unit** — rule compilation: path/method matching, precedence between overlapping patterns, the
  unmatched answer, and the startup failure when fail-closed omits the probe routes.
- **Module integration** — the compiled policy is bound into `HTTP_ROUTE_POLICY` when the bean is present,
  and the slot is left unbound when it is absent.
- **Runtime integration** — against a real kernel: no token → 401, insufficient scope → 403, sufficient
  scope → 200, a `permitAll` route reachable anonymously, and `@PreAuthorize` still enforced inside the
  handler for an admitted request.
- **Allocation** — a test asserting the compiled policy allocates nothing per `requirementFor` call, per
  obligation 2.
- **Architecture guard** — no `*.web.*` production class outside the compilation seam references
  `RouteAuthorizationEnforcer`, per obligation 1.

## Engineering Protocol

1. **Blocked on the kernel pin.** This binds SPI that ships in kernel 0.11.0. The pin cannot move while
   [`exeris-kernel#282`](https://github.com/exeris-systems/exeris-kernel/issues/282) stands — the 0.11.0
   graceful drain waits on idle keep-alive connections and takes ~60 s to shut down, which also breaks a
   currently-green wire-level test. Implementation does not start before the pin moves.
2. **End-to-end verification is gated on the `ClaimsMapper` slice.** Scope requirements can be tested with
   tokens carrying literal `scope` claims before then; parity with an application's `JwtAuthenticationConverter`
   cannot.
3. **The support matrix is updated in the implementing slice**, not this one — it describes what the
   runtime *does*, and updating it first would make the document outrun the code.
4. **A kernel stub** (`exeris-kernel/docs/adr/ADR-063.link.md`) is appropriate on the ADR-030 precedent:
   this ADR binds a kernel provider slot, so kernel-side awareness is warranted even though no kernel SPI
   change ships with it.

## Cross-references

- Kernel ADR-061 (Declarable HTTP route-authorization policy) — the contract this compiles onto, and the
  ADR whose §5 requires this document.
- Kernel ADR-014 (`@RequiresRole` compile-time RBAC) — §3's rejection of config-driven role policy, which
  alternative 3 honours.
- ADR-041 + supplement — the compat resource-server surface and the three fail-open closures that made the
  missing per-path layer the remaining gap.
- ADR-011 — the mode taxonomy this declares against.
- [`compat-spring-security-support.md`](../architecture/compat-spring-security-support.md) — the
  client-facing statement of what survives a migration; this ADR's non-goals land there.
