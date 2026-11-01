---
title: "ADR-109: Spring transaction propagation and the JDBC bridge's binding key, under a kernel that pins one connection per request"
type: adr
visibility: public
owning-repo: exeris-spring-runtime
status: active
slug: adr/ADR-109
---

# ADR-109: Spring transaction propagation and the JDBC bridge's binding key, under a kernel that pins one connection per request

| Attribute       | Value                                                                                                                                                                                                  |
|:----------------|:-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **Status**      | **ACCEPTED** (drafted and accepted 2026-11-01; single decider — no future gating event; ratified by the PR that introduces this file). **Amends [ADR-017](ADR-017-jdbc-compact-scope.md).**          |
| **Deciders**    | Arkadiusz Przychocki                                                                                                                                                                                   |
| **Date**        | 2026-11-01                                                                                                                                                                                             |
| **Scope**       | spring/tx (`exeris-spring-runtime-tx` `ExerisPlatformTransactionManager`; `exeris-spring-runtime-data` `ExerisDataSource` binding key; no kernel SPI change)                                          |
| **Owning Repo** | `exeris-spring-runtime`                                                                                                                                                                                |
| **Driven By**   | `docs/phases/phase-3-invariants.md` #4 — *"Any change to the propagation matrix requires an ADR"* — and ADR-017 §5, whose connection-reuse contract this changes                                       |
| **Compliance**  | [ADR-010](ADR-010-host-runtime-model.md), [ADR-011](ADR-011-pure-mode-vs-compatibility-mode.md), [ADR-017](ADR-017-jdbc-compact-scope.md), [Phase 3 invariants](../phases/phase-3-invariants.md)   |

## Context and Problem Statement

`ExerisPlatformTransactionManager` is the Compatibility Mode bridge that lets `@Transactional` demarcate
transactions on a kernel `PersistenceConnection`. Up to 0.7.0 it did not recognise a transaction that
was already active on the thread, so every `getTransaction` call began a new one. `REQUIRED` and
`REQUIRES_NEW` inside a transaction failed, `MANDATORY` always threw, and `SUPPORTS`, `NEVER` and
`NOT_SUPPORTED` ran inside the outer transaction. The matrix the Phase 3 invariants called fixed was
the one Spring documents. The manager did something else.

Making the manager detect an existing transaction is ordinary Spring work. What is not ordinary is the
kernel underneath it. On a kernel request route the kernel binds a per-request `PersistenceSessionBox`,
and `PersistenceEngine.openConnection()` returns the request's one connection every time it is called.
Spring's suspension model assumes the engine can hand the thread a second connection. On a request
route it cannot. Off the request route every `openConnection()` takes its own pooled connection and
the assumption holds.

The JDBC bridge had a second, independent defect. `ExerisDataSource` bound its transaction proxy under
the data-source key, which is where Spring's `DataSourceUtils` (and so `JdbcTemplate`) keeps its own
`ConnectionHolder`. `JdbcTemplate` inside an Exeris transaction therefore threw `ClassCastException`.

This ADR answers one question: what each Spring propagation does under `ExerisPlatformTransactionManager`
on and off a kernel request route, and how the JDBC bridge binds the transaction's connection so that
Spring's JDBC support takes part in that transaction.

## 🏁 The Decision

**`REQUIRED`, `SUPPORTS` and `MANDATORY` join an active Exeris transaction. `NEVER` refuses one.
`REQUIRES_NEW` and `NOT_SUPPORTED` suspend and resume off the request route and are refused early on
it. `NESTED` is refused everywhere. `ExerisDataSource` binds the transaction's connection under a key
private to the instance.**

**Concrete obligations:**

1. **The propagation matrix is the one below.** It applies inside an active transaction. Outside one,
   `REQUIRED` and `REQUIRES_NEW` begin a transaction, `SUPPORTS`, `NOT_SUPPORTED` and `NEVER` run
   without one, `MANDATORY` throws `IllegalTransactionStateException`, and `NESTED` throws
   `UnsupportedOperationException`.

   | Propagation     | Off the request route                                                  | On a kernel request route                                   |
   |:----------------|:-----------------------------------------------------------------------|:------------------------------------------------------------|
   | `REQUIRED`      | Joins                                                                  | Joins                                                       |
   | `SUPPORTS`      | Joins                                                                  | Joins                                                       |
   | `MANDATORY`     | Joins                                                                  | Joins                                                       |
   | `NEVER`         | `IllegalTransactionStateException`                                     | `IllegalTransactionStateException`                          |
   | `REQUIRES_NEW`  | Suspends the outer, runs on a second connection, resumes the outer     | `IllegalTransactionStateException` before the inner work runs |
   | `NOT_SUPPORTED` | Suspends the outer, runs without a transaction, resumes the outer      | `IllegalTransactionStateException` before the inner work runs |
   | `NESTED`        | `UnsupportedOperationException`                                        | `UnsupportedOperationException`                             |

   A joined participant that rolls back marks the transaction rollback-only, and the outermost commit
   then fails with `UnexpectedRollbackException`. `NESTED` is refused because the kernel
   `PersistenceConnection` contract has no savepoints. The manager never uses JDBC savepoints in its
   place.

