# Phase 3: Transactions, Context Propagation, and Persistence Integration

**Status:** Not Started  
**Depends on:** Phase 1 complete. Phase 2 optionally complete.  
**Milestone:** M3  

---

## Goal

Extend the Exeris host-runtime model into deeper application concerns:

1. **Transaction boundary bridge** — `@Transactional` semantics backed by Exeris `PersistenceConnection`
2. **Context propagation** — security context, request scope, observability context integrated with `ScopedValue`
3. **Persistence integration** — native Exeris repository model; optional JDBC compatibility bridge

This phase has the highest architectural risk. Every design choice here must be evaluated against
the "JDBC gravity well" anti-pattern: designs that collapse Exeris back into being a secondary
component running inside a conventional `DataSource` → `ConnectionPool` → `JPA` runtime will be rejected.

---

## 3A: Transaction Bridge

### Goal

`@Transactional` on Spring beans works, backed by Exeris `PersistenceConnection` lifecycle,
without HikariCP or `DataSource.getConnection()` as the canonical entry point.

### Architecture

**`ExerisPlatformTransactionManager implements PlatformTransactionManager`**  
**Module:** `exeris-spring-runtime-tx`

Maps Spring transaction lifecycle to Exeris `PersistenceConnection` lifecycle:

| Spring | Exeris |
|:-------|:-------|
| `getTransaction(definition)` | `PersistenceEngine.openConnection()` |
| `commit(status)` | `PersistenceConnection.commit()` |
| `rollback(status)` | `PersistenceConnection.rollback()` |
| `suspend(status)` | `PersistenceConnection.suspend()` (if supported) |
| `resume(transaction, status)` | `PersistenceConnection.resume()` |

The active `PersistenceConnection` is bound to the current virtual thread scope
via `ScopedValue` (NOT via `TransactionSynchronizationManager.bindResource()`).

### ScopedValue vs ThreadLocal challenge

Spring's `@Transactional` uses `TransactionSynchronizationManager` which is `ThreadLocal`-backed.
Virtual threads can safely use `ThreadLocal` (it is not banned for VTs, only for carrier threads
when combined with `synchronized` → pinning). However, `ScopedValue` is the Exeris-preferred model.

Decision: `ExerisPlatformTransactionManager` will use a hybrid approach:
- Exeris `PersistenceConnection` is stored in `ScopedValue`.
- Spring `TransactionSynchronizationManager` is also updated (ThreadLocal on VT) to satisfy
  Spring's internal machinery for `@Transactional` AOP proxy behavior.
- This dual-binding is isolated in `ExerisTransactionSynchronizationBridge`.
- ThreadLocal binding is scoped strictly to the `handle()` execution scope (cleared on exit).

### Classes to Implement

**`ExerisTransactionObject`** — carries the `PersistenceConnection` for a single transaction.

**`ExerisPlatformTransactionManager`** — the primary `PlatformTransactionManager` bean.

**`ExerisTransactionSynchronizationBridge`** — binds/releases Spring synchronization manager
alongside Exeris-owned connection.

**`ExerisTransactionAutoConfiguration`** — wires the above; conditional on
`@Transactional` class being present and Exeris persistence provider available.

### Risks

| Risk | Mitigation |
|:-----|:-----------|
| `Kernel PersistenceEngine` not yet public API in 0.5.0-SNAPSHOT | Track kernel ADR-010 progress; create forward-looking adapter layer |
| Nested transaction semantics mismatch | Support only REQUIRED and REQUIRES_NEW initially; document others as unsupported |
| VT `ThreadLocal` interaction with Spring AOP proxy creation | Test thoroughly with `@Transactional` on proxied beans; AOP proxy creation is off-VT anyway |
| Suspension/resume in Exeris kernel not implemented | Treat as PROPAGATION_REQUIRED-only in Phase 3; document REQUIRES_NEW limitations |

---

## 3B: Context Propagation

### Request Scope

Spring `@RequestScope` is backed by `ThreadLocal` in standard Spring Web.
For Exeris pure mode, request scope must be backed by a `ScopedValue`-bound map per request.

**`ExerisRequestScopeBean`** — mounts a `HashMap` into a `ScopedValue` slot at the start of
each request dispatch (in `ExerisHttpDispatcher`). Spring request-scoped beans read/write
to this map via `ExerisRequestScopeBeanFactory`.

This replaces the `RequestContextHolder.ThreadLocal` approach with a VT-safe model.

### Security Context

By default, Spring Security's `SecurityContextHolder` uses `ThreadLocal`.

Integration approach:
1. Store `SecurityContext` in `ScopedValue` (Exeris-native).
2. In compatibility mode, bind to `SecurityContextHolder.ThreadLocal` for the duration
   of handler execution (via `ExerisThreadLocalBridge` from Phase 2).
3. In pure mode, provide `ScopedValue`-based `ExerisSecurityContextAccessor`.

### Observability Context (Tracing)

If Spring Boot's Micrometer Tracing is on the classpath:
1. Read active `Span` from Micrometer `Observation` context.
2. Bridge to Exeris `KernelEvent` tags where feasible.
3. Propagate trace headers via `ExerisServerRequest` access.

