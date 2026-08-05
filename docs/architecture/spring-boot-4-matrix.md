# Spring Boot 4 dual matrix — status and friction inventory

Working document for the ADR-028 dual-matrix work (0.8.0 train). ADR-028 is the decision; this file
records **what the SB4 line actually costs**, measured rather than anticipated, and what has landed.

| | |
|:---|:---|
| **SB3 pin** | `3.5.14` (`matrix-sb3`, default) |
| **SB4 pin** | `4.1.0` (`matrix-sb4`) |
| **SB3 line** | ✅ green — full reactor |
| **SB4 line** | ❌ red — 1 of 3 friction areas closed (see inventory) |

```bash
mvn -s .github/maven-settings.xml install                 # SB3 (default)
mvn -s .github/maven-settings.xml -Pmatrix-sb4 install     # SB4
```

> **`activeByDefault` caveat.** Maven disables a profile's `activeByDefault` the moment *any* other
> profile in the same POM is named on the command line — including `-Pcoverage`. So
> `mvn -Pcoverage verify` leaves `matrix-sb3` **inactive** and falls through to the
> `spring.boot.version` fallback. Name the matrix explicitly whenever another profile is used:
> `mvn -Pmatrix-sb3,coverage verify`. CI does this for the same reason.

---

## Friction inventory (measured 2026-08-05 against Spring Boot 4.1.0)

ADR-028 §Context anticipated two of these from a package-level grep. The third was not anticipated and
is the one that constrains the bridge design.

### 1. `actuator` — health types moved module *and* package — ❌ open

| | |
|:---|:---|
| **Was** | `org.springframework.boot.actuate.health.{Health, HealthIndicator, Status}` in `spring-boot-actuator` |
| **Is** | `org.springframework.boot.health.contributor.{Health, HealthIndicator, Status}` in a **new artifact**, `spring-boot-health` |
| **Affects** | `ExerisRuntimeHealthIndicator`, `ExerisActuatorAutoConfiguration`, `ExerisCompatibilityActuatorController` |
| **Anticipated?** | Yes — ADR-028 §Context, `org.springframework.boot` row, friction (b) |

The artifact split matters as much as the package move: `spring-boot-actuator` 4.1.0 contains no health
package at all, so this is not a rename an IDE can follow — the dependency itself changes.

Class names and nesting are unchanged, so the bridge is a pure relocation problem. `HealthIndicator` is
an interface **we implement**, which rules out the cheapest reflective shapes: a version-neutral class
cannot declare `implements` against a type it cannot name.

### 2. `web` — `OAuth2ResourceServerProperties` moved module *and* package — ❌ open

| | |
|:---|:---|
| **Was** | `org.springframework.boot.autoconfigure.security.oauth2.resource.OAuth2ResourceServerProperties`, in `spring-boot-autoconfigure` |
| **Is** | `org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerProperties`, in a **new artifact**, `spring-boot-security-oauth2-resource-server`. Same class name, same nested `Jwt` / `Opaquetoken` types |
| **Affects** | `ExerisCompatJwtDecoderFactory`, `ExerisCompatJwtDecoderAutoConfiguration` — i.e. the whole ADR-041 compat resource-server surface |
| **Anticipated?** | Partially — ADR-028 treats Spring Security as a separate axis (obligation 6), but this type is Spring **Boot** configuration-properties, not Spring Security, so it lands inside the matrix claim rather than beside it |

Worth flagging against ADR-028 obligation 6: the "Security is a separate axis" carve-out does not cover
this. The type we lost is Boot's property binding for the resource server, and ADR-041 built on it
deliberately ("the public-factory mirror tracks **public** API"). That mirror now needs a second shape.

There is a third option here that ADR-041 had no reason to consider, and it may beat a bridge: **bind
the properties ourselves**. The factory needs `jwk-set-uri`, `issuer-uri` and `public-key-location` —
three strings under `spring.security.oauth2.resourceserver.jwt.*`. Those *property names* are the public
contract and did not move; only the class that binds them did. Reading them straight from the
`Environment` removes the version-specific type from the compile path entirely, on both lines, with no
reflection. The cost is that we restate three property names Boot also declares. To be decided in the
implementing slice.

