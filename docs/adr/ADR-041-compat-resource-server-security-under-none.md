# ADR-041: Compatibility-Mode Resource-Server Security Under `web-application-type=none`

| Attribute       | Value                                                                                                                                                                                                                 |
|:----------------|:--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **Status**      | **ACCEPTED**                                                                                                                                                                                                         |
| **Deciders**    | Arkadiusz Przychocki                                                                                                                                                                                                 |
| **Date**        | 2026-06-09                                                                                                                                                                                                           |
| **Scope**       | spring/web compat security (adds a compatibility `JwtDecoder` bean; no kernel SPI changes; Compatibility Mode only)                                                                                                  |
| **Owning Repo** | `exeris-spring-runtime`                                                                                                                                                                                              |
| **Registry**    | `exeris-docs/adr-index.md` row 041 (per-repo, public)                                                                                                                                                               |
| **Driven By**   | A brownfield OAuth2 JWT resource server silently loses authentication when migrated onto the Exeris runtime, because Spring Boot's resource-server decoder auto-config is servlet-web-gated and the app runs `web-application-type=none`. |
| **Compliance**  | [ADR-011 (Pure vs Compatibility Mode)](ADR-011-pure-mode-vs-compatibility-mode.md), [Module Boundaries](../architecture/module-boundaries.md), [Phase 2 (opt-in Spring compatibility)](../phases/phase-2-invariants.md) |

## Context and Problem Statement

`exeris-spring-runtime` hosts a Spring application on the Exeris kernel: **Exeris owns the transport**, so the Spring application runs with `spring.main.web-application-type=none` — there is no servlet container, no `DispatcherServlet`, no reactive `HttpHandler` from Spring. This is the defining shape of the runtime (ADR-010).

A consequence of `none` that is easy to miss: **every Spring Boot auto-configuration gated on `@ConditionalOnWebApplication(type = SERVLET)` silently does not run.** Spring Boot's `OAuth2ResourceServerAutoConfiguration` is exactly such a class. So for an app that configures a standard JWT resource server via

```properties
spring.security.oauth2.resourceserver.jwt.issuer-uri=https://issuer.example.com
# or jwk-set-uri / public-key-location
```

Spring Boot **does not create a `JwtDecoder` bean** under `none`. The compatibility security filter introduced earlier (`ExerisSecurityContextFilter`, `@ConditionalOnBean(JwtDecoder)`) therefore never activates, and the request path runs **unauthenticated** — not by configuration, but as an invisible side effect of the migration. The honour-the-user-converter fix (the prior PR) addressed *how* a token is converted once decoded; it did nothing for the *absence of the decoder itself*. That absence is the real migration blocker this ADR closes.

This is **instance #1 of a broader class**: error handling, CORS, multipart, content negotiation, and other servlet-web-gated auto-configs are dormant under `none` too. The general policy — *which* web-gated auto-configs Exeris compatibility should re-activate, and by what mechanism — is **out of scope here** and is deferred to a future RFC. This ADR decides only the security-critical first case.

## Decision

In Compatibility Mode, **re-activate the OAuth2 resource-server `JwtDecoder`** that Spring Boot would have created on a servlet stack, so the security filter has a decoder to use under `web-application-type=none`.

The decoder is provided by a **dedicated auto-configuration**, `ExerisCompatJwtDecoderAutoConfiguration`, ordered `@AutoConfiguration(before = ExerisCompatAutoConfiguration.class)`:

- **Why a separate, ordered auto-config (not a nested `@Configuration`).** `ExerisSecurityContextFilter` is gated `@ConditionalOnBean(JwtDecoder)`. `@ConditionalOnBean` only reliably observes beans contributed by auto-configurations processed **earlier** — it does **not** reliably see a bean from a sibling nested `@Configuration` of the same auto-config (declaration order is not enough, because the condition is evaluated before sibling member-class `@Bean` definitions are registered). So the decoder lives in its own auto-config ordered `before` the one that carries the security filter — exactly how Spring Boot itself separates the resource-server decoder from the security-filter-chain configuration. (An early nested-config attempt failed precisely this way: the decoder bean existed but the filter never activated; an end-to-end test now guards against regression.)
- **Gating:**
  - `@ConditionalOnClass(JwtDecoder)` — only when `spring-security-oauth2-resource-server` is on the classpath (the dependency is optional).
  - `@ConditionalOnProperty(exeris.runtime.web.mode = compatibility)` — Compatibility Mode only.
  - `@ConditionalOnNotWebApplication` — only when the app is **not** a servlet/reactive web app, i.e. exactly the Exeris-hosted `none` case. On a real servlet deployment Spring Boot's own decoder wins and this never fires.
  - `@EnableConfigurationProperties(OAuth2ResourceServerProperties.class)` — bind the same `spring.security.oauth2.resourceserver.jwt.*` properties Spring Boot uses.
  - bean `@ConditionalOnMissingBean(type = JwtDecoder)` — inert whenever a decoder already exists (servlet deployment, or an app-declared `JwtDecoder` bean).
  - bean `@Conditional(OnResourceServerJwtConfiguredCondition)` — only when a key source (`jwk-set-uri` / `public-key-location` / `issuer-uri`) is actually configured, mirroring Spring Boot's own property gating so an unconfigured decoder is never built.

