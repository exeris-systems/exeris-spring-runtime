---
title: "ADR-107: One main jar for both Spring Boot lines, published to Maven Central with its sources, javadoc and signatures"
type: adr
visibility: public
owning-repo: exeris-spring-runtime
status: active
slug: adr/ADR-107
---

# ADR-107: One main jar for both Spring Boot lines, published to Maven Central with its sources, javadoc and signatures

| Attribute       | Value                                                                                                                                                                                                             |
|:----------------|:------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **Status**      | **ACCEPTED** (drafted and accepted 2026-10-30; single decider — no future gating event; ratified by the PR that introduces this file). **Supersedes [ADR-067](ADR-067-binary-neutrality-of-the-published-artefact.md).** |
| **Deciders**    | Arkadiusz Przychocki                                                                                                                                                                                              |
| **Date**        | 2026-10-30                                                                                                                                                                                                        |
| **Scope**       | spring/build (root `pom.xml` `release` profile, `.github/workflows/deploy.yml`, `.github/maven-settings.xml`; no runtime behaviour change)                                                                       |
| **Owning Repo** | `exeris-spring-runtime`                                                                                                                                                                                           |
| **Driven By**   | Publishing releases to Maven Central, which validates every jar coordinate for a sources jar, a javadoc jar and a signature on each file; and ADR-067 Engineering Protocol 4, which makes a profile or classifier on the publish path a supersession |
| **Compliance**  | [ADR-028](ADR-028-spring-boot-4-nominal-compatibility-scope.md), [ADR-068](ADR-068-two-track-jdk-artefact-model.md), [Spring Boot 4 matrix](../architecture/spring-boot-4-matrix.md)                             |

## Context and Problem Statement

ADR-067 decided that **one jar**, compiled against Spring Boot 3, serves both Spring Boot lines, and
made that claim true by enforcement: the `binary-neutrality` job diffs every constant-pool reference
the compiled classes make into `org/springframework/**` across the two lines and fails the build on any
delta. It stated its premise as the decision rather than as an implementation detail, and protected it
with Engineering Protocol 4: *"If `deploy.yml` ever gains a profile or a classifier, this ADR is
superseded rather than amended."*

Publishing to Maven Central needs exactly those two things on the release path. Central refuses a
deployment unless every jar coordinate carries a `-sources` jar and a `-javadoc` jar beside it and
every file — pom included — carries a detached signature. Those are produced by a `release` profile,
and the two extra jars are classifiers.

Neither of them is what Protocol 4 was written against. The protocol guards the single-artefact
premise: a profile that compiled the published jar against a different Spring line, or a per-line
classifier on the main jar, would publish something the `binary-neutrality` job never compared. A
sources jar and a javadoc jar contain no bytecode a consumer links against. But the protocol's wording
does not draw that line, and rewording a protection to fit the change that crosses it is the amendment
it forbids. So the premise is restated here, with the release path it now has.

## 🏁 The Decision

**The single SB3-compiled main jar stays the only binary artefact per module, and it is published to
Maven Central with its sources jar, javadoc jar and signatures. ADR-067's enforcement carries over
unchanged.**

1. **One main jar per module, compiled against Spring Boot 3, serving both lines.** No per-line
   classifier, no per-line profile on the publish path. The `release` profile adds files beside that
   jar and changes nothing about how it is compiled: the publish command names `matrix-sb3` explicitly
   alongside `release`, so the Spring line does not depend on `activeByDefault`, which an explicit
   `-P` deactivates.
2. **Binary neutrality stays an enforced obligation.** ADR-067's decision points 2–4 and its
   Engineering Protocol 1–3 and 5 are restated as this ADR's own: every Spring call site binds to the
   same descriptor on both lines; the `binary-neutrality` job compares them and fails the build on a
   delta; the fix for a delta is a neutral member, never a suppression or a profile guard; "it compiles
   on both profiles" is never a sufficient closure statement.
3. **The permitted classifiers are `sources` and `javadoc`, and only on the release path.** They carry
   no linkable bytecode. Any other classifier, or any profile that changes the Spring line a published
   jar is compiled against, supersedes this ADR.
4. **Releases go to Maven Central; snapshots stay on GitHub Packages.** The `release` profile is the
   only place `central-publishing-maven-plugin` is declared, because the plugin takes over the deploy
   phase and would otherwise capture the snapshot publish. A release uploads to Central with
   `autoPublish=false` and `waitUntil=validated`: the deployment is validated, and a person publishes
   it in the Central Portal. Until then it can be dropped.
