# ADR-027: Spring Boot 4 Nominal Compatibility Scope for 1.0

| Attribute       | Value                                                                                                                                                                                                                                    |
|:----------------|:-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **Status**      | **ACCEPTED** (drafted and accepted 2026-05-17; single decider — no future gating event; ratified by the PR that introduces this file)                                                                                                    |
| **Deciders**    | Arkadiusz Przychocki                                                                                                                                                                                                                     |
| **Date**        | 2026-05-17                                                                                                                                                                                                                               |
| **Scope**       | spring (binds the Maven reactor's `spring-boot-dependencies` import; affects autoconfigure/web/tx/data/actuator/events/flow module compile classpaths; introduces a CI matrix invariant)                                                |
| **Owning Repo** | `exeris-spring-runtime`                                                                                                                                                                                                                  |
| **Driven By**   | Commercial migration path identified in `CLAUDE.md` §"Who consumes this repo" — brownfield Spring customers increasingly arrive on SB4 codebases. A 1.0 release pinned only to SB 3.5+ would force a 1.1.x dependency for SB4 adopters. |
| **Compliance**  | [Module Boundaries](../architecture/module-boundaries.md), [Roadmap to 1.0 and TRL-9](../roadmap-1.0-trl9.md), [Phase 0 Invariants](../phases/phase-0-invariants.md), [Phase 1 Invariants](../phases/phase-1-invariants.md)               |

## Context and Problem Statement

The repository currently pins `spring.boot.version=3.5.14` in the root POM (verified at `pom.xml:60`) and imports `spring-boot-dependencies` 3.5.x into the reactor. All modules compile against Spring Framework 6.1.x types transitively. Spring Boot 4.0 has reached general availability; Spring Framework 7 is its underlying core. Downstream Spring applications — the primary commercial consumer per `CLAUDE.md` §"Who consumes this repo" — are increasingly authored on the SB4 line, and the rate of SB4 adoption on greenfield brownfield-migration candidates is non-trivial.

A 1.0 release that supports only SB 3.5+ has two failure modes against the stated commercial path:

1. **SB4 customers cannot migrate until 1.1.x.** Their existing applications run SB4 already; the runtime cannot host them without a version bump that doesn't yet exist. The repository's commercial value at 1.0 is reduced to "brownfield customers still on SB 3.x" — a narrowing segment.
2. **The 1.0 support statement carries a hidden expiry.** Spring Boot 3.x support windows are bounded; promising "1.0 supports Spring Boot 3.5+" means the support statement narrows on its own as 3.x reaches end-of-OSS-support without an SB4 path having been validated.

The cost of supporting SB4 at this layer is bounded by what we actually consume from the framework. A repo-wide grep (executed 2026-05-17) classifies main-source imports into these top-level Spring packages:

| Top-level package          | Imports (count) | Stability outlook for SB3 → SB4                                                                                                |
|:---------------------------|:----------------|:-------------------------------------------------------------------------------------------------------------------------------|
| `org.springframework.beans`        | 6  | Stable. `Autowired`, `Qualifier`, `FactoryBean`, `InitializingBean`, `ObjectProvider`, `SmartInitializingSingleton` unchanged. |
| `org.springframework.boot`         | 14 | Mostly stable. `@AutoConfiguration`, `@ConditionalOn*`, `@ConfigurationProperties`, `@ConstructorBinding`, `@DefaultValue`, `@EnableConfigurationProperties`, `ApplicationConversionService`. Anticipated friction: `spring-boot-actuate-autoconfigure` reorganizations. |
| `org.springframework.context`      | 11 | Stable. `@Bean`, `@Conditional`, `@Configuration`, `@Primary`, `ApplicationContext`, `SmartLifecycle`, `LocaleContextHolder`, `SimpleLocaleContext`. |
| `org.springframework.core`         | 11 | Stable. `AnnotationUtils`, `Conventions`, `ConversionService`, `MethodIntrospector`, `MethodParameter`, `Ordered`, `ExceptionDepthComparator`, `AnnotatedTypeMetadata`. |
| `org.springframework.http`         | 11 | Mostly stable. `HttpHeaders`, `HttpMethod`, `HttpStatusCode`, `MediaType`, `ResponseEntity`, `PathContainer`, `HttpMessageConverter`. Anticipated friction: `MappingJackson2HttpMessageConverter` package or replacement in Spring Framework 7. |
| `org.springframework.lang`         | 2  | Stable (`@NonNull`, `@Nullable`).                                                                                              |
| `org.springframework.security`     | 7  | Spring Security has its own version cycle. SB4 ships with Spring Security 7; consumed surface here is the `SecurityContextHolder` family on the compat path. Treated as a separate axis (Security is not part of this ADR's nominal compatibility claim — it remains a per-customer dependency choice). |
| `org.springframework.stereotype`   | 1  | Stable.                                                                                                                        |
| `org.springframework.transaction`  | 8  | Stable. `PlatformTransactionManager`, `TransactionDefinition`, `TransactionStatus`, propagation/isolation enums.               |
| `org.springframework.util`         | 1  | Stable.                                                                                                                        |
| `org.springframework.validation`   | 3  | Stable.                                                                                                                        |
| `org.springframework.web`          | 25 | Model-only per `CLAUDE.md` §"Module layout" (`web` module is the only consumer; `spring-webmvc` is banned). `@RestController`, `@RequestMapping`, `ServerHttpRequest`, `ServerHttpResponse`, `HandlerMethod`, `RequestMappingInfo`. Anticipated friction: `WebMvc`-adjacent helper relocations. |

The consumed surface is concentrated in the **framework-stable core** (DI, configuration, lifecycle, model types) and avoids the layers where Spring Boot 4 made its largest changes (`spring-boot-starter-web` defaults, embedded servlet container, AOT/native, observability defaults) — those layers are already excluded by the Pure Mode classpath guards (`PureModeClasspathGuardTest`) and ADR-021 (gateway workloads).

This ADR answers: **does 1.0 commit to supporting Spring Boot 4 as a nominal version alongside Spring Boot 3.5+, and on what enforcement?**

## 🏁 The Decision

**The 1.0 release supports Spring Boot 3.5+ and Spring Boot 4.x as nominal versions. From 0.7.0-preview onward, the reactor builds and tests under a dual matrix (SB3 and SB4); CI matrix failure on either line blocks merge.**

The 1.0 support statement reads, in normalized form: *"`exeris-spring-runtime` 1.0 is supported on Spring Boot 3.5.x and Spring Boot 4.x. JVM baseline is JDK 26 with `--enable-preview`. Servlet containers (Tomcat/Jetty/Undertow), Netty, and Reactor remain out of scope on Pure Mode classpath under both matrices."*

**Concrete obligations:**

1. **Dual Maven profile in the reactor.** The root POM defines `<profile>matrix-sb3</profile>` (default) and `<profile>matrix-sb4</profile>`. Each profile imports a distinct `spring-boot-dependencies` BOM version. The reactor source tree is unchanged across profiles; only the dependency-management import differs. No source-level fork (no `src/sb3`, no `src/sb4`).
2. **CI runs both matrices on every push.** `.github/workflows/ci.yml` adds a matrix axis `spring-boot-line: [sb3, sb4]`. Both axes run the full reactor including integration tests; failure on either blocks merge. The current single-line `mvn -s .github/maven-settings.xml clean install` becomes `mvn -P matrix-${{ matrix.spring-boot-line }} -s .github/maven-settings.xml clean install`.
3. **Pure Mode classpath guards stay green on both matrices.** `PureModeClasspathGuardTest` (per-module), `WallIntegrityTest`, and `CompatibilityIsolationGuardTest` must pass under both `matrix-sb3` and `matrix-sb4`. Spring Boot 4 must not be allowed to re-introduce servlet/Netty/Reactor edges that SB3 forbids. If SB4 transitively pulls a banned coordinate that SB3 does not, the resolution is an explicit `<exclusion>` in the reactor's `dependencyManagement` for that matrix — not weakening the guard.
4. **Compat shims are scoped, named, and bounded.** Where SB4 package reorganization affects a type we consume (anticipated: `spring-boot-actuate-autoconfigure` health/info package layout; possibly `MappingJackson2HttpMessageConverter` replacement in Spring Framework 7), the response is a narrow shim in the affected module under a `compat.sb4.*` sub-package (mirroring the existing `compat.*` discipline from ADR-011). Shims carry an `@SbCompat` marker (or equivalent comment) naming the SB4 type they bridge, and are removed when the SB3 line is dropped post-1.0.x.
5. **Out-of-scope dependencies remain out of scope under both matrices.** `spring-cloud-starter-gateway*` (per ADR-021), embedded servlet containers (per Phase 0/1 classpath guards), `spring-webflux` reactive stack. The dual-matrix claim is **not** a "all of Spring works on Exeris" promise — it is precisely scoped to what `exeris-spring-runtime` consumes from the framework (DI, configuration binding, `spring-context` lifecycle, `spring-web` model types, `spring-tx` `PlatformTransactionManager`, `spring-boot-actuator` health/info/metrics, `spring-boot-autoconfigure` plumbing).
6. **Spring Security is a separate axis.** SB4 ships with Spring Security 7; SB3 currently aligns with Spring Security 6. The consumed surface here is the `SecurityContextHolder` family on the Phase 2c compat filter. Validation of Security 7 against the existing compat filter is a follow-up; this ADR does not promise nominal Security 7 support at 1.0 if a meaningful compat gap surfaces. The Security version pin is decoupled from the Spring Boot pin.

## Matrix at a glance

| Matrix profile | Spring Boot BOM | Spring Framework | Spring Security | Jakarta EE | JDK baseline | Pure Mode classpath guards |
|:---------------|:----------------|:-----------------|:----------------|:-----------|:-------------|:---------------------------|
| `matrix-sb3` (default) | 3.5.x | 6.1.x | 6.x | 10.x | 26 + preview | Must stay green |
| `matrix-sb4`           | 4.0.x | 7.x   | 7.x (separate axis — see obligation 6) | 11.x | 26 + preview | Must stay green |

Banned coordinates (both matrices, no exceptions): `org.apache.tomcat.embed:*`, `org.eclipse.jetty:*`, `io.undertow:*`, `io.netty:*`, `io.projectreactor:*`, `jakarta.servlet:jakarta.servlet-api`, `com.zaxxer:HikariCP`, `spring-cloud-starter-gateway*`, `spring-webflux`.

## Consequences

### ✅ Positive Outcomes

- **[+] Commercial migration path stays open at 1.0.** Brownfield customers on SB4 codebases can adopt the runtime at 1.0 rather than waiting for 1.1.x. The primary commercial consumer (per `CLAUDE.md`) is supported on its actual baseline.
- **[+] Support statement is honest by construction.** The dual-matrix CI invariant means "SB4 is supported" is not a forward-looking claim — it is a present-tense CI fact every PR demonstrates. No future regression can silently break SB4 support without a red build.
- **[+] The cost matches the consumed surface.** Because the repository consumes the framework-stable core (DI, configuration, lifecycle, model types) and excludes the volatile layers (embedded transport, reactive stack), the bridging cost is bounded. Compat shims, if needed, are narrow and named.
- **[+] Pure Mode discipline transfers cleanly.** The classpath guards that keep SB3 honest are the same guards that keep SB4 honest. The Wall doesn't move; only the import scopes change.
- **[+] Phase 5 (`exeris-spring-runtime-gateway`, 0.6.0-preview) is the cleanest validation surface.** A greenfield module added immediately before this ADR's enforcement period is the lowest-risk place to validate dual-matrix behaviour — no SB3-only legacy in that module to retrofit.

### ⚠️ Trade-offs

- **[-] CI time roughly doubles for the matrix axes that run both.** The full reactor + integration tests run twice per push. Mitigation: only the reactor build and the runtime-integration tests need to run on both axes; unit tests scoped to non-Spring code (kernel SPI types, codec internals) can be filtered if CI cost becomes meaningful. Default: run both axes in full to keep the matrix invariant load-bearing.
- **[-] Compat shims add a small surface that the SB3 path doesn't need.** The shims live in `compat.sb4.*` sub-packages, follow the same discipline as `compat.*` from ADR-011 (clearly marked, isolated from Pure Mode imports), and have an explicit removal date (when the SB3 matrix is dropped post-1.0.x). The cost is bounded by the count of types that actually relocate, which (per the consumed-surface analysis above) is small.
- **[-] Spring Security version coupling is decoupled and remains a per-customer concern.** A customer on SB4 + Security 7 may encounter compat issues on the Phase 2c filter that this ADR explicitly does not guarantee. Mitigation: an additional ADR scopes Security compat if/when a meaningful gap surfaces; until then, the support statement names Security as a separate axis.
- **[-] The 1.0 timeline absorbs an additional preview train.** Inserting 0.7.0-preview between 0.6.0 (Phase 5) and 0.9.0-rc1 delays rc1 by one train. The roadmap absorbs this explicitly rather than collapsing SB4 into 0.6.0 — the cross-cutting nature of dual-matrix validation is better served by a dedicated train with a single graduation criterion than by mixing with Phase 5 module-skeleton work.

### 📋 What is NOT in scope

- **Spring Boot 3.4 and earlier support.** The 1.0 floor is Spring Boot 3.5 — older lines reach end-of-OSS-support inside the 1.0 window and supporting them would dilute the matrix.
- **Embedded servlet container support on SB4.** Tomcat/Jetty/Undertow remain banned under both matrices. SB4's default embedded Tomcat is excluded by the same `PureModeClasspathGuardTest` rules as SB3.
- **Spring WebFlux / reactive stack on SB4.** Out of scope under both matrices; ADR-010 ownership boundary applies identically.
- **Spring Cloud Gateway on SB4.** Out of scope per ADR-021 under both matrices.
- **Spring Security 7 nominal support.** Treated as a separate axis (obligation 6). A follow-up ADR scopes Security compat if a meaningful gap surfaces.
- **Native image / AOT compilation.** SB4 emphasizes AOT and native; this ADR does not promise that the runtime ships AOT hints for SB4 native image. AOT is a follow-up axis if/when downstream demand materializes.
- **Application-side migration tooling.** This ADR governs `exeris-spring-runtime` compatibility with both Spring Boot lines. It does not produce migration tooling for downstream Spring applications themselves — that is the application owner's concern.

## Cross-references

- ADR-006 — Spring-Free Kernel Boundary (The Wall): `exeris-docs/adr/ADR-006-spring-free-kernel-boundary.md` — the parent invariant that survives the dual-matrix expansion unchanged
- ADR-010 — Host Runtime Model: `docs/adr/ADR-010-host-runtime-model.md` — the ownership boundary that scopes what "supported Spring Boot version" means in this repository
- ADR-011 — Pure Mode vs Compatibility Mode: `docs/adr/ADR-011-pure-mode-vs-compatibility-mode.md` — the mode taxonomy that compat shims under this ADR must respect
- ADR-017 — JDBC Compatibility Scope for `ExerisDataSource`: `docs/adr/ADR-017-jdbc-compact-scope.md` — adjacent compatibility-scope ADR (different surface, same discipline)
- ADR-021 — Gateway-Class Workloads Out of Compatibility Scope: `docs/adr/ADR-021-gateway-class-workloads-out-of-compatibility-scope.md` — banned coordinates that survive under both matrices
- ADR-026 — Spring `ApplicationEventPublisher` / Exeris `EventBus` separation: `docs/adr/ADR-026-eventbus-applicationeventpublisher-boundary.md` — invariant that survives the SB4 line unchanged
- `docs/roadmap-1.0-trl9.md` — 0.7.0-preview train row anchors this ADR in the release plan

## Engineering Protocol

The ADR is forward-looking — implementation work falls in the 0.7.0-preview train. Three concrete deliverables (each can be a separate PR; they do not need to land in one):

1. **Reactor profile addition.** Root POM `<profiles>` block adds `matrix-sb3` (default, current behaviour) and `matrix-sb4` (imports `spring-boot-dependencies` 4.0.x). The `spring.boot.version` property remains, but is the SB3 line; an `spring.boot.sb4.version` property pins the SB4 line. No source changes required for this PR — it only proves the reactor builds under both BOMs with the existing source.
2. **CI matrix axis.** `.github/workflows/ci.yml` adds the `spring-boot-line: [sb3, sb4]` matrix axis to the build job. Both axes run `mvn -P matrix-${{ matrix.spring-boot-line }} -s .github/maven-settings.xml clean install`. The job becomes blocking on both axes. If SB4 build fails on the first run, an additional PR (or PRs) introduces compat shims in `compat.sb4.*` sub-packages of the affected modules — never weakens the Pure Mode classpath guards.
3. **Compat shim discipline.** Any `compat.sb4.*` sub-package introduced under this ADR mirrors the existing `compat.*` discipline from ADR-011 — `@SbCompat` marker (or equivalent annotation/comment), explicit isolation from Pure Mode imports, named removal point (when the SB3 matrix is dropped post-1.0.x). Each compat shim PR cites this ADR in its description.

A future PR that proposes adding a coordinate banned by obligation 5 on either matrix is an ADR-027-violating PR; the reviewer cites this ADR by number when blocking. A future PR that removes the SB4 matrix axis is an ADR-027-violating PR until and unless this ADR is superseded.