- **Construction — faithful, not hand-rolled.** `ExerisCompatJwtDecoderFactory` builds the decoder and its validator chain with **public Spring Security factories only**, mirroring Spring Boot's `OAuth2ResourceServerJwtConfiguration.JwtDecoderConfiguration`:
  - `jwk-set-uri` → `NimbusJwtDecoder.withJwkSetUri(...).jwsAlgorithms(...).build()`;
  - `public-key-location` → `NimbusJwtDecoder.withPublicKey(...)` (PEM read exactly as Spring does);
  - `issuer-uri` → lazy `SupplierJwtDecoder(() -> JwtDecoders.fromIssuerLocation(...))`, deferring OIDC discovery to first decode just like Spring Boot;
  - validators → `JwtValidators.createDefaultWithIssuer(...)` / `createDefault()`, plus an audience `JwtClaimValidator` when `audiences` is configured, combined via `DelegatingOAuth2TokenValidator`.

  **No bespoke token validation is invented.** Getting JWT validation subtly wrong (issuer confusion, missing `exp`, wrong audience semantics) is a security defect, so the implementation is a thin re-wiring of Spring's own building blocks.

## Scope and Non-Goals

- **In scope:** the resource-server JWT decoder, Compatibility Mode, `web-application-type=none`.
- **Out of scope (deferred to a future RFC):** a general mechanism for re-activating servlet-web-gated Spring auto-configs under `none` (error handling, CORS, multipart, …). This ADR is deliberately narrow; it does not establish that broader policy.
- **Out of scope:** opaque-token (introspection) resource servers, reactive resource servers, and full Spring Security `SecurityFilterChain` setups (when a chain is present, `NoSecurityFilterChainCondition` already disables the compat filter).
- **No kernel involvement.** This is entirely Spring-side compatibility wiring; The Wall is untouched.

## Alternatives Considered

1. **Re-use Spring Boot's own `JwtDecoderConfiguration` via an `ImportSelector`** (import the package-private internal class by FQN string, bypassing only its web gate). Rejected: couples the runtime to an undocumented, package-private Spring Boot internal class name, which can change silently across Boot upgrades — an unacceptable fragility for a security component. The public-factory mirror tracks **public** API instead.
2. **Hand-roll the validator chain.** Rejected: re-implementing issuer/audience/timestamp validation is exactly the security-sensitive surface to avoid. We reuse `JwtValidators` so the defaults stay identical to Spring's.
3. **Leave it to the application** (document "declare your own `JwtDecoder` bean under `none`"). Rejected as the *default*: it turns a silent auth bypass into tribal knowledge. The app-declared bean still wins (`@ConditionalOnMissingBean`), so the escape hatch remains for non-standard setups.
4. **Do nothing / file an issue.** Rejected: this is the same compatibility-migration class of fix as the surrounding datasource and security-filter work — it belongs in the runtime, not in every consumer.

## Consequences

- **Positive:** a brownfield JWT resource server keeps working unchanged after migrating onto Exeris; authentication is no longer silently dropped under `none`. Validation parity with a servlet deployment is preserved because Spring's own validator factories are used.
- **Cost:** ~150 lines of compatibility code (factory + nested config) that must track Spring Boot's resource-server decoder wiring across major Boot upgrades. Guarded by unit tests (`ExerisCompatJwtDecoderFactoryTest`) and module-integration tests (`ExerisCompatJwtDecoderConfigurationTest`).
- **Fail-fast, not fail-open:** if a key source is configured but malformed (bad PEM, bad algorithm), the decoder construction throws at startup rather than starting with broken validation.
- **Precedent set narrowly:** this establishes the *pattern* (compat re-activation of a web-gated auto-config) for exactly one case. The general policy is explicitly left to a future RFC, so this ADR does not become an implicit licence to re-activate arbitrary web-gated configuration.

## Compliance / Verification

- Mode: **COMPATIBILITY_MODE**. Code lives in `eu.exeris.spring.runtime.web.compat.security.*` and the compat nested config; `CompatibilityIsolationGuardTest` and `ModuleBoundaryTest` stay green.
- Unit: `ExerisCompatJwtDecoderFactoryTest` (key-source selection, lazy issuer path, blank-handling, no-source error).
- Module-integration: `ExerisCompatJwtDecoderConfigurationTest` (jwk-set-uri / issuer-uri / public-key-location produce a decoder under a non-web context; no decoder without properties; app-declared decoder wins).
- End-to-end: `ExerisCompatAutoConfigurationTest#compatJwtDecoder_activatesSecurityFilter_endToEnd_underNone` drives the full auto-config chain through `ApplicationContextRunner` and asserts that the compat decoder **and** `ExerisSecurityContextFilter` are both present — proving the `before`-ordered decoder actually activates the `@ConditionalOnBean(JwtDecoder)` filter (the regression that the nested-config approach hit).
