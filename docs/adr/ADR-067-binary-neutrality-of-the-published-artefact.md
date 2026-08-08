# ADR-067: Binary neutrality of the single published artefact across the Spring Boot dual matrix

| Attribute       | Value                                                                                                                                                                                                             |
|:----------------|:------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **Status**      | **ACCEPTED** (drafted and accepted 2026-08-08; single decider — no future gating event; ratified by the PR that introduces this file; convention documented in `CLAUDE.md` §"ADR status convention in this repo")   |
| **Deciders**    | Arkadiusz Przychocki                                                                                                                                                                                              |
| **Date**        | 2026-08-08                                                                                                                                                                                                        |
| **Scope**       | spring/build (`.github/workflows/build.yml`, `.github/scripts/`; two call sites in `exeris-spring-runtime-web`; no kernel involvement)                                                                             |
| **Owning Repo** | `exeris-spring-runtime`                                                                                                                                                                                           |
| **Driven By**   | Two Compatibility Mode defects found by a downstream Spring Boot 4 application after both matrix axes had been green for the whole train — neither was visible to ADR-028 obligation 2                              |
| **Compliance**  | [ADR-028](ADR-028-spring-boot-4-nominal-compatibility-scope.md), [ADR-011](ADR-011-pure-mode-vs-compatibility-mode.md), [Spring Boot 4 matrix](../architecture/spring-boot-4-matrix.md)                             |

## Context and Problem Statement

ADR-028 chose a dual matrix over a source-tree split: one source tree, two profiles, both axes running
the full reactor including integration tests, failure on either blocking merge (obligation 2). That
obligation verifies **two builds of the source**.

`deploy.yml` publishes something else. It runs `mvn -B -ntp -s .github/maven-settings.xml clean deploy`
with **no `-P`**, so the default `matrix-sb3` profile is active and exactly **one** jar is published,
compiled against Spring Framework 6. ADR-028 then claims that jar runs on Spring Boot 4 as well.

Nothing checked that claim, and it does not follow from the matrix. Source compatibility and binary
compatibility are different properties, and the gap between them is not a corner case:

> A call site can compile on **both** lines and bind to a **different descriptor** on each. Both axes
> go green. The published SB3-compiled binary then calls a method that resolves differently — or not
> at all — on SB4.

### How it surfaced

In a running Compatibility Mode application on Spring Boot 4:

```
IncompatibleClassChangeError: Class org.springframework.http.HttpHeaders
                              does not implement the requested interface java.util.Map
    at org.springframework.http.HttpHeaders.putAll(HttpHeaders.java:1992)
    at ExerisResponseEntityReturnValueHandler.handleReturnValue(...:65)
```

Spring Framework 7 stopped `HttpHeaders` implementing `MultiValueMap` — `javap` on `spring-web` 7.0.8
shows `implements java.io.Serializable` and nothing else. The header copy

```java
springResponse.getHeaders().putAll(entity.getHeaders());
```

compiles under both profiles, because SF7 *added* a `putAll(HttpHeaders)` overload. It binds
`putAll(Map)` under SB3 and `putAll(HttpHeaders)` under SB4. The published SB3 binary therefore hands
a non-`Map` to a `Map` parameter, and **every** `@RestController` method returning `ResponseEntity<T>`
fails. A controller returning a bare `List<T>` does not: it goes through
`ExerisResponseBodyReturnValueHandler` and never reaches that line — which made a total failure of one
handler present as a selective one, and cost diagnosis time.

A sweep of the reactor found a second instance, **absent from the bug report** because it needs a
usage the reporting application does not have:

| Call site | SB3 binds | SB4 binds | Failure on SB4 |
|---|---|---|---|
| `ExerisResponseEntityReturnValueHandler:65` `headers.putAll(entity.getHeaders())` | `putAll:(Ljava/util/Map;)V` | `putAll:(Lorg/springframework/http/HttpHeaders;)V` | `IncompatibleClassChangeError` on every `ResponseEntity` return value |
| `ExerisNativeWebRequest:79` `getHeaders().get(name)` | `get:(Ljava/lang/Object;)Ljava/util/List;` | `get:(Ljava/lang/String;)Ljava/util/List;` | `NoSuchMethodError` on any multi-valued `@RequestHeader` |

Line numbers here are the **failing** revision, matching the stack trace above — they are a record of
where the defect was, not a pointer into the current file, where both call sites have since moved.

Both reproduce deterministically by compiling the call shape against `spring-web` 6.2.7 and executing
it on 7.0.8.

### The root cause is a criterion, not a call site

Both defects sit under friction item 3 of `spring-boot-4-matrix.md`, which was closed with:

> `forEach` … is declared on `HttpHeaders` in **both** Spring Framework 6.2 and 7.0 with an identical
> signature, so both call sites now iterate through it and **one source compiles under both profiles**.

That closure criterion is the defect. It is necessary and not sufficient, and its blind spot is exact:
it catches everything that **stops compiling** (`entrySet()`, `keySet()` — which is how item 3 was
found) and is blind to everything that **keeps compiling against a changed signature**. The same
criterion was applied across the whole SB4 friction inventory.

## 🏁 The Decision

**One artefact continues to serve both lines, and binary neutrality becomes an enforced obligation
rather than an assumption.**

Concretely:

1. **The published jar stays single and SB3-compiled.** No per-line classifiers. `deploy.yml` is
   unchanged.

2. **Every Spring call site must bind to the same descriptor on both lines.** This is a build-breaking
   property of the source, not a review guideline. Where the two APIs differ, the call site uses a
   member whose signature is identical on both — established by diffing the two jars with `javap`,
   never by inference from the import.

3. **`.github/scripts/spring-binary-neutrality.sh` enforces it.** It compiles the reactor under both
   profiles and diffs every constant-pool reference our `target/classes` make into
   `org/springframework/**`. Any delta fails the build and names the offending class and descriptor.
   It runs as its own `build.yml` job, not as a matrix axis, because it needs both lines compiled in
   one workspace — which a per-line axis cannot provide. It skips tests, so it costs two
   `install -DskipTests` runs rather than two `verify` runs.

   The gate asserts a **floor on the number of references it found** before comparing. A check whose
   healthy state is "no output" fails open by construction: a broken scan yields two empty
   fingerprints, an empty diff, and a green build that verified nothing. The floor converts that into
   a failure. This is not hypothetical — the first working version of the script exited on the first
   class with no Spring references, because `grep` reports no-match as failure and the script runs
   under `set -e` with `pipefail`.

4. **Scope of the scan is `target/classes` only.** Test classes are compiled and executed per line and
   never ship, so they carry no binary-compatibility obligation.

At the time of this decision the reactor makes **262** such references. Before the fix, exactly two
differed; after it, none.

## Why a descriptor diff rather than a type check

The check compares compiled call sites, not source. That is deliberate — the failure mode being
guarded is precisely one that source-level analysis cannot see, since the source is identical and
correct on both lines. It also means the gate needs no model of Spring's API: it does not know what
`HttpHeaders` is, only that the same expression bound to two different things.

It covers method references behind `invokedynamic`. `responseHeaders::put` appears in the constant pool
as an ordinary `Methodref`, so rewriting a lambda as a method reference is not a way around the gate —
verified rather than assumed.

## Choosing a neutral member

`javap` both jars first. Present with an identical signature on `spring-web` 6.2.x and 7.0.x:
`forEach(BiConsumer)`, `put(String, List)`, `add(String, String)`, `getFirst(String)`,
`addAll(String, List)`, `getValuesAsList(String)`, `size()`, `isEmpty()`.

`getValuesAsList(String)` is a trap worth naming: identical on both lines and **not** a drop-in for
`get(name)`, because it splits comma-separated values. Same signature is not the same behaviour, and
the gate cannot tell the difference — it checks binding, not semantics. Behaviour stays the
responsibility of tests.

The two fixes:

- **Header copy** — `entity.getHeaders().forEach(responseHeaders::put)`. `putAll` semantics are
  per-key replacement, which `put` reproduces exactly.
- **Header lookup** — `forEach` with a case-insensitive name match. Absent versus present-but-empty is
  preserved explicitly, because `WebRequest` callers such as Spring's
  `RequestHeaderMethodArgumentResolver` read `null` as "header missing" and turn a required missing
  header into a 400; an empty array would present as "header present, no values".

## Scope and Non-Goals

**In scope.** The binary property of what this repository publishes, across the two Spring Boot lines
ADR-028 names, for the modules in this reactor.

**Not in scope.**

- **The Spring Security 7 axis** — a separate axis under ADR-028 obligation 6, and untouched here.
  Note that the gate will still see Spring Security call sites if they exist in `target/classes`;
  what is out of scope is the *claim*, not the mechanism.
- **Source-level divergence that fails to compile.** The existing matrix already covers it; this ADR
  adds nothing there.
- **Kernel API drift.** The scan is scoped to `org/springframework/**`. The kernel is a single pinned
  released coordinate, not a matrix, so the failure mode does not arise.
- **Semantic drift behind an identical signature.** Out of reach of a descriptor diff by construction;
  it belongs to tests, and the `getValuesAsList` note above is the standing warning.

## Alternatives Considered

