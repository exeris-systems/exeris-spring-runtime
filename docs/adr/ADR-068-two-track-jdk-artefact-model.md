# ADR-068: Two published artefacts — a GA line and a `-preview` line, and the `-preview` line is for Valhalla

| Attribute       | Value                                                                                                                                                                                       |
|:----------------|:----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **Status**      | **ACCEPTED** (drafted and accepted 2026-08-08; single decider — no future gating event for the decision; ratified by the PR that introduces this file)                                       |
| **Deciders**    | Arkadiusz Przychocki                                                                                                                                                                        |
| **Date**        | 2026-08-08                                                                                                                                                                                  |
| **Scope**       | spring/build (distribution model, build profiles, CI axes; no runtime behaviour change)                                                                                                     |
| **Owning Repo** | `exeris-spring-runtime`                                                                                                                                                                     |
| **Driven By**   | Kernel ADR-066's two-track JDK model, and [RFC-2026-08-08](../rfc/RFC-2026-08-08-two-track-jdk-line.md), which left the artefact decision explicitly unmade                                   |
| **Compliance**  | [ADR-067](ADR-067-binary-neutrality-of-the-published-artefact.md), [ADR-028](ADR-028-spring-boot-4-nominal-compatibility-scope.md), [ADR-029](ADR-029-phase-3b-alpha-scope-request-scope-and-structured-concurrency.md) |

## Context and Problem Statement

Kernel ADR-066 splits the platform into two tracks — a default line on an LTS JDK with no preview
flags, and a `preview` branch on the latest JDK. The branch is not a sandbox: it **converges into
`main` at an LTS**, so whatever it has absorbed becomes the GA line at that point. A component that
does not track it does not avoid the API delta; it defers the whole of it to the LTS, after the fact.

RFC-2026-08-08 settled the hard part for this repository by withdrawing `ExerisStructuredScope` — the
one class that used a preview feature — and deliberately left the distribution question open:

> **The artefact/line decision — one GA artefact plus one `-preview` artefact, Spring axis
> classifier-free — is still owed its own ADR and is not covered here.**

That is this ADR.

## 🏁 The Decision

**Two published artefacts, mirroring the kernel: a GA line and a `-preview` line. The `-preview` line
exists for Valhalla, and ships with content from its first release.**

1. **GA line** — LTS JDK, no preview flags. This is the default and what `deploy.yml` publishes today.
2. **`-preview` line** — latest JDK, `--enable-preview`, carrying the value-class work.
3. **Two artefacts, not four.** The Spring Boot axis stays classifier-free per ADR-067, so each
   JDK-line artefact serves both the SB3 and SB4 lines. Only the JDK axis takes a classifier, and for
   a reason that does not transfer from ADR-067: one jar can serve two Spring lines; it cannot be two
   class-file majors.
4. **The `-preview` artefact does not publish as a JDK-target rebuild of the GA jar.** It publishes
   when it carries preview-dependent code, and it does so from its first release.

## Why the preview line is for Valhalla, not for `StructuredTaskScope`

`StructuredTaskScope` was the reason this question arose and is no longer a reason for anything here:
RFC-2026-08-08 established that the wrapper built on it did nothing, and callers who want it can use
the JDK type directly on a line where it exists.

The forcing content is **JEP 401 (Value Objects, preview)** in JDK 28, with **JEP 539 (Strict Field
Initialization, preview)** as its soundness companion. Value classes are what this runtime's carriers
would actually be exercised against — `RequestScope` first, then the hot-path types the request path
allocates. That exercise has to happen somewhere before the preview line *becomes* the GA line.

Verified on `openjdk-28-ea+10`, rather than inferred from the release calendar:

| check | result |
|---|---|
| `value class` under `--release 28 --enable-preview` | compiles and runs |
| `value record RequestScope(UUID, String, Map<String,Object>)` — the real carrier shape | compiles and runs |
| emitted field flags | `ACC_STRICT_INIT` — JEP 539 in effect, not merely present |
| `ScopedValue` on 28 with no preview flag | GA, unchanged |
| class file | major 72, minor 65535 |

## The cost: a deliberate per-line source overlay

This is the ADR's real price and it is stated rather than glossed.

`value record` and `record` are **different source**. ADR-028 obligation 1's "one source tree" — no
`src/sb3`, no `src/sb4` — therefore cannot hold across the JDK axis for the carriers. RFC-2026-08-08
had just removed an *accidental* per-line split (`ExerisStructuredScope`, a no-op wrapper); this ADR
reintroduces a *deliberate* one. The difference is that this split buys the exercise the preview line
exists for, and the previous one bought nothing.

Two bounds keep it honest:

- **The overlay is confined to carrier types.** Not modules, not packages that happen to contain a
  carrier — the specific types being exercised.
