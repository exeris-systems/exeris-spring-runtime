# RFC-2026-08-08: Tracking the kernel's two-track JDK model, and what happens to `ExerisStructuredScope`

| Field             | Value                                                                                  |
|:------------------|:---------------------------------------------------------------------------------------|
| **Status**        | **DRAFT**                                                                              |
| **Author(s)**     | Arkadiusz Przychocki                                                                   |
| **Date Opened**   | 2026-08-08                                                                             |
| **Date Closed**   | —                                                                                      |
| **Target ADR(s)** | TBD — expected two: the artefact/line decision, and the `ExerisStructuredScope` disposition |
| **Affected Repos**| `exeris-spring-runtime` (decision), `exeris-kernel` (upstream driver, no change asked of it) |
| **Reviewers**     | —                                                                                      |

## Question

Kernel ADR-066 splits the platform into two tracks: a **default line** on an LTS JDK with no preview
flags, and a **`preview` branch** on the latest JDK where `StructuredTaskScope` lives. The decision to
follow both — publishing a GA artefact and a `-preview` artefact — is made. This RFC answers the part
that is not: **what happens to `ExerisStructuredScope`**, the one class in this repository that cannot
exist unchanged on both lines, and what the two-line split costs elsewhere.

## Context

| | Kernel default line (`main`) | Kernel `preview` branch |
|---|---|---|
| JDK | LTS only — 25 today, 29 next | Latest — 28 today |
| Preview flags | none | `--enable-preview`, by definition |
| Concurrency | virtual threads + explicit `ScopedValue` rebind (both GA) | `StructuredTaskScope` |
| Artefact | `1.0` | `1.0-preview` |

Two upstream facts bound this decision, and both are settled rather than open:

- **`eu.exeris.kernel.core.concurrent.StructuredScope` is not a consumer surface and will never
  become one.** It is a transitional helper scoped to live only until `StructuredTaskScope` exits
  preview, at which point it is deleted. It sits in `exeris-kernel-core`, outside the ADR-065
  compatibility gate — no stability-matrix row, no japicmp, no signal on change — but the disqualifier
  is the scheduled removal, not the missing gate. Promotion to SPI is explicitly off the table.
- **Demand for richer joiner policies fell rather than rose.** Two of the kernel's four
  `StructuredTaskScope` sites stopped forking altogether; the answer was in-thread execution, because
  the contract required arbitrary application `ScopedValue`s and no GA mechanism supplies them.
  `failFast` / `firstSuccess` would not have rescued either site.

## Investigation

### How much of this repository is actually preview-pinned

Measured with `javap` over every compiled class in the reactor, looking for `minor_version 65535`:

| | classes | preview-pinned |
|---|---:|---:|
| `exeris-spring-runtime` (whole reactor) | 131 | **1** |
| `exeris-kernel` after the S19 substitution, still compiled with `--enable-preview` on JDK 26 | 930 | **0** |

The single pinned class is `eu.exeris.spring.runtime.web.scope.concurrent.ExerisStructuredScope`.
`javac` marks only classes that actually use a preview feature, not the whole compilation — so the
blast radius is one class, not one jar. `ExerisRequestScope` compiles clean: `ScopedValue` is GA on
JDK 25.

Consuming a kernel compiled at `--release 25` (major 69) from a JDK 26 build works unchanged. **The
pressure to move is product-side, not compile-side.**

### The sharper problem is the signature, not the class

```java
public <U extends T> Subtask<U> fork(Callable<? extends U> task)
```

`Subtask` is `java.util.concurrent.StructuredTaskScope.Subtask` — a **preview type in our public
API**. The class javadoc defends this: *"the returned `Subtask` is the JDK type by design: wrapping it
would add a per-fork allocation without semantic value."* That trade exchanges one allocation per fork
for a preview requirement on every consumer's entire build, because `--enable-preview` is
whole-compilation and whole-JVM. Those are not comparable quantities, least of all for a brownfield
enterprise Spring application — the primary commercial consumer of this repository. The kernel avoided
the same trap deliberately by returning its own `ForkedTask`.

### What we promise versus what we deliver

`phase-3b-alpha-invariants.md` §4 promises narrowly: *"`ExerisStructuredScope.fork(...)` rebinds the
captured `RequestScope`."* That is a slot **we** define, rebound **explicitly** through
`ExerisRequestScope.callWith` — not inherited. It survives a move to explicit-carrier forking intact,
along with `tenantIdPropagatesAcrossForks` and `tenantIdIsolatesPerOutermostRequest`.