2. **On a kernel request route, suspension is refused before any inner work runs.** The kernel holds
   one connection for the whole request, so there is no connection to suspend onto. The manager
   detects this at suspend time and throws `IllegalTransactionStateException`, and the outer transaction
   stays active and usable. The alternative is to let the inner work run inside the outer transaction
   under a propagation that promises it will not. Work that must commit independently of the request's
   transaction runs off the request path.

3. **The manager detects a request route by probing for a connection, and the probe has a cost.** Every
   suspension opens one connection from the engine, checks whether it is already in a transaction, and
   closes it. On a request route the probe gets the request's own connection, which is in the suspended
   transaction, and costs nothing. Off the request route the probe borrows and returns one pooled
   connection. A suspension therefore needs a free pool slot besides the one the suspended transaction
   holds. That includes `NOT_SUPPORTED`, which otherwise needs no connection. With no slot free, the
   suspension waits for the pool's connection timeout (`exeris.runtime.persistence.connection-timeout-ms`)
   and fails with `CannotCreateTransactionException`, and the outer transaction stays usable. The probe
   stays until the kernel exposes an SPI signal that names the request-scoped connection, requested in
   exeris-kernel#822. When that signal is available, it replaces the probe, under the amendment rule in
   Engineering Protocol 1.

4. **A completion callback cannot join the transaction that has just completed.** The transaction stays
   bound to the thread after commit or rollback, until cleanup, so `afterCommit` and `afterCompletion`
   callbacks (and `@TransactionalEventListener` phases) still see it. From such a callback,
   `REQUIRES_NEW` gets a new transaction. Off the request route it runs on a second connection. On a
   request route it runs on the request's connection, which the completed transaction no longer uses.
   `REQUIRED`, `SUPPORTS` and `MANDATORY` fail with `IllegalTransactionStateException`.

   **This is stricter than Spring's default, on purpose.** Spring lets data access in `afterCommit` "still
   participate" in the original transaction and tells callers to use `REQUIRES_NEW`
   (`TransactionSynchronization#afterCommit` Javadoc). Under this manager that participation has nothing
   behind it. Commit has already ended the transaction. Off the request route its connection has gone
   back to the pool. On a request route the connection stays open after its transaction ends, so a
   joined write runs on the request connection outside any transaction. The caller cannot commit it or
   roll it back, and the participant reports that it joined. Refusing makes the `REQUIRES_NEW`
   instruction enforceable rather than advisory.

5. **`ExerisDataSource` binds the transaction's connection proxy under a key private to the instance.**
   The data-source key belongs to Spring's JDBC support. `DataSourceUtils` finds nothing there on first
   use, calls `getConnection()`, receives the transaction's proxy, and holds it in its own
   `ConnectionHolder` for the rest of the transaction. That way `JdbcTemplate` takes part in the Exeris
   transaction, and rolls back and suspends with it. The proxy is unbound on suspend and on completion
   and bound again on resume. **A second `ExerisDataSource` that the application builds itself does not
   share the transaction's connection.** The transaction manager's callback is wired to the one bean that
   `ExerisDataAutoConfiguration` creates, and no other instance can read that instance's key.

6. **`JdbcTransactionManager` over `ExerisDataSource` is not a supported pairing.** Since obligation 5,
   `getConnection()` never reads a `ConnectionHolder` that another manager bound under the data-source
   key, so it no longer throws `ClassCastException`. The pairing still lets Spring demarcate raw JDBC
   transactions on an Exeris-owned connection, bypassing `PersistenceConnection.beginTransaction` and
   `commit`. That is the split ownership ADR-017 rejects (ADR-017 §2 item 5, §4.4). The regression test
   that exercises it is a guard against the cast, not a support claim. The supported manager is
   `ExerisPlatformTransactionManager` (`exeris.runtime.tx.enabled`).

## Alternatives Considered

- **Read `PersistenceSessionBox` from the tx module to detect a request route.** This would answer the
  question exactly and need no probe connection. Rejected: `PersistenceSessionBox` lives in
  `eu.exeris.kernel.community.persistence`, and `exeris-spring-runtime-tx` depends on
  `exeris-kernel-community` at test scope only. Reading the box would add a production tx→community edge
  and tie the bridge to one engine's concrete request mechanism, which defeats the swappable-engine
  contract of the kernel SPI. The probe costs a pooled connection per suspension off the request route.
  That cost is accepted until exeris-kernel#822 gives the SPI an engine-neutral way to answer the
  question.
- **Keep binding under a shared key.** There are two variants. The data-source instance itself was the
  0.7.0 behaviour. It collides with `DataSourceUtils`'s `ConnectionHolder`, and that collision is the
  `ClassCastException`. `ExerisDataSource.class` is the key ADR-017's §4.1 and §5 text names. It avoids
  that collision, but any code can reach it, and every instance shares it, so two data sources wired to
  two managers would collide on `bindResource`. Rejected: the instance-private key cannot collide with
  anything. Its cost is obligation 5's last sentence.
