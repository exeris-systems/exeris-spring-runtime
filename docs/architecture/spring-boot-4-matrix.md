# Spring Boot 4 dual matrix — status and friction inventory

Working document for the ADR-028 dual-matrix work. ADR-028 is the decision; this file records **what
the SB4 line actually costs**, measured rather than anticipated, and what has landed.

> **Release train.** ADR-028 scheduled this for `0.8.0-preview`; it ships in **0.7.0**. The reason is
> mundane: 0.7.0 stayed untagged for weeks while it was held for the kernel drain fix
> ([`exeris-kernel#282`](https://github.com/exeris-systems/exeris-kernel/issues/282), closed in kernel
> 0.11.0), so work merged in the meantime ships in it. The hold has since been lifted and the pin has
> moved. The ADR's train label is a scheduling estimate, not a decision; recorded here rather than
> silently relabelled in the ADR.

| | |
|:---|:---|
| **SB3 pin** | `3.5.14` (`matrix-sb3`, default) |
| **SB4 pin** | `4.1.0` (`matrix-sb4`) |
| **SB3 line** | ✅ green — full reactor, tests included |
| **SB4 line** | ✅ green — full reactor, tests included |
| **CI** | both axes run `-Pmatrix-<line>,coverage clean verify` on every push; failure on either blocks merge (ADR-028 obligation 2). A third job, `binary-neutrality`, compares Spring call-site descriptors across the two lines — see §"Binary neutrality" for why the matrix alone is not enough |
| **What ships** | **one** jar, built by the default `matrix-sb3` profile (`deploy.yml` runs `clean deploy` with no `-P`) and claimed to run on both lines |

> **Measure with `clean`.** An incremental `-Pmatrix-sb4 install` can report SUCCESS on stale classes
> left by an SB3 build. The first run of this kind here did exactly that, and it looked like the
> actuator problem had solved itself. Always `clean` when switching profiles.

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

ADR-028 §Context anticipated items 1, 2 and 5 from a package-level grep. Item 3 was not anticipated.
Item 4 did not exist when the ADR was written — this runtime introduced it in the same release.

**Compiling is not passing.** Items 1–4 were all found by compiling. Item 5 only appears when the
tests run, because it is a *runtime* dependency mismatch rather than a missing symbol. ADR-028
obligation 2's "run both axes in full" earns its keep here: a compile-only matrix would have reported
the SB4 line healthy while every compat dispatch failed.

**Three of the five closed with no bridge and no reflection**; one needed a real reflective bridge;
one is open. The recurring lesson is in the §"What this means" section below.

### 1. `actuator` — health types moved module *and* package — ✅ closed, reflective bridge

| | |
|:---|:---|
| **Was** | `org.springframework.boot.actuate.health.{Health, HealthIndicator, Status}` in `spring-boot-actuator` |
| **Is** | `org.springframework.boot.health.contributor.{Health, HealthIndicator, Status}` in a **new artifact**, `spring-boot-health` |
| **Affects** | `ExerisRuntimeHealthIndicator`, `ExerisActuatorAutoConfiguration`, `ExerisCompatibilityActuatorController` |
| **Anticipated?** | Yes — ADR-028 §Context, `org.springframework.boot` row, friction (b) |

The artifact split matters as much as the package move: `spring-boot-actuator` 4.1.0 contains no health
package at all, so this is not a rename an IDE can follow — the dependency itself changes.

**Closed with the one genuinely reflective bridge in this train.** This is where "drop the dependency
instead of bridging it" runs out: `HealthIndicator` is not a carrier of data available elsewhere, it is
an interface Spring Boot discovers **by type**, and something must implement it.

The health decision moved into `ExerisRuntimeHealth` / `ExerisRuntimeHealthIndicator`, which name no
framework type at all — so the compat actuator controller and the indicator's own tests are
version-neutral for free. `SpringBootHealthIndicatorFactory` then creates a JDK proxy against whichever
`HealthIndicator` interface is present and converts through the `Health` builder, whose API is
identical in shape on both lines. Registration goes through a `BeanDefinitionRegistryPostProcessor`
rather than a `@Bean` method: Boot finds contributors with `getBeansOfType(HealthIndicator.class)`,
which resolves a factory method's *declared* return type before instantiating it, so an
`Object`-returning `@Bean` would never match and the indicator would silently never appear. The
definition's target type is set explicitly instead.

Resolution failure stands the indicator down with a log line rather than throwing — an actuator that
cannot register must not stop the application serving traffic.

Worth recording against ADR-028: obligation 4 names exactly this case as the canonical `bridge.sb4.*`
sub-package example. A sub-package cannot solve it. The problem is that the type is unnameable at
compile time under one line, and the package a class sits in does not change that. The bridge is also
not SB4-specific — it runs on both lines — so it lives in `actuator.bridge`, where an `sb4` label would
misdescribe when it is in play.

### 2. `web` — `OAuth2ResourceServerProperties` moved module *and* package — ✅ closed, no bridge needed

| | |
|:---|:---|
| **Was** | `org.springframework.boot.autoconfigure.security.oauth2.resource.OAuth2ResourceServerProperties`, in `spring-boot-autoconfigure` |
| **Is** | `org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerProperties`, in a **new artifact**, `spring-boot-security-oauth2-resource-server`. Same class name, same nested `Jwt` / `Opaquetoken` types |
| **Affects** | `ExerisCompatJwtDecoderFactory`, `ExerisCompatJwtDecoderAutoConfiguration` — i.e. the whole ADR-041 compat resource-server surface |
| **Anticipated?** | Partially — ADR-028 treats Spring Security as a separate axis (obligation 6), but this type is Spring **Boot** configuration-properties, not Spring Security, so it lands inside the matrix claim rather than beside it |

Worth flagging against ADR-028 obligation 6: the "Security is a separate axis" carve-out does not cover
this. The type we lost is Boot's property binding for the resource server, and ADR-041 built on it
deliberately ("the public-factory mirror tracks **public** API"). That mirror now needs a second shape.

**Resolved by binding the properties ourselves**, not by bridging the relocated type. What did *not*
move is the **property names** — `spring.security.oauth2.resourceserver.jwt.*` is the contract an
application writes against, identical on both lines, and `OnResourceServerJwtConfiguredCondition` in
this same feature was already reading them as literals. `ExerisResourceServerJwtProperties` binds the
five settings the factory consumes (`jwk-set-uri`, `issuer-uri`, `public-key-location`, `audiences`,
`jws-algorithms`) through Spring's `Binder`, so relaxed and list binding stay identical to Boot's.
`public-key-location` binds as a `String` and resolves through the application's `ResourceLoader`,
which is why no resource-aware conversion service is needed.

The version-specific type leaves the compile path entirely: no reflection, no `@SbCompat` bridge,
nothing to delete when the SB3 line is dropped. The cost is restating five property names Boot also
declares — a smaller and more visible surface than a reflective shim over a class whose package
differs per line, and the more stable half of the pair, since Boot moved the class while the names
stayed put.

*(An earlier revision of this entry called it "three strings". The factory reads five settings,
including two lists — counted properly while implementing it.)*

### 3. `web` — `HttpHeaders` is no longer a `Map` — ✅ closed, no bridge needed

| | |
|:---|:---|
| **Was** | `HttpHeaders implements MultiValueMap<String, String>` — `entrySet()`, `keySet()` available |
| **Is** | Spring Framework 7 drops that; `entrySet()` / `keySet()` do not exist, `headerNames()` arrives |
| **Affected** | `ExerisMvcServerHttpResponse` (`headers.entrySet()`), `ExerisNativeWebRequest` (`getHeaders().keySet()`), and — found later, see below — `ExerisResponseEntityReturnValueHandler` (`headers.putAll`) and `ExerisNativeWebRequest.getHeaderValues` (`headers.get`) |
| **Anticipated?** | **No.** ADR-028's `org.springframework.http` row rated `HttpHeaders` "mostly stable" and named `MappingJackson2HttpMessageConverter` as the expected friction there |

**Resolved with no bridge at all.** `forEach(BiConsumer<? super String, ? super List<String>>)` is
declared on `HttpHeaders` in **both** Spring Framework 6.2 and 7.0 with an identical signature, so both
call sites now iterate through it and one source compiles under both profiles.

> **This item was closed twice, and the first closure was wrong.** The criterion above — "one source
> compiles under both profiles" — is the whole error. It is necessary and not sufficient, and the two
> call sites it missed are the subject of the §"Binary neutrality" section below. `entrySet()` and
> `keySet()` were found because they *stopped compiling*; `putAll` and `get` kept compiling on both
> lines while binding to a different method on each, so nothing in the dual matrix objected.

> **Correction.** An earlier revision of this document asserted that all three items required ADR-028's
> reflective third form, reasoning from the fact that no *import* could be redirected. That reasoning
> skipped a step: a method-signature change only forces a bridge if **no** common method exists, and
> that is a question to answer by diffing the two APIs rather than by inference. Diffing them
> (`javap` over `spring-web` 6.2.7 and 7.0.8) showed `forEach` present on both. The generalisation was
> made before the check; the check took two minutes and removed a bridge.

The cost of neutrality is one intermediate `ArrayList` in `getHeaderNames()`, where `keySet().iterator()`
had been a view. That is a Compatibility Mode accessor called by Spring's own argument resolvers, not a
Pure Mode hot path, and the allocation is proportional to one request's header count.

### 4. `data` — `HibernatePropertiesCustomizer` moved module *and* package — ✅ closed, no bridge needed

| | |
|:---|:---|
| **Was** | `org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer`, in `spring-boot-autoconfigure` |
| **Is** | `org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer`, in a **new artifact**, `spring-boot-hibernate` |
| **Affects** | `ExerisHibernateBootstrapCustomizer` |
| **Anticipated?** | **Could not have been.** This runtime introduced the dependency itself, in the same release, while the matrix work was pending |

**Resolved by dropping the interface.** It was only a delivery mechanism for two settings, so the class
now contributes them as ordinary `spring.jpa.properties.*` entries from a `BeanFactoryPostProcessor`.
Spring Boot binds those into the very map the customizer would have handed us — identical effect, and
nothing version-specific on the compile path.

The rework improved the guarantee it was written for. "Never overrule an application that has already
spoken" is now enforced by **property-source precedence** — the contribution is added last — rather
than by an explicit key check, so a value the application sets anywhere wins automatically, including
the metadata switch that previously had no such protection.

### 5. `web` — Spring Boot 4 ships Jackson 3, the compat bridge built a Jackson 2 converter — ✅ closed

| | |
|:---|:---|
| **Was** | `MappingJackson2HttpMessageConverter`, backed by `com.fasterxml.jackson.core:jackson-databind` (Jackson 2) |
| **Is** | SB4 ships `tools.jackson.core:jackson-databind:3.1.1` and only `jackson-annotations:2.21` from the 2.x line. The SF7 class still exists but its Jackson 2 core does not, so construction fails with `NoClassDefFoundError: com/fasterxml/jackson/core/util/DefaultPrettyPrinter$Indenter`. SF7 offers `JacksonJsonHttpMessageConverter` for Jackson 3 |
| **Affects** | `ExerisCompatAutoConfiguration#exerisCompatJacksonConverter` and everything downstream of it — 33 test failures in `web`, all one cause |
| **Anticipated?** | Yes — ADR-028's `org.springframework.http` row named `MappingJackson2HttpMessageConverter` as the expected friction there |

**Closed by choosing the implementation at runtime.** `ExerisCompatJsonConverterFactory` picks by which
Jackson databind is present and the bean is declared as `HttpMessageConverter<?>`, a type on both
lines — which cost nothing, since every consumer already took `List<HttpMessageConverter<?>>`.

Only one of the two converters is constructed reflectively, and the asymmetry is the point:
`MappingJackson2HttpMessageConverter` is nameable at compile time on **both** lines, so it is
constructed directly; `JacksonJsonHttpMessageConverter` exists only in Spring Framework 7, so naming it
would break the SB3 compile. Reflection is used exactly where the compiler cannot follow, and nowhere
else.

The tests were part of the fix rather than an afterthought: three of them constructed the Jackson 2
converter directly, which compiles on both lines and throws on SB4. They now go through the same
factory the production path uses, so what they exercise is the selection, not a hardcoded guess.

---

## What this means for the bridge design

ADR-028 obligation 4 offers three forms — `bridge.sb4.*` sub-package, `compat.sb4.*` sub-package, or an
inline guard. For the one open item, **the sub-package forms do not solve the compile problem**, and
that is worth stating before the implementing slice picks it up:

> A class that imports an SB4-only type cannot compile under `matrix-sb3`, and vice versa. Since both
> profiles compile the *same* source tree (obligation 1, "no `src/sb3`, no `src/sb4`"), any class
> naming a version-specific type breaks one of the two lines — regardless of which package it sits in.

The sub-package forms are therefore about **where a bridge lives once it exists**, not about how it
dodges the compile problem.

Items 2, 3 and 4 all closed **without** a bridge, by three different routes, and the pattern is worth
naming because the reflex in each case was to reach for reflection first:

| Item | Shape | What removed the need for a bridge |
|---|---|---|
| 3 `HttpHeaders` | signature change | A method present on **both** versions (`forEach`) — found by diffing the two APIs instead of inferring from the import |
| 2 `OAuth2ResourceServerProperties` | relocation | The **property names** did not move, only the class binding them. Bind them ourselves and the type leaves the compile path |
| 4 `HibernatePropertiesCustomizer` | relocation | The interface was only a delivery mechanism for two settings. Contribute them as ordinary properties and the interface is not needed |

So a relocation does not automatically mean a bridge either: ask what the relocated type was *for*. If
it carried data that also exists as configuration, or if a version-neutral extension point delivers the
same effect, the dependency can be dropped rather than bridged.

Item 1 is where that reasoning runs out. `HealthIndicator` is not a carrier of data we could source
elsewhere — it is an interface **we implement**, and the framework discovers our implementation by
type. A version-neutral class cannot declare `implements` against a type it cannot name, so this one
needs obligation 4's third form for real: a runtime-created proxy against whichever interface is
present, or an equivalent.

---

## The CI matrix axis

Added once the SB4 line went green, which was the condition set when the profiles landed: a
**required** check on a red line makes every unrelated PR unmergeable, and a **non-blocking** one
satisfies ADR-028's letter while defeating its purpose — a gate that never blocks is not a gate, and a
permanently-amber check trains reviewers to ignore it.

Both axes now run `-Pmatrix-<line>,coverage clean verify`. `fail-fast` is off so a break on one line
still reports the other: knowing whether a change broke SB4 only or both is most of the diagnosis.

Note the profile list — `-Pmatrix-sb3,coverage`, not `-Pcoverage`. Naming any profile disables
`activeByDefault`, so the matrix profile must be explicit whenever another one is used. That is the
same trap described at the top of this document, and CI would have hit it silently: the SB3 axis would
have fallen through to the fallback pin and still gone green, testing nothing the SB4 axis did not.

A third job, `binary-neutrality`, sits beside the matrix rather than inside it: it needs both lines
compiled in one workspace, which a per-line axis cannot provide. It skips tests — it only compares
compiled call sites — so it costs two `install -DskipTests` runs rather than two `verify` runs. See
[ADR-067](../adr/ADR-067-binary-neutrality-of-the-published-artefact.md) for why it exists.

> **Filename drift.** ADR-028 obligation 2 and its Engineering Protocol §2 both name
> `.github/workflows/ci.yml`. The workflow is `build.yml` — it predates the ADR and was never called
> `ci.yml`. Recorded here rather than edited into the accepted ADR body; the axis itself is exactly as
> the obligation specifies.

---

## Binary neutrality — what the dual matrix does not prove

**Found downstream, in a running Compatibility Mode application on Spring Boot 4**, after both matrix
axes had been green for the whole train:

```
IncompatibleClassChangeError: Class org.springframework.http.HttpHeaders
                              does not implement the requested interface java.util.Map
    at org.springframework.http.HttpHeaders.putAll(HttpHeaders.java:1992)
    at ExerisResponseEntityReturnValueHandler.handleReturnValue(...:65)
```

Every `@RestController` method returning `ResponseEntity<T>` failed. A controller returning a bare
`List<T>` did not, because it goes through `ExerisResponseBodyReturnValueHandler` and never reaches
that line — which is why the failure looked selective rather than total.

### The mechanism

`deploy.yml` runs `mvn clean deploy` with **no profile**, so `matrix-sb3` is active by default and the
published jar is compiled against Spring Framework 6. ADR-028 then claims that one jar runs on both
lines. The dual matrix proves the *source* compiles and tests on both. It cannot prove anything about
the *binary*, and the two are not the same claim:

> A call site can compile on both lines and bind to a **different descriptor** on each. Both axes go
> green. The published SB3-compiled binary then calls a method that resolves differently — or not at
> all — on SB4.

Two instances, both invisible to the matrix:

| Call site | SB3 binds to | SB4 binds to | Failure on SB4 |
|---|---|---|---|
| `responseHeaders.putAll(entity.getHeaders())` | `putAll:(Ljava/util/Map;)V` | `putAll:(Lorg/springframework/http/HttpHeaders;)V` | `IncompatibleClassChangeError` — SF7 stopped `HttpHeaders` implementing `MultiValueMap`, so the SB3-compiled call hands a non-`Map` to a `Map` parameter |
| `springRequest.getHeaders().get(headerName)` | `get:(Ljava/lang/Object;)Ljava/util/List;` | `get:(Ljava/lang/String;)Ljava/util/List;` | `NoSuchMethodError` — SF6 inherited `get(Object)` from `Map`; SF7 declares `get(String)` |

The second one was **not** in the downstream report. It surfaces only on `@RequestHeader` binding a
multi-valued header, which that application does not do — it was found by the sweep below, not by a
second bug report.

Both are fixed by using members whose signature is identical on both lines: `forEach` plus
`put(String, List)`, and `forEach` with a case-insensitive name match. Verified by compiling the new
call shapes against `spring-web` 6.2.7 and executing them on 7.0.8 — multi-value lists, absent-header
`null`, and pre-existing headers all preserved.

### The gate

A one-off fix does not close this; the *class* of defect is inherent to one source tree published as
one binary. `.github/scripts/spring-binary-neutrality.sh` compiles the reactor under both profiles and
compares every constant-pool reference our `target/classes` make into `org/springframework/**`. Any
difference fails the build and names the call site. It runs as its own `build.yml` job because it needs
both lines in one workspace, which the per-line matrix cannot give it.

Across the reactor there are **262** such references. Before the fix, exactly two differed; after it,
none. The check also sees method *references* behind `invokedynamic` — `responseHeaders::put` appears
in the constant pool as an ordinary `Methodref`, so the lambda form is not a blind spot.

### Two things this corrects

- **The earlier "measure with `clean`" note undersold what it had found.** An incremental profile
  switch produced a `NoSuchMethodError` on `HttpHeaders.putAll`, which was diagnosed as stale classes
  and filed under build hygiene. That diagnosis was locally right — the classes *were* stale — but a
  build that runs SB3-compiled classes against SB4 jars is precisely the deployment we ship, and the
  accident had reproduced the production failure. It was read as noise about the build rather than as
  a signal about the product.
- **ADR-028 obligation 2 is necessary and not sufficient.** "Both axes run the full reactor including
  integration tests; failure on either blocks merge" verifies two builds; it does not verify the one
  artefact. That is a product-level question about what "nominal SB4 compatibility" promises, so it is
  settled in [ADR-067](../adr/ADR-067-binary-neutrality-of-the-published-artefact.md) rather than here:
  the artefact stays single and SB3-compiled, per-line classifiers are rejected, and binary neutrality
  becomes an enforced obligation joining obligation 2 rather than superseding it.

---

## Cross-references

- [ADR-028](../adr/ADR-028-spring-boot-4-nominal-compatibility-scope.md) — the decision, the obligations,
  and the bridge-package taxonomy.
- [ADR-067](../adr/ADR-067-binary-neutrality-of-the-published-artefact.md) — one artefact serves both
  lines, and binary neutrality is enforced rather than assumed. Supplements ADR-028 obligation 2.
- [ADR-041](../adr/ADR-041-compat-resource-server-security-under-none.md) — the compat resource-server
  surface that friction item 2 lands on.
- [ADR-011](../adr/ADR-011-pure-mode-vs-compatibility-mode.md) — why `compat.sb4.*` is hidden from
  Pure Mode imports while `bridge.sb4.*` is not.