What does **not** survive is the part nobody wrote down: `StructuredTaskScope` propagates *all*
`ScopedValue` bindings in effect at scope open. Today that silently carries kernel provider slots —
`callWith` binds only `RequestScope`, so `MEMORY_ALLOCATOR` and `PERSISTENCE_ENGINE` reach a forked
body purely by STS inheritance — **and any `ScopedValue` the consuming application defines**.

The kernel measured what that costs. Reconstructing a carrier and forking with it produced a boot in
which the HTTP subsystem started with no bound handler and every route answered 404. The lost binding
was not a kernel slot at all: it was `HTTP_SERVER_HANDLER`, bound by the *application* around
`boot()`. The general rule it establishes:

> You cannot enumerate what you do not define. Wherever an application-defined `ScopedValue` must
> reach the task, the GA answer is in-thread execution, not a better fork. Forking stays safe only
> where the body needs solely the slots you define yourself — there, `open(Carrier)` carries them
> explicitly.

Applied here, the conclusion is stronger than a porting note. **`ExerisStructuredScope` is a public API
whose forked bodies are application code.** It is the arbitrary-application-`ScopedValue` case by
construction, not by accident. On a GA line it cannot offer transparent propagation to anyone — and
the failure mode is the kernel's 404, moved one level out, where the lost slot is one we never knew
existed because the consumer defined it.

This is also the answer to "so add joiner policies to make it work": no policy fixes a binding that
was never carried.

### Collateral: boot is no longer parallel

Subsystem start now takes the sum of start times rather than the maximum. Paid once per JVM, and
`FOUNDATION` was sequential regardless. `ExerisRuntimeProperties.LifecycleProperties` defaults
`startupTimeoutSeconds` to **30**, and `ExerisRuntimeLifecycle` throws `startup timed out` on expiry.
That budget was chosen against parallel start; it needs re-measuring against the new shape, not
re-assuming. BudgetHQ starts the widest subsystem set and is the right place to measure.

## Options Considered

### Option A: `ExerisStructuredScope` ships on the `-preview` line only

The GA artefact does not contain the class. `ExerisRequestScope` — already GA-clean — remains the
supported primitive on both lines, and GA-line users doing concurrent work either run in-thread or
rebind explicitly on threads they spawn themselves.

**Pros:**
- The preview type stays on the line whose premise *is* preview, so the `Subtask` leak stops being a
  defect and becomes a property of that line. No wrapper type, no per-fork allocation, no reflection.
- Nothing is promised on the GA line that the GA mechanism cannot deliver. The transparent-propagation
  guarantee is not silently downgraded — it is simply absent where it cannot hold.
- Zero new code. The divergence is a build concern, not an API concern.
- Both lines converge for free when `StructuredTaskScope` goes GA at the next LTS: the class moves to
  the single line and `Subtask` becomes a GA type, on the same schedule that deletes the kernel's own
  helper.

**Cons:**
- The GA line — the commercially primary one — ships without a structured-concurrency helper.
- One source tree cannot compile both lines while the class exists on only one, so the module needs a
  per-line source exclusion. That is precisely what ADR-028 obligation 1 forbids for the Spring axis.
- Phase 3B-α's roadmap entry names `StructuredTaskScope` helpers as deliverables; it needs amending.

**Cost:** build configuration plus documentation. No production code change.

### Option B: Build our own GA structured-concurrency layer in `exeris-spring-runtime`

Own the fork/join/cancel layer over virtual threads with explicit `ScopedValue` rebinding, mirroring
what the kernel built for itself, and put `ExerisStructuredScope` on top of it for both lines.

**Pros:**
- One API on both lines; no per-line source exclusion.
- Removes `Subtask` from the signature as a side effect.

**Cons:**
- It cannot deliver the API's main implicit value. Application-defined `ScopedValue`s still do not
  propagate, so consumers get an API that looks like today's and silently behaves differently — the
  worst of the available outcomes, and the exact failure the kernel spent a boot regression learning.
- Duplicates a kernel helper that is itself temporary, so we would build, gate, test and document
  something with the same known expiry and no shared maintenance.
- Structured concurrency is runtime-owner territory. Building a second one in the Spring layer inverts
  the ownership model this repository exists to demonstrate.

**Cost:** a new concurrency primitive with its own test and guard surface, deleted at the next LTS.

### Option C (do nothing): stay single-line on JDK 26 with `--enable-preview`

**Pros:**
- No work. Nothing breaks today: a JDK 25 kernel loads fine in a JDK 26 build.

