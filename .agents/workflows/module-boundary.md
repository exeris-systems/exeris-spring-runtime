---
name: module-boundary
description: Enforce module-responsibility boundaries — banned dependency edges across autoconfigure, web, tx, data, actuator, graph; banned classpath dependencies in pure mode; thin autoconfigure.
argument-hint: PR diff or module-boundary / pom change to audit
steps:
  - {skill: exeris-spring-module-boundary-review}
  - {agent: exeris-spring-runtime-architect}
gates:
  - hook:guardrails-gate-on-stop
---

Audit this change against module-responsibility boundaries.

Module rules:
- `autoconfigure`: Thin configuration and property binding. No transport, request processing, tx, or persistence logic. Classes >100 lines of logic are a smell.
- `web`: `HttpHandler` bridging Exeris `HttpExchange` to Spring handler beans. Pure mode: no servlet, no reactive, no body copy. Depends on `spring-web` model only, never `spring-webmvc`.
- `tx`: `PlatformTransactionManager` over `PersistenceConnection`. No `ThreadLocal` context carrier (use `ScopedValue`). No `DataSource`/HikariCP ownership.
- `data`: Optional persistence bridge under high scrutiny. Public classes need ADR comments. No HikariCP, no JPA/Hibernate as first class.
- `actuator`: Observability only. Read-only. Never owns a data-plane path.
- `graph`: Spring-side seam for kernel `GraphEngine` (Phase 4C, ADR-030). Default-off. No `org.springframework.data..`. Concrete drivers test-scoped only.

Banned dependency edges: `autoconfigure → web/tx/data`, `web → data`, `data → web`, `tx → web`, `actuator → web (data-plane)`.

Banned from runtime classpath in pure mode: `org.apache.tomcat.embed:*`, `org.eclipse.jetty:*`, `io.undertow:*`, `io.netty:*`, `io.projectreactor:*`, `jakarta.servlet:jakarta.servlet-api`, `com.zaxxer:HikariCP`.

Change:
$ARGUMENTS

Please review:
1. Does the change add a dependency edge in the banned set?
2. Does `autoconfigure` grow beyond thin wiring? Any class >100 lines of logic?
3. In `web` pure mode: any servlet or reactive imports, or body copies?
4. In `tx`: any `ThreadLocal` as tx context carrier? Any `DataSource`/HikariCP ownership?
5. In `data`: HikariCP/JPA/Hibernate as first-class path?
6. In `actuator`: any data-plane responsibility?
7. In `graph`: any banned Spring Data Neo4j imports or concrete drivers in main scope?
8. Any banned classpath dependencies added in pure mode?
9. Minimal correction if module boundaries are violated.
