# RFC-2026-08-08: Tracking the kernel's two-track JDK model, and what happens to `ExerisStructuredScope`

| Field             | Value                                                                                  |
|:------------------|:---------------------------------------------------------------------------------------|
| **Status**        | **ACCEPTED**                                                                           |
| **Author(s)**     | Arkadiusz Przychocki                                                                   |
| **Date Opened**   | 2026-08-08                                                                             |
| **Date Closed**   | 2026-08-08                                                                             |
| **Target ADR(s)** | ADR-029 partial withdrawal (executed, same PR); the artefact/line decision is still owed an ADR |
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

### Does the wrapper add anything? Measured, and the answer is no

The reasoning above assumed the wrapper's explicit rebind is what carries `RequestScope` into a fork.
Probed instead of assumed — a **raw** `StructuredTaskScope`, no Exeris wrapper, no rebind anywhere:

```
[PROBE] raw STS fork          — RequestScope bound? true   tenantId matches outer scope
[PROBE] plain virtual thread  — RequestScope bound? false
```

`StructuredTaskScope` already propagates the binding. The wrapper's `callWith` rebinds a value STS has
**already carried**, and it is redundant in every reachable case: `StructuredTaskScope.open(...)`
captures at open, the wrapper captures at construction, and both happen in the same static factory
call on the same thread. Where the scope is opened outside a binding, the wrapper captures `null` and
STS inherits nothing — the two agree there too.

So the invariant in `phase-3b-alpha-invariants.md` §4 is a property of **`StructuredTaskScope`**, not
of anything this repository wrote. `tenantIdPropagatesAcrossForks` passes against the JDK.

What remains in the class is: three factory aliases for `StructuredTaskScope.open(Joiner.X())`,
delegating `join()` / `close()`, a diagnostic accessor, and a per-fork cost of roughly three
allocations — two lambdas plus a `ScopedValue` carrier — spent on the redundant rebind. That is the
inverse of this repository's own rule against promoting convenience by hiding cost.

ADR-029's stated rationale for the class was to *"keep a Spring-side surface that shields callers from
JDK preview-API iteration."* It does not: `fork()` returns the JDK's `Subtask`, the factory names
mirror `Joiner` semantics, and `join()` returns JDK-shaped results. The exposed surface **is** the
JDK's, so churn passes straight through — when `StructuredTaskScope` moves again on JDK 28, the
wrapper moves with it, having absorbed nothing.

### What the repository looks like without it

Measured by removing the class, deleting `--enable-preview` from the compiler args, and building:

- the reactor **compiles clean** with no preview flag;
- **zero** classes carry `minor_version 65535`.

The only surviving `--enable-preview` is the *test* JVM argument, and its stated reason is that
`exeris-kernel-core` is currently preview-compiled — which the pin move to a post-S19 kernel retires.

**One class stands between this repository and a preview-clean build, and its only semantic content is
a no-op.**

### Collateral: boot is no longer parallel

Subsystem start now takes the sum of start times rather than the maximum. Paid once per JVM, and
`FOUNDATION` was sequential regardless. `ExerisRuntimeProperties.LifecycleProperties` defaults
`startupTimeoutSeconds` to **30**, and `ExerisRuntimeLifecycle` throws `startup timed out` on expiry.
That budget was chosen against parallel start; it needs re-measuring against the new shape, not
re-assuming. BudgetHQ starts the widest subsystem set and is the right place to measure.

## Options Considered

### Option D: Withdraw `ExerisStructuredScope` entirely

Delete the class. `ExerisRequestScope` — GA-clean, and the half of Phase 3B-α with real content —
stays on both lines. Callers who want structured concurrency use `StructuredTaskScope` directly on a
line where it exists, and get the `RequestScope` propagation for free, because that is where the
propagation was coming from all along.

**Pros:**
- Removes the only preview-pinned class in the reactor. Both lines become preview-clean **today**,
  with no per-line source exclusion, so ADR-028 obligation 1 survives intact and the GA/`-preview`
  split reduces to a JDK and build concern.
- Removes the preview type from our public API by removing the API, rather than by wrapping it in
  another type that would also have to track JDK 28.
- Removes a per-fork allocation cost that buys nothing.
- Deletes the maintenance obligation outright: nothing to re-verify when `StructuredTaskScope` changes
  on 28, and nothing to converge when it goes GA at the next LTS.
- Withdrawing a default-off, 1.0-preview surface with no GA commitment is close to free now, and stops
  being free the moment it is promised.

**Cons:**
- Phase 3B-α loses a named deliverable, and ADR-029 obligation 2 must be withdrawn rather than
  amended — a decision-level change, not a doc edit.
- Callers lose the `failFast` / `firstSuccess` / `allSuccessful` naming over `Joiner`. This is an
  aliasing convenience, not a capability.
- If a consumer later wants scope propagation on the GA line, that work starts from nothing. It would
  have started from nothing anyway: the wrapper's propagation is STS's, and does not port.

**Cost:** one class and its test deleted; ADR-029 amended; roadmap and phase docs corrected.

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

**Option D — withdraw `ExerisStructuredScope` entirely.**

The probe changes the question. This was framed as "which line should the wrapper ship on", which
presumes the wrapper does something. It does not: `StructuredTaskScope` already propagates
`RequestScope`, the explicit rebind is redundant in every reachable case, and the §4 invariant we cite
as ours is the JDK's. What is left is three factory aliases and about three allocations per fork spent
on a no-op — and that no-op is why the reactor cannot build preview-clean, why a preview type sits in
our public API, and why the two-line split would need a per-line source exclusion.