- **A guard asserts public-API identity.** Each overlay class must expose the same public API as its
  GA counterpart, so the divergence is a modifier and never a contract. If the two ever differ in
  signature, the build fails: consumers moving between lines must not be recompiling against a
  different surface.

  **It cannot be an ArchUnit rule** — see §"ArchUnit does not run on the preview line". The mechanism
  is deliberately dumber and therefore portable: the GA build emits a public-API signature snapshot per
  carrier, the snapshot is checked in, and the preview line asserts its overlay class matches by plain
  reflection. No bytecode inspection, so no ASM, so no class-file-major ceiling. The two classes share
  a fully-qualified name and can never be on one classpath, which is the other reason a direct
  comparison is not available.

ADR-028 obligation 1 is not weakened for the Spring axis, which is what it was written about. It is
read as axis-specific rather than universal, and this ADR says so explicitly so the next reader does
not apply it to the wrong axis — the same mistake ADR-067 had to correct in the other direction.

## ArchUnit does not run on the preview line

The architecture-guard layer — 16 test classes, including `WallIntegrityTest`, `ModuleBoundaryTest`,
the eight `PureModeClasspathGuardTest`s, `CompatibilityIsolationGuardTest` and
`RequestScopeArchitectureTest` — **is excluded from the preview line**. ArchUnit 1.5.0 tops out at
JDK 27 support; there is no JDK 28 support to pin to.

The justification is not the missing version, it is the **failure mode**, and this repository has
already recorded it. From the root POM's own rationale for pinning 1.4.2:

> 1.3.0's older ASM throws "Unsupported class file major version 70" and **silently skips those
> classes** — which would let the Wall/boundary guards pass without actually inspecting Java 26
> bytecode.

An ArchUnit whose ASM cannot read the class-file major does not fail; it inspects nothing and reports
green. Running the guard suite on major 72 would therefore produce 16 passing tests that verified
nothing — a fail-open gate, and a worse outcome than not running them, because a green guard is read
as evidence. Excluding them is the correct action, not a reluctant concession.

The exclusion must be **explicit** in the preview profile. Leaving the suite enabled in the hope that a
future ASM handles major 72 is precisely how the vacuous pass arrives unnoticed.

**Consequence: the GA line is the sole authority for the architecture-guard layer.** A change is never
validated by the preview line alone. Since the two lines share one source tree apart from the carrier
overlay, the GA line's guard run covers everything except the overlay itself — which is the bound that
makes this acceptable.

**The hole this leaves, stated rather than papered over:** `RequestScopeArchitectureTest` bans
`ThreadLocal` under `eu.exeris.spring.runtime.web.scope..`, and the overlay carrier lives there. A
`ThreadLocal` introduced *only* in the overlay would be caught by neither the GA guard run (different
file) nor the identity guard (a private field is not public API). The mitigation is the overlay's size
and review, not a machine — and that is a reason to keep the overlay to carrier types and nothing else.

## Prerequisite: a preview-clean kernel

Not JDK 28, which is downloadable today. **Preview class files are major-pinned**, measured:

```
UnsupportedClassVersionError: P (class file version 70.65535) was compiled with preview features
that are unsupported. This version of the Java Runtime only recognizes preview features for
class file version 72.65535
```

Kernel 0.10.2 is itself compiled with preview on JDK 26, so its classes are `70.65535` and **cannot
load on a JDK 28 JVM at all** — the preview line cannot run a single test against the current pin.
Once the kernel ships its ADR-066 substitution its classes are ordinary (`minor 0`) and load on 28 by
normal forward compatibility.

So the `-preview` artefact waits on the kernel's preview-clean build, which kernel ADR-066 targets at
the same 0.11.0 this repository is already holding 0.7.0 for. If 0.11.0 ships the structured-concurrency
layer without the substitution, the preview line waits for the release that carries it.

> **Prerequisite met (2026-08-19).** It did ship the substitution. Kernel 0.11.0 is preview-clean —
> measured at the cut as 2 286 classes across eight published modules at class-file major 69 with zero
> preview stamps — and the kernel also published a companion `0.11.1-preview` in which its carriers are
> JEP 401 value classes. Both halves of the conditional above therefore resolved in this ADR's favour:
> the GA line has a preview-clean kernel to compile against (taken by this repository's 0.11.0 pin and
> the JDK 25 baseline that followed), and the preview line has a kernel counterpart that already
> carries value-class content rather than being an empty coordinate.
>
> This unblocks the `-preview` artefact; it does not schedule it. Nothing about the decision changes —
> the cost stated below (a per-line source overlay for carrier types, an API-identity guard, and a CI
> axis on an EA JDK that must report rather than block) is unchanged and is what the implementing
> slice has to pay.

## Scope and Non-Goals

**In scope.** What this repository publishes, on which JDK lines, and where the two lines' sources may
diverge.

**Not in scope.**

- **Which JDK each line pins.** The GA line follows the kernel's LTS; the preview line follows the
  kernel's preview branch. Tracking those is mechanical and does not need an ADR per bump.