**Per-line classifiers (`-sb3` / `-sb4`).** Rejected. It doubles the published surface and pushes a
version decision onto every consumer, in exchange for removing a problem it does not actually
remove — each classifier still needs its own verification, so the check moves rather than
disappears. It would also make the BOM carry two coordinate sets for the same module. The measured
cost of neutrality was two call sites out of 262 references; that does not justify doubling the
distribution.

**A denylist of known-drifting members** (an ArchUnit rule banning `HttpHeaders.get`, `putAll`,
`keySet`, `entrySet`, …). Rejected as the primary mechanism: it encodes the last failure rather than
the class of failure, and it would have caught neither defect before they were known. The descriptor
diff needs no such list because it compares what the compiler actually did.

**Compile-only SB4 axis instead of full `verify`.** Already rejected by ADR-028 obligation 2 and
re-affirmed here from the opposite direction: obligation 2 is not enough, so weakening it is not on
the table. Friction item 5 (Jackson 3) is the standing evidence — it appears only when tests run.

**Dropping the SB4 claim to "untested".** Rejected. The claim is commercially load-bearing for
brownfield migration, and the evidence says it is achievable and cheaply enforceable.

## Consequences

- **[+]** The class of defect cannot recur silently. A regression fails the build naming the call site,
  instead of reaching a customer as a runtime error in one handler.
- **[+]** ADR-028's "nominal SB4 compatibility" becomes a verified statement about the artefact rather
  than an inference from two green builds.
- **[+]** The gate is API-agnostic — it needs no update when Spring Framework 8 arrives, only a new
  profile pin.
- **[-]** Two additional `install -DskipTests` runs per PR. It is the cheapest of the three CI jobs and
  is not on the critical path of the matrix.
- **[-]** Neutrality occasionally costs a linear scan where a hash lookup existed —
  `ExerisNativeWebRequest.getHeaderValues` is now O(headers). This is a Compatibility Mode accessor
  called by Spring's own argument resolvers, not a Pure Mode hot path, and the allocation is
  proportional to one request's header count. Consistent with the trade-off already recorded on
  `getHeaderNames()`.
- **[-]** A neutral member can still be the wrong member. The gate says nothing about behaviour; see
  `getValuesAsList`.

## Compliance / Verification

- `.github/scripts/spring-binary-neutrality.sh` green — 262 references on each line, zero delta.
- The gate's **negative** case exercised, not just its positive one: replaying the comparison against
  the pre-fix fingerprints reaches the failure branch and reports both drifting call sites, SB3
  binding in the first column and SB4 in the second. A gate only ever observed passing is not known
  to be a gate.
- Both matrix axes green with full tests and per-module coverage floors
  (`-Pmatrix-sb3,coverage` and `-Pmatrix-sb4,coverage clean install`).
- `ExerisNativeWebRequestTest` — multi-valued header, case-insensitivity, absent-header `null`,
  header-name enumeration, first-value lookup.
- `ExerisCompatMvcIntegrationTest#compatMode_responseEntity_propagatesHeaders` — the failing path,
  through the real dispatcher.
- Cross-line execution proof: the new call shapes compiled against `spring-web` 6.2.7 and executed on
  7.0.8, preserving multi-value lists, absent-header `null`, and pre-existing response headers.

## Engineering Protocol

1. A PR that introduces a Spring call site binding differently on the two lines is rejected by the
   `binary-neutrality` job. The fix is a neutral member, **not** a suppression and not a profile guard.
2. A PR that removes or weakens the `binary-neutrality` job is an ADR-067-violating PR until and unless
   this ADR is superseded. The reviewer cites this ADR by number when blocking.
3. When a friction item is closed against the SB4 line, "it compiles on both profiles" is **not** a
   sufficient closure statement. State what was checked: the compiled descriptors, or the behaviour, or
   both.
4. If `deploy.yml` ever gains a profile or a classifier, this ADR is superseded rather than amended —
   the single-artefact premise is the decision, not an implementation detail of it.
5. Adding a third Spring Boot line means adding a profile and extending the script's line list; the
   comparison generalises to pairwise diffs without changing shape.

## Cross-references

- [ADR-028](ADR-028-spring-boot-4-nominal-compatibility-scope.md) — the dual matrix, the obligations,
  and the bridge-package taxonomy this supplements. Obligation 2 is not superseded; it is joined.
- [ADR-011](ADR-011-pure-mode-vs-compatibility-mode.md) — both defects were Compatibility Mode only,
  which is where the consumed Spring surface is widest.
- [`docs/architecture/spring-boot-4-matrix.md`](../architecture/spring-boot-4-matrix.md) —
  §"Binary neutrality" carries the measurements and the friction inventory this corrects.