Option A was the right answer to the wrong question. It keeps a class on the `-preview` line whose
only content is naming, and it accepts a source exclusion that breaks ADR-028 obligation 1 in order to
do so. Removing the class instead makes both lines preview-clean immediately from **one** source tree
— measured, not projected: with the class gone and the compiler flag removed, the reactor compiles and
carries zero preview-pinned classes.

The argument against Option B stands and gets stronger. It would build a GA layer whose propagation
consumers cannot rely on, duplicating a temporary kernel helper in the layer least entitled to own
concurrency — and we now know it would be replacing a wrapper that never provided the propagation in
the first place.

Residual uncertainty, stated plainly: this leaves neither line with a Spring-side structured-concurrency
helper, and we do not know whether any consumer wants one. The wrapper's existence is not evidence that
one does — it was never load-bearing. If the answer turns out to be yes, the honest starting point is an
explicit-carrier fork API restricted to slots we define, which is a different design from today's and
would have had to be written from scratch under any of these options.

### Why not the alternatives?

- **Option A** — ships a class whose only remaining content is naming, and pays for it with a per-line
  source exclusion that ADR-028 obligation 1 forbids.
- **Option B** — ships an API whose behaviour silently diverges from its shape, and duplicates a
  temporary kernel helper in the layer least entitled to own concurrency.
- **Option C** — pushes `--enable-preview` onto every brownfield consumer's whole build, which is the
  cost ADR-066 exists to remove.

### Risks of the recommendation

- **Withdrawing a published API, even a default-off one.** `ExerisStructuredScope` shipped in the
  `0.6.0` train and was consumed as `0.5.0-SNAPSHOT` downstream. Checked rather than assumed: neither
  `budgetHQ` nor `pbm` references the class, the `scope.concurrent` package, or
  `exeris.runtime.context.scope.enabled`. The known-consumer blast radius is zero. That does not cover
  an external brownfield customer, but the surface is default-off and carries no GA commitment, which
  is what the withdrawal rests on.
- **The §4 invariant and its two tests are deleted with the class.** They tested the JDK, so nothing
  real is lost — but the phase document must say *why* an invariant disappeared, or the next reader
  reads it as coverage quietly dropped.
- **Two artefacts is still a reversal of ADR-067's rejection of per-line classifiers** — for a
  different reason (one jar cannot be both major 69 and major 70), and it will read as inconsistency
  unless stated. The Spring axis stays classifier-free; only the JDK axis gains them. **Two artefacts,
  not four.**
- **The roadmap and ADR-029 both name `StructuredTaskScope` helpers as Phase 3B-α content.** Under this
  option they are not amended but *withdrawn*, which is the heavier move: ADR-029 obligation 2 must go
  through withdrawal rather than a body edit, per this repo's rule against silently amending an
  accepted ADR.

Two risks recorded in an earlier revision of this RFC **no longer apply**, and are noted so the
reasoning is not re-derived: the per-line source exclusion breaking ADR-028 obligation 1, and
ADR-067's binary-neutrality gate mis-reporting an intended divergence. Both were consequences of
keeping the class on one line only. With the class gone, both lines compile from one source tree with
the same set of classes — and the gate arguably *should* be extended to the JDK axis, since the same
source compiled at two `--release` levels can still bind to different JDK overloads. That extension is
follow-up work, not a risk.
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
| **Outcome**          | **ACCEPTED** |
| **Date**             | 2026-08-08 |
| **Resulting ADR(s)** | **ADR-029, obligations 2 and 6 withdrawn** — executed in the same PR that accepted this RFC. No new ADR number was minted for it: the outcome reverses part of an existing decision rather than taking a new one, so it is recorded as a withdrawal section in ADR-029 with the measurements, per this repo's rule against silently amending an accepted body. **The artefact/line decision — one GA artefact plus one `-preview` artefact, Spring axis classifier-free — is still owed its own ADR and is not covered here.** |
| **Notes**            | The recommendation changed between DRAFT and ACCEPTED, from "ship the class on the `-preview` line" to "withdraw it", on the strength of one probe. Both revisions are in the PR history deliberately: the first is what the reasoning produced, the second is what measuring produced, and the gap between them is the point. |

## Open questions / follow-ups

- **Re-measure the startup budget.** `startupTimeoutSeconds` defaults to 30, chosen against parallel
  subsystem start. Measure against the sequential shape on the widest subsystem set before the kernel
  pin moves — owner: this repo, gated on a released kernel carrying S19.
- **Does any consumer want structured concurrency on either line?** Answer with a consumer, not a
  design — and specifically check BudgetHQ for a compile-time dependency on `ExerisStructuredScope`
  before the deletion lands. If the answer is yes, scope an explicit-carrier fork API restricted to
  slots we define.
- **Extend the ADR-067 binary-neutrality gate to the JDK axis.** The same source compiled at two
  `--release` levels can bind to different JDK overloads — the identical failure class the gate was
  built for, on a different axis. Cheap to add once both lines build from one tree.
- **How do the Spring and JDK axes compose in CI?** Four build combinations exist; running all four on
  every push may not be worth it. Decide which cells are load-bearing.
- **`ExerisRequestScope.callWith` as the documented GA answer.** It is already the right primitive and
  is not currently presented as one. Documentation task, independent of this decision.
