# Policy: Runtime Ownership

Spring is the application framework. Exeris is the runtime owner.

## The Rule

- **Spring owns**: Dependency injection (DI), configuration property binding, and user bean lifecycles.
- **Exeris owns**: Transport ingress, HTTP request lifecycle, backpressure, off-heap buffer management, provider discovery via Java `ServiceLoader`, and the telemetry hot path.
- **No Ownership Inversion**:
  - Ingress must never be delegated to servlet containers (Tomcat, Jetty, Undertow), Netty, or Project Reactor.
  - Exeris SPI discovery must never be replaced by Spring IoC lookups.
  - Persistence ownership belongs to Exeris `PersistenceEngine` / `ConnectionFactory` over `PersistenceConnection`, not JDBC-first or JPA-first pools.
- **Host-Runtime Model**: `exeris-spring-runtime` is an integration host layer that hosts Spring applications on top of the Exeris execution model. It is not a Spring Boot starter and must not be described as one.
