# Spring Security in Compatibility Mode — what is supported

**Audience:** engineers evaluating or performing a brownfield migration of a Spring application onto
the Exeris runtime.

**Read this before migrating.** It tells you whether your security configuration survives the move,
what it becomes, and what has no equivalent yet. Nothing here is aspirational — every row describes
behaviour that exists in `0.7.0`.

**Mode:** everything below is Compatibility Mode (`exeris.runtime.web.mode=compatibility`) unless a
row says otherwise. Pure Mode applications do not use Spring Security's request-path machinery at
all; method security still applies to their beans.

---

## The one-paragraph version

Exeris owns HTTP ingress. There is no servlet container, so there is no `FilterChainProxy`, so
**`SecurityFilterChain` does not run** — not "runs differently", does not run. What is supported is
the resource-server half: a Bearer token is decoded and validated by your `JwtDecoder`, an
`Authentication` is placed in the `SecurityContextHolder`, and authorization is enforced by method
security (`@PreAuthorize`, `@Secured`) on your services. Per-path rules
(`authorizeHttpRequests`) have no equivalent in `0.7.0`.

If your application's authorization lives entirely in a `SecurityFilterChain` and nowhere else, it
does not migrate cleanly today. **The context will refuse to start** rather than serve your traffic
unauthenticated — see [Startup failures](#startup-failures) below.

---

## Support table

### Authentication

| Feature | Status | Notes |
|---|---|---|
| JWT bearer resource server (`spring.security.oauth2.resourceserver.jwt.*`) | **Supported** | `jwk-set-uri`, `issuer-uri`, `public-key-location`. The decoder is re-activated under `web-application-type=none` per ADR-041, using Spring's own `JwtValidators` — validation semantics are identical to a servlet deployment. |
| Application-declared `JwtDecoder` bean | **Supported** | Wins over the compat-provided one (`@ConditionalOnMissingBean`). |
| Custom `Converter<Jwt, ? extends AbstractAuthenticationToken>` / `JwtAuthenticationConverter` bean | **Supported** | Honoured for claim-to-authority mapping (`realm_access.roles`, custom prefixes). Two such beans without a `@Primary` fail startup deliberately — the mapping would be ambiguous. |
| Invalid / expired / forged token | **Supported — rejected with 401** | Behaviour change in 0.7.0; see [Invalid tokens](#invalid-tokens). |
| Absent token | **Supported — anonymous** | Public endpoints keep working. Authorization is then up to method security. |
| Opaque token / introspection | **Not supported** | Out of scope in ADR-041. No workaround in-runtime; terminate introspection in front of the application. |
| Reactive resource server | **Not supported** | The runtime has no Reactor on the request path by design. |
| Form login, HTTP Basic, session-based auth | **Not supported** | All are `SecurityFilterChain` mechanisms. Stateless bearer only. |

### Authorization

| Feature | Status | Notes |
|---|---|---|
| `@PreAuthorize` / `@PostAuthorize` / `@Secured` on beans | **Supported** | Plain Spring AOP; independent of the request path. This is where your authorization should live during a migration. |
| `authorizeHttpRequests` / `requestMatchers` per-path rules | **Supported, rewritten** | Not by running the chain — it still does not run. Declare an `ExerisHttpSecurity` bean and the runtime compiles it, once at startup, into the kernel's route policy. See [Per-path rules](#per-path-rules-exerishttpsecurity) below for the mapping and its one real reduction. |
| `hasRole(...)` / `hasAuthority(...)` at the edge | **Not supported** | The kernel's route contract evaluates scopes only — there is no role kind, and simulating one by convention would install a second authority model whose disagreements with Spring's are authorization decisions taken on the wrong basis. Role checks stay in `@PreAuthorize`. |
| Custom filters in the chain (`addFilterBefore`, …) | **Not supported** | No chain, no filter positions. |
| `AuthorizationManager` beans used directly by your own code | **Supported** | Nothing stops you calling them; the runtime just does not call them for you. |

### Per-path rules: `ExerisHttpSecurity`

The `SecurityFilterChain` your application declares cannot execute — there is no servlet container, so
there is no `FilterChainProxy` — and since 0.7.0 the runtime **fails startup** when it finds one rather
than serving traffic with neither authentication nor authorization. What replaces it is a declaration
that compiles onto the kernel's own authorization decision:

```java
@Bean
ExerisHttpSecurity httpSecurity() {
    return ExerisHttpSecurity.create()
            .requestMatchers("/health", "/health/live", "/health/ready", "/db/ping", "/db/roundtrip")
                .permitAll()
            .requestMatchers(HttpMethod.GET, "/api/orders/**").hasAnyScope("orders:read")
            .requestMatchers(HttpMethod.POST, "/api/orders/**").hasAllScopes("orders:read", "orders:write")
            .anyRequest().authenticated();
}
```

| `SecurityFilterChain` | `ExerisHttpSecurity` |
|---|---|
| `requestMatchers("/x").permitAll()` | `requestMatchers("/x").permitAll()` |
| `requestMatchers("/x").authenticated()` | `requestMatchers("/x").authenticated()` |
| `requestMatchers("/x").hasAuthority("SCOPE_a")` | `requestMatchers("/x").hasAnyScope("a")` |
| `requestMatchers(GET, "/x")` | `requestMatchers(HttpMethod.GET, "/x")` |
| `anyRequest().authenticated()` | `anyRequest().authenticated()` |
| `requestMatchers("/x").hasRole("USER")` | **no equivalent** — keep it in `@PreAuthorize` |

Rules match in **declaration order, first match wins**, exactly as a filter chain does — so a
transcribed configuration keeps the meaning its order already carried.

**Where the decision is taken.** Not here. The compiled policy is bound into the kernel, and every
decision is made on its admission path *before* the dispatcher runs. A refused caller never reaches
Spring at all: no handler, no argument resolver, no `@Transactional` advice. There is deliberately no
second evaluation on the Spring side, because two layers that can disagree produce a route whose
effective policy depends on which one ran.

**Two things fail at startup rather than at runtime**, both deliberately:

1. **No `anyRequest()` clause.** There is no default. Every other rule covers paths the author thought
   about; this one covers the paths they did not, which is where an authorization mistake actually
   lands.
2. **Fail-closed without the probe routes declared.** `anyRequest().authenticated()` is the line a
   Spring application already writes — and the kernel does not exempt its own `/health`, `/health/live`,
   `/health/ready`, `/db/ping` and `/db/roundtrip` endpoints from a bound policy. Writing the familiar
   thing would therefore deny orchestrator probes against a perfectly healthy process, and the symptom
   is a rollout that never becomes ready. Startup fails with those routes named instead.

**Declaring nothing changes nothing.** With no `ExerisHttpSecurity` bean the slot is left unbound and
the kernel applies no per-route requirement.

**One divergence to know about while it lasts.** The kernel derives the scopes it checks through its
own `ClaimsMapper`; Spring derives authorities through your `Converter<Jwt, …>`. For a token whose
authorities do not come from a literal `scope`/`scp` claim these are two mappings of the same token,
so an edge rule and a `@PreAuthorize` can disagree. Both fail closed, so a disagreement produces a
refusal rather than an escalation — but an operator debugging "why is this a 403 when the user has the
role" needs to know there are two mappings. Kernel 0.11.0 made `ClaimsMapper` supplyable, so this is
now fixable rather than inherent; closing it is the next slice.

### Request-path concerns

| Feature | Status | Notes |
|---|---|---|
| CSRF | **Not applicable** | Stateless bearer authentication; there is no session cookie to protect. |
| `SessionCreationPolicy` | **Not applicable** | No sessions. |
| CORS | **Not supported** | Neither the chain nor a compat equivalent handles it. Terminate CORS at your edge. |
| Security headers (HSTS, frame options, …) | **Not supported** | Same — an edge concern today. |
| `ExceptionTranslationFilter` semantics | **Partially** | 401/403 mapping exists; the anonymous upgrade does not. See [Known difference](#known-difference-anonymous-access-denied). |

### Error mapping

| Situation | Response |
|---|---|
| Presented Bearer token fails validation | `401` + `WWW-Authenticate: Bearer` |
| `AuthenticationException` escapes a handler | `401` + `WWW-Authenticate: Bearer` |
| `AccessDeniedException` escapes a handler (e.g. from `@PreAuthorize`) | `403` |
| Anything else unhandled | `500`, body-less |

Before 0.7.0 the first three were all `500`.

---

## Invalid tokens

A **presented but invalid** token is now answered with `401` and a `WWW-Authenticate: Bearer`
challenge, and the handler is never invoked.

Previously the decoder failure was swallowed: the request continued as anonymous with no log line,
no metric, and no response difference. A caller presenting an expired or forged token was treated
exactly like a caller presenting none, so a token that stopped validating — rotated key, clock skew,
wrong issuer — surfaced only as unexplained authorization failures deeper in the application, if at
all.

Every rejection emits the JFR event `eu.exeris.spring.runtime.web.BearerTokenRejected`, carrying the
failure class name and whether the request was refused. It carries no token material and no
validator message text: a JFR recording is an artefact that gets shipped around, and validation
messages can echo claim values. **This is the signal to alert on** — a rejection spike is what a
rotated key or a misconfigured issuer looks like.

**Opting out.** `exeris.runtime.web.compat.security.reject-invalid-token=false` restores the old
continue-as-anonymous behaviour for a migration that provably depends on it. It is fail-open; the
JFR event is still emitted on both paths, so the property silences the response, never the
telemetry.

---

## Startup failures

The runtime refuses to start in two situations, both deliberate.

### A `SecurityFilterChain` bean is present

Because it will never be executed, and standing silently by while it does nothing means serving
requests with no authentication or authorization at all. The error message names the bean, explains
why it cannot run, and lists what to do.

Resolution, in order of preference:

1. **Move authentication to the resource-server path** and authorization to method security. This is
   the supported shape.
2. **Delete the bean** if the application no longer needs it.
3. **Acknowledge it deliberately** with
   `exeris.runtime.web.compat.security.allow-unenforced-filter-chain=true`, if something else
   authorizes your traffic and the bean exists for a non-Exeris deployment profile. This silences
   the failure; it does not make the chain run. Nothing does.

### Two JWT authentication converter beans without a `@Primary`

The claim-to-authority mapping would be ambiguous, and guessing it is a security decision. Mark one
`@Primary`. This mirrors how Spring Security's own resource server resolves the converter.

---

## Known difference: anonymous access-denied

In a servlet deployment, Spring's `ExceptionTranslationFilter` inspects the security context on
`AccessDeniedException` and upgrades it to a `401` when the caller is anonymous — the reasoning
being that an unauthenticated caller should be told to authenticate rather than told "no".

This runtime does not do that. It would require reading `SecurityContextHolder` — a `ThreadLocal` —
from the mode-neutral error path, which the ThreadLocal Rule confines to Compatibility Mode.

**Exposure.** An *invalid* token is rejected with a `401` before any handler or method-security check
runs, so it never reaches this path. What remains is a caller with **no** token hitting a
method-secured endpoint: Spring would answer `401`, this answers `403`. The caller is refused either
way and no request is let through — only the status differs. Per-path rules resolve it properly by
deciding authentication before authorization.

---

## Properties

| Property | Default | Effect |
|---|---|---|
| `exeris.runtime.web.compat.security.reject-invalid-token` | `true` | `false` restores pre-0.7.0 continue-as-anonymous on an invalid token. Fail-open. |
| `exeris.runtime.web.compat.security.allow-unenforced-filter-chain` | `false` | `true` downgrades the `SecurityFilterChain` startup failure to a warning. Does not make the chain run. |

---

## Related

- [`ADR-041`](../adr/ADR-041-compat-resource-server-security-under-none.md) — why the JWT decoder is
  re-activated under `web-application-type=none`, and the scope boundaries of that decision.
- [`ADR-011`](../adr/ADR-011-pure-mode-vs-compatibility-mode.md) — the mode taxonomy.
- [`kernel-integration-seams.md`](kernel-integration-seams.md) — how requests reach Spring at all.