`ThreadLocal`-based MDC propagation must be isolated to compatibility mode within
the `ExerisThreadLocalBridge` lifecycle.

---

## 3C: Persistence Integration

### Two Levels

**Level 1 — Exeris Native Repositories (recommended)**

Application code implements repository interfaces backed by Exeris `PersistenceEngine`
and `QueryExecutor` directly. No `DataSource`. No `JdbcTemplate`. No Hibernate.

Spring DI provides the bean lifecycle. Exeris provides execution semantics.

```java
@Component
public class UserRepository {

    private final PersistenceEngine engine; // injected from KernelProviders ScopedValue

    public Optional<User> findById(long id) {
        return engine.execute(
            "SELECT id, name FROM users WHERE id = ?", id,
            result -> result.hasNext() ? Optional.of(mapUser(result.next())) : Optional.empty()
        );
    }
}
```

This path has:
- Zero connection pool indirection (`DataSource` not involved)
- Exeris-managed connection lifecycle
- `PersistenceConnection` bound to transaction scope via `ScopedValue`

**Level 2 — JDBC Compatibility Bridge (requires ADR approval)**

If an application uses a framework that cannot be adapted to Level 1 (e.g., Spring Data JPA),
a `DataSource` adapter backed by Exeris `ConnectionFactory` can be provided.

**`ExerisDataSource implements javax.sql.DataSource`**

- `getConnection()` calls `ConnectionFactory.open()` from the kernel
- Returns a `Connection` wrapper backed by the Exeris-managed connection
- Does not use HikariCP internally

Limitations:
- Every `getConnection()` call opens a real connection (not pooled at DataSource level)
- Connection pooling must be handled at the Exeris `PersistenceProvider` level
- Auto-commit behavior must be explicitly configured
- JPA/Hibernate compatibility is LIMITED — documented per-feature

This is the compatibility layer for persistence. It must be in:
`eu.exeris.spring.runtime.data.compat.ExerisDataSource`

And must NOT be the canonical recommendation.

### The JDBC Gravity Well Warning

Any design review for this module must answer:

1. Does this design require `DataSource.getConnection()` as the primary path for any non-JPA use case?
2. Does this design make HikariCP or ORM lifecycle the effective owner of connection management?
3. Does this design present Exeris as a secondary helper running inside a conventional JDBC stack?

If any answer is "yes", the design is rejected. Exeris must remain the owner of connection lifecycle.

### Decision Record Required

Before any class is added to `exeris-spring-runtime-data` beyond the placeholder POM,
a sub-ADR (ADR-002 or equivalent) must be accepted that:
- states whether Level 1 or Level 2 is the approach for that class
- documents the performance trade-off
- names the alternative designs considered
- references the kernel `ADR-010` persistence refactor for alignment

---

## Phase 3 Test Matrix

**Transaction bridge tests (`exeris-spring-runtime-tx`):**
- `@Transactional` method commits on success
- `@Transactional` method rolls back on exception
- `PROPAGATION_REQUIRED` joins existing transaction
- `PROPAGATION_REQUIRES_NEW` suspends existing and opens new
- Transaction-scoped resource is released on completion
- Concurrent requests each have independent transaction scopes (ScopedValue isolation)

**Context propagation tests:**
- Request-scoped beans are independent per request (not shared)
- Security context bound in compatibility mode is cleared after handler
- No `SecurityContextHolder` contamination across concurrent requests

**Persistence integration tests (Level 1):**
- Repository query executes on VT
- Result is correctly mapped
- Connection is closed after transaction ends
- Error during query rolls back and releases connection

**Persistence integration tests (Level 2, if activated):**
- `ExerisDataSource.getConnection()` returns valid wrapped JDBC connection
- Connection is backed by Exeris `PersistenceConnection`
- JDBC operations on the returned connection are forwarded correctly
- `close()` on the JDBC connection wrapper releases the Exeris connection

---

## Exit Criteria

Phase 3 is complete when:

1. `@Transactional` commit and rollback work on a Spring bean backed by Exeris persistence.
2. Request scope beans are VT-safe with no cross-request contamination.
3. A native Exeris repository executes a query and returns results.
4. If Level 2 is activated: `ExerisDataSource` provides correct JDBC semantics.
5. The Wall remains intact: no Spring types added to kernel SPI or Core.
6. No `ThreadLocal` leak across request boundaries (verified in concurrent integration tests).
7. Phase 1 and Phase 2 integration tests still pass.
8. Sub-ADR accepted for each class in `exeris-spring-runtime-data`.

---

## Kill Criteria for Phase 3

Phase 3 should be stopped or fundamentally redesigned if:

1. Achieving `@Transactional` support requires inserting Spring types into kernel Core.
2. The persistence path irreversibly collapses into HikariCP/JPA as the canonical owner.
3. `ThreadLocal` contamination cannot be reasonably isolated to the compatibility bridge scope.
4. Performance measurements show that the transaction overhead eliminates all Exeris benefits
   compared to a standard Spring Boot + HikariCP baseline.
