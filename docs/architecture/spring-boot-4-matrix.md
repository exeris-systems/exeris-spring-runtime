# Spring Boot 4 dual matrix — status and friction inventory

Working document for the ADR-028 dual-matrix work (0.8.0 train). ADR-028 is the decision; this file
records **what the SB4 line actually costs**, measured rather than anticipated, and what has landed.

| | |
|:---|:---|
| **SB3 pin** | `3.5.14` (`matrix-sb3`, default) |
| **SB4 pin** | `4.1.0` (`matrix-sb4`) |
| **SB3 line** | ✅ green — full reactor |
| **SB4 line** | ❌ red — three friction areas below, none yet bridged |

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

### 1. `actuator` — health types moved module *and* package

| | |
|:---|:---|
| **Was** | `org.springframework.boot.actuate.health.{Health, HealthIndicator, Status}` in `spring-boot-actuator` |
| **Is** | `org.springframework.boot.health.contributor.{Health, HealthIndicator, Status}` in a **new artifact**, `spring-boot-health` |
| **Affects** | `ExerisRuntimeHealthIndicator`, `ExerisActuatorAutoConfiguration`, `ExerisCompatibilityActuatorController` |
| **Anticipated?** | Yes — ADR-028 §Context, `org.springframework.boot` row, friction (b) |

The artifact split matters as much as the package move: `spring-boot-actuator` 4.1.0 contains no health
package at all, so this is not a rename an IDE can follow — the dependency itself changes.

### 2. `web` — `OAuth2ResourceServerProperties` gone

| | |
|:---|:---|
| **Was** | `org.springframework.boot.autoconfigure.security.oauth2.resource.OAuth2ResourceServerProperties` |
| **Is** | package absent in SB4 |
| **Affects** | `ExerisCompatJwtDecoderFactory`, `ExerisCompatJwtDecoderAutoConfiguration` — i.e. the whole ADR-041 compat resource-server surface |
| **Anticipated?** | Partially — ADR-028 treats Spring Security as a separate axis (obligation 6), but this type is Spring **Boot** configuration-properties, not Spring Security, so it lands inside the matrix claim rather than beside it |

Worth flagging against ADR-028 obligation 6: the "Security is a separate axis" carve-out does not cover
this. The type we lost is Boot's property binding for the resource server, and ADR-041 built on it
deliberately ("the public-factory mirror tracks **public** API"). That mirror now needs a second shape.

### 3. `web` — `HttpHeaders` is no longer a `Map` *(not anticipated)*

| | |
|:---|:---|
| **Was** | `HttpHeaders implements MultiValueMap<String, String>` — `entrySet()`, `keySet()` available |
| **Is** | Spring Framework 7 drops that; `entrySet()` / `keySet()` do not exist |
| **Affects** | `ExerisMvcServerHttpResponse:90` (`headers.entrySet()`), `ExerisNativeWebRequest:85` (`getHeaders().keySet()`) |
| **Anticipated?** | **No.** ADR-028's `org.springframework.http` row rated `HttpHeaders` "mostly stable" and named `MappingJackson2HttpMessageConverter` as the expected friction there |

This is the item that constrains the design, because it is a **method-signature change on a type both
lines consume**, not a relocation. There is no import to redirect: SF6 offers `keySet()`, SF7 offers
`headerNames()`, and no single call compiles against both. A source tree that must compile unchanged
under both profiles (ADR-028 obligation 1) therefore cannot name either method directly.

---

## What this means for the bridge design

ADR-028 obligation 4 offers three forms — `bridge.sb4.*` sub-package, `compat.sb4.*` sub-package, or an
inline guard. Items 1 and 2 look like sub-package candidates at first glance, but **a sub-package does
not work for any of the three**, and the reason is worth stating before the implementing slice picks it
up:

> A class that imports an SB4-only type cannot compile under `matrix-sb3`, and vice versa. Since both
> profiles compile the *same* source tree (obligation 1, "no `src/sb3`, no `src/sb4`"), any class
> naming a version-specific type breaks one of the two lines — regardless of which package it sits in.

The sub-package forms in obligation 4 are therefore about **where a bridge lives once it exists**, not
about how it avoids the compile problem. The mechanism has to be obligation 4's third form for all
three items: reflection, or an interface we own with the version-specific part resolved at runtime.

That is a heavier lift than the ADR's cost estimate implies, and it is a finding, not a complaint: the
estimate was derived from a package-level import grep, which cannot see a method disappearing from a
type that stayed put.

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
