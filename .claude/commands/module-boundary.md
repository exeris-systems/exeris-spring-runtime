---
description: Enforce module-responsibility boundaries — banned dependency edges across `autoconfigure / web / tx / data / actuator / graph`; banned classpath deps in pure mode; thin autoconfigure.
argument-hint: PR diff or module-boundary / pom change to audit
---

Audit this change against module-responsibility boundaries.

Module table + banned edges (per repo `CLAUDE.md`):

| Module | Role | Hard rules |
|---|---|---|
| `-bom` / `-build-config` | Version + plugin config | No source. |
| `-autoconfigure` | Boot wiring, properties, `SmartLifecycle`, `@Conditional*` | **Thin.** No transport, no request processing, no tx, no persistence. Classes > 100 lines of logic = smell. |
| `-web` | `HttpHandler` impl bridging Exeris `HttpExchange` ↔ Spring handler beans | Pure mode: no `jakarta.servlet.*`, no `io.projectreactor.*`, no body copy from `LoanedBuffer` to `byte[]`. Depends on `spring-web` model only — never `spring-webmvc`. |
| `-tx` | `PlatformTransactionManager` over `PersistenceConnection` | No `ThreadLocal` as tx context carrier (use `ScopedValue`). No `DataSource` / HikariCP ownership. |
| `-data` | Optional persistence bridge (high scrutiny) | Each public class needs ADR/Phase-3 reference comment. No HikariCP, no JPA/Hibernate as first-class. |
| `-actuator` | Health, info, Micrometer over `TelemetrySink` | Read-only / observability. Never owns a data-plane path; never redefines `TelemetrySink`. |
| `-graph` | Spring-side seam for kernel `GraphEngine` / `GraphSession` (Phase 4C, ADR-030) | Default-off via `exeris.runtime.graph.enabled`. No Spring Data Neo4j (`org.springframework.data..` banned). No fluent DSL builder. No `GraphCursor` until kernel ships it. No cross-resource transactions. Concrete drivers test-scope only. |

**Banned dependency edges:** `autoconfigure → web/tx/data`, `web → data`, `data → web`, `tx → web`, `actuator → web (data-plane)`.

**Banned from runtime classpath in pure mode:** `org.apache.tomcat.embed:*`, `org.eclipse.jetty:*`, `io.undertow:*`, `io.netty:*`, `io.projectreactor:*`, `jakarta.servlet:jakarta.servlet-api`, `com.zaxxer:HikariCP`. Each module ships `PureModeClasspathGuardTest`.

Change:
$ARGUMENTS

Please review:
1. Does the change add a dependency edge in the banned set?
2. Does `-autoconfigure` grow beyond thin (transport / request processing / tx / persistence logic)? Any single class > 100 lines of logic?
3. In `-web` pure mode: any `jakarta.servlet.*`, `io.projectreactor.*` import, or body copy from `LoanedBuffer` to `byte[]` / `InputStream`?
4. In `-web`: any `spring-webmvc` dep (only `spring-web` model is permitted)?
5. In `-tx`: any `ThreadLocal` as tx context carrier? Any `DataSource` / HikariCP ownership?
6. In `-data`: HikariCP / JPA / Hibernate as first-class path? Public class without ADR / Phase-3 reference comment?
7. In `-actuator`: any data-plane responsibility? Any `TelemetrySink` redefinition?
8. In `-graph`: any `org.springframework.data..` import? Fluent DSL builder? `GraphCursor` use before kernel ships it? Cross-resource transactions? Non-test-scoped concrete driver dep?
9. Any banned classpath dep added in pure mode? `PureModeClasspathGuardTest` still green?
10. Minimal correction if module boundary is at risk.