- **Let `REQUIRES_NEW`/`NOT_SUPPORTED` run on a request route without suspending.** Rejected: the inner
  work would commit or roll back with the outer transaction while its propagation promises otherwise.
  That is the silent behaviour this ADR exists to remove.

## Consequences

### ✅ Positive Outcomes

- **[+] Spring's documented propagation semantics hold wherever the kernel can support them.** Where
  it cannot, the call fails before any work runs and does not do something else silently.
- **[+] `JdbcTemplate` and other `DataSourceUtils` users work inside an Exeris transaction** without a
  second transaction manager.
- **[+] Every refusal leaves the outer transaction usable**, so a caller can catch it and continue.

### ⚠️ Trade-offs

- **[-] `REQUIRES_NEW` and `NOT_SUPPORTED` inside a transaction are unavailable on a kernel request
  route.** An application that uses them in a request handler must move that work off the request path.
- **[-] Off the request route, every suspension costs one pooled connection borrow and return.**
  `NOT_SUPPORTED` needs a free slot it would not otherwise need, and pool exhaustion turns into a
  connection-timeout wait followed by `CannotCreateTransactionException`.
- **[-] Completion callbacks that relied on Spring's default participation now fail** and must declare
  `REQUIRES_NEW`.
- **[-] A user-built second `ExerisDataSource` runs outside the transaction**, on its own connection.

### 📋 What is NOT in scope

- Automatic retry on serialization failures (`40001` / `40P01`). The manager still does not retry.
  `TransactionalExecutor` remains the path for that.
- The kernel SPI signal itself. It is requested in exeris-kernel#822 and owned by the kernel.
- JPA/Hibernate connection handling beyond the binding key. ADR-017 §6 is unchanged.

### 🚫 Non-Goals

- **Savepoint-based `NESTED`.** The kernel connection contract exposes no savepoints. Emulating them
  through the raw JDBC connection would put transaction state outside `PersistenceConnection`.
- **A second connection on a request route.** The kernel's one-connection-per-request model is the
  kernel's decision. This bridge does not work around it.

### ⚠️ Risks and Assumptions

- **Assumes:** a freshly opened connection that is already in a transaction is the suspended
  transaction's own connection handed back, which is true only on a kernel request route. An engine
  that hands out a different, already-in-transaction connection would make the manager refuse a
  suspension it could have performed. The refusal is safe, but the behaviour would be wrong.
- **Assumes:** the kernel keeps rejecting pools smaller than two connections, so off the request route
  a free slot can exist beside the suspended transaction's.
- **Risk:** a deployment with a pool sized exactly to its concurrency sees suspensions wait for the
  connection timeout under load. It shows up as `CannotCreateTransactionException` naming the
  suspension, and the operator notices first.
- **Reversed by:** a kernel SPI signal (exeris-kernel#822) that identifies the request-scoped
  connection, which replaces the probe in obligation 3. Or a kernel change that serves a request
  route with more than one connection, which would make `REQUIRES_NEW` and `NOT_SUPPORTED` possible
  there and reverse obligation 2. Or savepoints on the `PersistenceConnection` contract, which would
  reopen `NESTED`. Each one is a change to the matrix and so needs an ADR (Engineering Protocol 1).

## Cross-references

- [ADR-017](ADR-017-jdbc-compact-scope.md) — the JDBC bridge. Its binding key (§4.1, §5) and its
  description of how the raw JDBC connection is obtained (§3 Option B, §4.1) are amended by this ADR.
- [ADR-010](ADR-010-host-runtime-model.md) — Exeris owns the persistence connection lifecycle.
- `docs/phases/phase-3-invariants.md` #4 — the matrix as an invariant, with its guards.
- `docs/phases/phase-3-tx-persistence.md` — Phase 3 plan and risk register.
- exeris-kernel#822 — request for an SPI signal naming the request-scoped connection.

## Engineering Protocol

1. **Any change to the propagation matrix requires an ADR**, whether it amends this one or supersedes
   it. That includes the request-route refusal, the completion-callback rule, and replacing the probe.
   `docs/phases/phase-3-invariants.md` #4 states the same rule at the invariant.
2. `ExerisTransactionPropagationIntegrationTest` covers every row of the matrix against a live kernel
   engine, both off the request path and on a thread bound to a `PersistenceSessionBox` the way the
   kernel dispatcher binds it. It also covers the completion-callback cases, the exhausted-pool
   suspension, and the begin-time binding conflict. It asserts that no transaction resource or
   synchronization stays bound to the thread after any test. A PR that weakens one of these
   assertions violates this ADR.
3. `ExerisDataSourceTransactionIntegrationTest` guards obligation 5 (`JdbcTemplate` joins, rolls back
   with, and suspends with the Exeris transaction) and the obligation 6 cast regression. Its
   `JdbcTransactionManager` test must keep its "not a supported pairing" statement.
4. Existing code complies. This ADR records the decision that the 0.8.0 change implements and locks it
   against drift.