5. **The kernel resolves from Maven Central.** The build declares no `<repositories>` and needs no
   package credentials.

## Scope and Non-Goals

- **In scope:** what the release path publishes and where; the restated single-artefact premise.
- **Not in scope:** the JDK axis. ADR-068's `-preview` line takes a JDK classifier for a reason this
  ADR does not touch — one jar cannot be two class-file majors — and it supplements this ADR the way it
  supplemented ADR-067.
- **Not in scope:** the Spring Security 7 axis (ADR-028 obligation 6), unchanged.

## Alternatives Considered

- **Amend ADR-067's Protocol 4 to exempt `sources`/`javadoc`.** Rejected: the protocol says in so many
  words that the premise is superseded rather than amended, and narrowing a protection in place is the
  silent amendment `adr-triggers.md` forbids.
- **Keep releases on GitHub Packages only.** Rejected: every consumer then needs a GitHub token to
  resolve a public Apache-2.0 library, and the kernel this runtime binds to already resolves from
  Central.
- **Build the release on the SB4 line, or publish one jar per line.** Rejected for the reasons ADR-067
  recorded: per-line jars double the published surface and the consumer's version decision without
  removing the need for the descriptor comparison.

## Consequences

- **[+]** Consumers resolve releases with no repository entry and no credentials.
- **[+]** The single-artefact premise and its enforcement are unchanged; only the files beside the jar
  and the channel move.
- **[−]** A release needs a GPG key and a Central user token in the repository's secrets, and a person
  in the loop to press Publish.
- **[−]** The release path runs two Maven invocations (Central, then GitHub Packages), so the GitHub
  Packages jar is a second build of the same commit. Both use `matrix-sb3`; byte identity between the
  two is not asserted yet.

## Risks and Assumptions

- **Assumption: Central's per-coordinate requirements stay what the bundle check encodes** — a pom,
  a main jar, a sources jar and a javadoc jar per jar coordinate, and a detached signature on every
  file. If Central adds a requirement, a release fails at upload validation, before anything is
  published, tagged or deployed to GitHub Packages.
- **Assumption: the two release builds produce the same main jar.** They build one commit with one
  profile set on one JDK, but nothing compares their bytes, so a consumer of GitHub Packages and a
  consumer of Central could in principle link against different class files.
- **Risk: the release depends on three secrets and a person.** A missing or expired GPG key or
  Central token stops the release at the signing or upload step; a deployment nobody publishes stays
  validated and unpublished.
  A measured difference is fixed by publishing the same files to both channels, not by reversing
  this decision.
- **Reversed by:** a `binary-neutrality` delta for which no neutral member exists, so that only a
  per-line jar serves both Spring Boot lines; or a Central requirement that only a classifier other
  than `sources` or `javadoc`, or a per-line build, can satisfy. Engineering Protocol 4 turns either
  into a supersession.

## Compliance / Verification

- `binary-neutrality` job in `build.yml`, unchanged.
- `mvn -P release,matrix-sb3 clean verify` produces, per jar module, the main jar plus `-sources` and
  `-javadoc` jars, and a signature on every file including the poms.
- `deploy.yml` checks the Central bundle before anything is deployed to GitHub Packages or tagged:
  every jar coordinate has its pom, jar, sources and javadoc, and every file its signature. The
  `centralDryRun` input runs the same build with `-DskipUpload` and changes nothing remote.

## Engineering Protocol

1. A PR that introduces a Spring call site binding differently on the two lines is rejected by the
   `binary-neutrality` job. The fix is a neutral member, **not** a suppression and not a profile guard.
2. A PR that removes or weakens the `binary-neutrality` job violates this ADR unless it supersedes it.
3. When a friction item is closed against the SB4 line, "it compiles on both profiles" is **not** a
   sufficient closure statement. State what was checked: the compiled descriptors, the behaviour, or
   both.
4. If the publish path ever gains a classifier other than `sources` or `javadoc`, or a profile that
   changes the Spring line a published jar is compiled against, this ADR is superseded rather than
   amended.
5. Adding a third Spring Boot line means adding a profile and extending the script's line list; the
   comparison generalises to pairwise diffs without changing shape.

## Cross-references

- [ADR-067](ADR-067-binary-neutrality-of-the-published-artefact.md) — superseded; its measurements and
  the two call-site fixes remain the record of why the descriptor comparison exists.
- [ADR-028](ADR-028-spring-boot-4-nominal-compatibility-scope.md) — the dual matrix.
- [ADR-068](ADR-068-two-track-jdk-artefact-model.md) — the JDK axis and its classifier.
