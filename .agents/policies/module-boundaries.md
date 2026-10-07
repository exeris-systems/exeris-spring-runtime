# Policy: Module Responsibility Boundaries

The repository is modularized into distinct concerns. Module boundaries and dependency directions are strict.

## The Rule

- **`exeris-spring-boot-autoconfigure`**:
  - Thin configuration, property binding, `SmartLifecycle` management, and conditional bean registration.
  - Must NOT contain transport handlers, request processing logic, transaction management, or persistence code. Classes with >100 lines of business logic indicate boundary drift.
- **`exeris-spring-runtime-web`**:
  - Implements `HttpHandler` bridging Exeris `HttpExchange` to Spring route handler beans.
  - In pure mode: depends on `spring-web` model only; never `spring-webmvc`. No servlet or reactive dependencies.
- **`exeris-spring-runtime-tx`**:
  - Implements `PlatformTransactionManager` over `PersistenceConnection`.
  - Uses `ScopedValue` for transaction context carrier. No `DataSource` / HikariCP ownership.
- **`exeris-spring-runtime-data`**:
  - Optional persistence bridge under high scrutiny.
  - Public classes require ADR / Phase 3 reference comments. No JPA/Hibernate as a first-class path.
- **`exeris-spring-runtime-actuator`**:
  - Observability, Micrometer bridge over `TelemetrySink`. Read-only. Never owns request data-plane paths.
- **`exeris-spring-runtime-graph`**:
  - Spring seam for kernel `GraphEngine` (Phase 4C, ADR-030). Default-off via `exeris.runtime.graph.enabled`.
  - `org.springframework.data..` is banned. No fluent DSL builder. Concrete drivers are test-scoped only.
- **Banned Dependency Edges**:
  - `autoconfigure → web/tx/data`
  - `web → data`
  - `data → web`
  - `tx → web`
  - `actuator → web` (on data-plane)
- **Banned Runtime Classpath in Pure Mode**:
  - `org.apache.tomcat.embed:*`, `org.eclipse.jetty:*`, `io.undertow:*`, `io.netty:*`, `io.projectreactor:*`, `jakarta.servlet:jakarta.servlet-api`, `com.zaxxer:HikariCP`.
- **Enforcement**: Guarded by `ModuleBoundaryTest` and `PureModeClasspathGuardTest`.