**Cons:**
- Forfeits the entire point of ADR-066 for our consumers. `--enable-preview` propagates to a brownfield
  application's whole codebase and pins it to one JDK major — a far larger ask of an enterprise Spring
  shop than of a greenfield kernel-direct SKU, and this repository's primary consumer is exactly that
  shop.
- Diverges from a platform decision already taken, leaving this repo as the only component that cannot
  ship a preview-clean artefact.

## Recommendation

**Option A — `ExerisStructuredScope` ships on the `-preview` line only.**

The decisive argument is not cost, it is honesty about what the GA mechanism can do. Option B produces
an API with today's shape and different behaviour, and the difference is invisible until a consumer's
own `ScopedValue` comes back unbound inside a fork — the kernel's 404, relocated to a customer's
application where we cannot even name the missing slot. Option A declines to make a promise instead of
making one it cannot keep. Given Phase 3B-α is default-off, 1.0-preview, and carries no GA commitment,
declining now is nearly free; it will not be later.

Option A also resolves the `Subtask` leak without writing anything. The leak is only a defect on a line
that claims to be preview-clean. On the `-preview` line, preview is the premise — `Subtask` in the
signature is accurate there, and the allocation argument in the javadoc becomes valid rather than
merely convenient.

Residual uncertainty, stated plainly: this leaves the commercially primary line without a
structured-concurrency helper, and we do not yet know whether any consumer wants one. That is a
question to answer with a consumer, not with a design. If the answer turns out to be yes, the right
response is an explicit-carrier fork API restricted to slots we define — narrower than today's, and a
different decision from this one.

### Why not the alternatives?

- **Option B** — ships an API whose behaviour silently diverges from its shape, and duplicates a
  temporary kernel helper in the layer least entitled to own concurrency.
- **Option C** — pushes `--enable-preview` onto every brownfield consumer's whole build, which is the
  cost ADR-066 exists to remove.

### Risks of the recommendation

- **Per-line source exclusion contradicts ADR-028 obligation 1** ("no `src/sb3`, no `src/sb4`") if read
  as a general rule rather than one about the Spring axis. The resulting ADR must say which axis each
  obligation governs, or the next reader will apply the wrong one.
- **ADR-067's binary-neutrality gate does not extend to this axis.** It compares the same source
  compiled twice. Here the lines contain *different* sets of classes, so a descriptor diff would report
  the intended divergence as a failure. The gate needs an explicit scope statement, not a silent
  assumption that it generalises.
- **Two artefacts is a reversal of ADR-067's rejection of per-line classifiers** — for a different
  reason (one jar cannot be both major 69 and major 70), but it will read as inconsistency unless
  stated. The Spring axis stays classifier-free; only the JDK axis gains them. **Two artefacts, not
  four.**
- **The roadmap and ADR-029 both name `StructuredTaskScope` helpers as Phase 3B-α content** without a
  line qualifier. Left unamended, they will read as a GA-line promise.
- **"0.6.0-preview" is a naming collision waiting to be misread.** ADR-029 scopes 3B-α to the
  `0.6.0-preview` *release train* and calls it "pure JDK 26 preview features" in the same sentence.
  Those are two different meanings of the word: the train suffix is a maturity label that
  deliberately never reaches a published Maven coordinate, while the JDK line is a distribution.
  A reader can easily conclude 3B-α was always preview-line content and that Option A changes
  nothing — it does, because the train label says nothing about which artefact ships the class. Any
  resulting ADR should retire or qualify one of the two usages rather than let them sit adjacent.

## Decision Record

*Filled in when status reaches ACCEPTED / REJECTED / WITHDRAWN.*

| Field                | Value |
|:---------------------|:------|
| **Outcome**          | —     |
| **Date**             | —     |
| **Resulting ADR(s)** | —     |
| **Notes**            | —     |

## Open questions / follow-ups

- **Re-measure the startup budget.** `startupTimeoutSeconds` defaults to 30, chosen against parallel
  subsystem start. Measure against the sequential shape on the widest subsystem set before the kernel
  pin moves — owner: this repo, gated on a released kernel carrying S19.
- **Does any consumer want structured concurrency on the GA line?** Answer with a consumer, not a
  design. If yes, scope an explicit-carrier fork API restricted to slots we define.
- **How do the Spring and JDK axes compose in CI?** Four build combinations exist; running all four on
  every push may not be worth it. Decide which cells are load-bearing.
- **`ExerisRequestScope.callWith` as the documented GA answer.** It is already the right primitive and
  is not currently presented as one. Documentation task, independent of this decision.