### 3. `web` — `HttpHeaders` is no longer a `Map` — ✅ closed, no bridge needed

| | |
|:---|:---|
| **Was** | `HttpHeaders implements MultiValueMap<String, String>` — `entrySet()`, `keySet()` available |
| **Is** | Spring Framework 7 drops that; `entrySet()` / `keySet()` do not exist, `headerNames()` arrives |
| **Affected** | `ExerisMvcServerHttpResponse` (`headers.entrySet()`), `ExerisNativeWebRequest` (`getHeaders().keySet()`) |
| **Anticipated?** | **No.** ADR-028's `org.springframework.http` row rated `HttpHeaders` "mostly stable" and named `MappingJackson2HttpMessageConverter` as the expected friction there |

**Resolved with no bridge at all.** `forEach(BiConsumer<? super String, ? super List<String>>)` is
declared on `HttpHeaders` in **both** Spring Framework 6.2 and 7.0 with an identical signature, so both
call sites now iterate through it and one source compiles under both profiles.

> **Correction.** An earlier revision of this document asserted that all three items required ADR-028's
> reflective third form, reasoning from the fact that no *import* could be redirected. That reasoning
> skipped a step: a method-signature change only forces a bridge if **no** common method exists, and
> that is a question to answer by diffing the two APIs rather than by inference. Diffing them
> (`javap` over `spring-web` 6.2.7 and 7.0.8) showed `forEach` present on both. The generalisation was
> made before the check; the check took two minutes and removed a bridge.

The cost of neutrality is one intermediate `ArrayList` in `getHeaderNames()`, where `keySet().iterator()`
had been a view. That is a Compatibility Mode accessor called by Spring's own argument resolvers, not a
Pure Mode hot path, and the allocation is proportional to one request's header count.

---

## What this means for the bridge design

ADR-028 obligation 4 offers three forms — `bridge.sb4.*` sub-package, `compat.sb4.*` sub-package, or an
inline guard. For the two open items, **the sub-package forms do not solve the compile problem**, and
that is worth stating before the implementing slice picks them up:

> A class that imports an SB4-only type cannot compile under `matrix-sb3`, and vice versa. Since both
> profiles compile the *same* source tree (obligation 1, "no `src/sb3`, no `src/sb4`"), any class
> naming a version-specific type breaks one of the two lines — regardless of which package it sits in.

The sub-package forms are therefore about **where a bridge lives once it exists**, not about how it
dodges the compile problem. Items 1 and 2 need either obligation 4's third form (reflection, or an
interface we own resolved at runtime) or — for item 2 specifically — removal of the dependency on the
relocated type altogether.

Item 3 is the counter-example worth keeping in view while doing them: **check for a common API before
reaching for a bridge.** A relocation genuinely has no common form; a signature change might.

---

## Why the CI matrix axis has not landed yet

ADR-028 obligation 2 requires a CI axis where failure on either line blocks merge. It is deliberately
**not** added in the slice that introduced the profiles, because the SB4 line is red:

- Adding it as a **required** check makes every unrelated PR unmergeable until the bridges land.
- Adding it as **non-blocking** (`continue-on-error`) satisfies the letter and defeats the purpose — a
  gate that never blocks is not a gate, and a permanently-amber check trains reviewers to ignore it.

The axis lands in the slice that turns the SB4 line green, in the same commit. Until then this document
is the honest record of where the line stands, and `matrix-sb4` is runnable on demand by anyone who
wants to check progress.

---

## Cross-references

- [ADR-028](../adr/ADR-028-spring-boot-4-nominal-compatibility-scope.md) — the decision, the obligations,
  and the bridge-package taxonomy.
- [ADR-041](../adr/ADR-041-compat-resource-server-security-under-none.md) — the compat resource-server
  surface that friction item 2 lands on.
- [ADR-011](../adr/ADR-011-pure-mode-vs-compatibility-mode.md) — why `compat.sb4.*` is hidden from
  Pure Mode imports while `bridge.sb4.*` is not.