- **The Spring Boot axis.** Governed by ADR-028 and ADR-067 and untouched here.
- **Value-class adoption beyond carriers.** Exercising `RequestScope` is the first slice, not a
  commitment to convert every type.
- **Any GA-line behaviour change.** The GA artefact is what ships today, unchanged.

## Alternatives Considered

**Publish only the GA artefact; track preview as a CI axis without releasing it.** Rejected on the
decider's call. It is the cheaper path and would have been defensible while the preview line had no
content — a `--release 25` jar loads unchanged on a JDK 28 JVM, so a content-free `-preview` coordinate
would be a second distribution to reason about in exchange for nothing. Adopting value classes removes
that objection: with carrier content the artefact is materially different, and shipping it is how the
Valhalla work reaches anyone.

**Publish `-preview` immediately as a JDK-target rebuild, add content later.** Rejected. It trains
consumers to treat the two coordinates as interchangeable, which they would be — until one day they
are not.

**Do nothing; single line on JDK 26 with `--enable-preview`.** Rejected by RFC-2026-08-08 and its
withdrawal: `--enable-preview` is whole-compilation and whole-JVM, so it propagates to a brownfield
consumer's entire build.

## Consequences

- **[+]** Valhalla-readiness gets exercised before it becomes the GA line, which is the whole reason
  the kernel's preview branch exists.
- **[+]** The distribution matches the kernel's, so a consumer picking a track picks it once across the
  platform rather than per component.
- **[+]** JEP 539's strict field initialization applies to the carriers on the preview line, which is
  where a record's array-component equality traps would surface early.
- **[-]** A per-line source overlay, with the maintenance and the guard it requires. Bounded, but real,
  and it re-opens something RFC-2026-08-08 had just closed.
- **[-]** Two artefacts double the release surface: two deploys, two coordinate sets in the BOM, and a
  question every consumer must answer once.
- **[-]** The preview line runs on an EA JDK, so its CI is exposed to EA churn — a broken EA build
  blocks a line that blocks nothing in production, and the policy for that needs to be "report, do not
  block the GA line."
- **[-]** The preview line ships without the architecture-guard layer, because running it there would
  pass vacuously rather than fail. Bounded by the GA line covering the shared source, and by the
  overlay staying small — but it is a genuine asymmetry between the two artefacts' verification, and
  the `ThreadLocal`-in-overlay hole above is its sharp edge.

## Compliance / Verification

- `openjdk-28-ea+10` measurements above, reproducible from the scratch probes.
- The preview line's build must fail if the kernel on the classpath carries preview class files —
  otherwise the failure surfaces as an unexplained `UnsupportedClassVersionError` mid-suite.
- The public-API identity guard between overlay and GA carrier is merge-blocking, and is reflection
  based rather than ArchUnit based so it survives the class-file-major ceiling.
- The preview profile **explicitly excludes** the ArchUnit suite. A preview build that reports those
  tests as passing is a defect in the profile, not a signal about the code.
- ADR-067's binary-neutrality gate stays scoped to the Spring axis. It compares one source compiled
  twice; across the JDK axis the sources differ by design, so extending it unmodified would report the
  intended divergence as a failure.

## Engineering Protocol

1. A PR adding a source overlay file outside the carrier set is an ADR-068-violating PR.
2. A PR that lets the overlay and GA carrier diverge in public signature is rejected by the identity
   guard. The fix is the signature, never a suppression.
3. The `-preview` artefact is not published from a release whose only difference is the JDK target.
4. A red preview-line CI axis reports; it does not block the GA line.
5. No change is merged on preview-line evidence alone. The GA line is the sole authority for the
   architecture-guard layer, and a green preview run says nothing about The Wall, module boundaries,
   pure-mode classpath purity, or compat isolation.
6. Re-enabling ArchUnit on the preview line requires demonstrating that its ASM actually reads the
   current class-file major — by observing a guard **fail** on a deliberate violation, not by observing
   the suite pass.
7. When `value` exits preview at an LTS, the overlay merges into the single source tree and this ADR is
   superseded rather than amended — the two-line premise is the decision, not an implementation detail.

## Cross-references

- [RFC-2026-08-08](../rfc/RFC-2026-08-08-two-track-jdk-line.md) — the accepted RFC that left this
  decision open, and removed the class that would have forced an accidental version of the same split.
- [ADR-067](ADR-067-binary-neutrality-of-the-published-artefact.md) — the Spring axis stays
  classifier-free; this ADR explains why that does not transfer.
- [ADR-028](ADR-028-spring-boot-4-nominal-compatibility-scope.md) — obligation 1's one-source-tree rule,
  here read as axis-specific.
- [ADR-029](ADR-029-phase-3b-alpha-scope-request-scope-and-structured-concurrency.md) — `RequestScope`,
  the first carrier the preview line exercises.
