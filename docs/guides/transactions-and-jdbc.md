---
title: "Transactions and JDBC on the Exeris persistence engine"
type: howto
visibility: public
owning-repo: exeris-spring-runtime
status: active
last-verified: 2026-11-03
---

# Transactions and JDBC on the Exeris persistence engine

This guide shows you how to run `@Transactional` services on JDBC connections that the Exeris kernel
owns. The kernel persistence engine opens the connections and pools them. Spring marks the
transaction boundaries. The application reads and writes through a `javax.sql.DataSource`, either
directly or with Spring's `JdbcTemplate`.

**Mode: mixed.** Ingress is Pure Mode: a kernel route calls your service. The transaction bridge and
the JDBC bridge are Compatibility Mode surfaces. `ExerisDataSource` is marked `@CompatibilityMode`,
and ADR-017 calls this Level 2 persistence. Level 1, the recommended path, is application code
written against the kernel's `PersistenceEngine`.

The finished application is [`examples/transactions-jdbc`](../../examples/transactions-jdbc). It is a
small ledger: open an account, transfer between two accounts, read a balance. Its tests send real
requests and check commit, rollback and propagation against an in-memory H2 database.

## Prerequisites

- An application that already runs in Pure Mode. Start with [Getting started](getting-started.md).
- **JDK 25** or newer, and **Maven 3.9** or newer.
- A JDBC driver for **PostgreSQL** or **H2**. The Community engine supports these two databases. The
  example uses H2 in memory, so it needs no database server.

## 1. Add the modules

<!-- example: transactions-jdbc/pom.xml#dependencies -->
```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter</artifactId>
</dependency>
<dependency>
    <groupId>eu.exeris</groupId>
    <artifactId>exeris-spring-boot-autoconfigure</artifactId>
</dependency>
<dependency>
    <groupId>eu.exeris</groupId>
    <artifactId>exeris-spring-runtime-web</artifactId>
</dependency>
<!-- The transaction manager over the kernel persistence engine. -->
<dependency>
    <groupId>eu.exeris</groupId>
    <artifactId>exeris-spring-runtime-tx</artifactId>
</dependency>
<!-- The JDBC bridge: a DataSource whose connections come from the kernel engine. -->
<dependency>
    <groupId>eu.exeris</groupId>
    <artifactId>exeris-spring-runtime-data</artifactId>
</dependency>
<dependency>
    <groupId>eu.exeris</groupId>
    <artifactId>exeris-kernel-community</artifactId>
    <scope>runtime</scope>
</dependency>
<!-- JdbcTemplate, which joins the Exeris transaction. -->
<dependency>
    <groupId>org.springframework</groupId>
    <artifactId>spring-jdbc</artifactId>
</dependency>
<!-- Jackson, to read the JSON request bodies. -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-json</artifactId>
</dependency>
<!-- The JDBC driver is the application's choice. -->
<dependency>
    <groupId>com.h2database</groupId>
    <artifactId>h2</artifactId>
    <scope>runtime</scope>
</dependency>
```

With `spring-jdbc` on the classpath, Spring Boot builds a `JdbcTemplate` over the bridge `DataSource`.
Add `spring-jdbc` itself, not `spring-boot-starter-jdbc`: the starter brings a HikariCP pool, and the
kernel already pools the connections. `spring-boot-starter-json` is there only because the example
reads JSON request bodies.

## 2. Configure the engine and turn both bridges on

<!-- example: transactions-jdbc/src/main/resources/application.properties#properties -->
```properties
spring.main.web-application-type=none
exeris.runtime.network.port=8080

# persistence joins the set: the kernel engine owns the database connections and their pool.
exeris.runtime.subsystems=memory,transport,http,persistence

# The database the kernel engine connects to. H2 runs the INIT script each time it opens a
# connection, so the script only creates what does not exist yet.
exeris.runtime.persistence.jdbc-url=jdbc:h2:mem:ledger;DB_CLOSE_DELAY=-1;INIT=RUNSCRIPT FROM 'classpath:db/ledger.sql'
exeris.runtime.persistence.username=sa
exeris.runtime.persistence.password=

# Both are off unless set to true.
exeris.runtime.tx.enabled=true
exeris.runtime.data.compat-datasource.enabled=true
```

- **`exeris.runtime.persistence.*`** configures the kernel engine, not Spring. Spring Boot's
  `spring.datasource.*` properties play no part here. The kernel pools the connections, so add no
  pool of your own. For pool sizing, see [Configuration reference](configuration-reference.md).
- **`exeris.runtime.tx.enabled=true`** registers `ExerisPlatformTransactionManager`. While it is
  registered, Spring Boot adds no transaction manager of its own, even with `spring-jdbc` on the
  classpath. Unless the flag is `true`, there is no Exeris transaction manager. Without `spring-jdbc`,
  `@Transactional` then has nothing to bind to. With `spring-jdbc`, Spring Boot registers a
  `JdbcTransactionManager` over the bridge, which is not supported (see
  [Where this does not apply](#where-this-does-not-apply)).
- **`exeris.runtime.data.compat-datasource.enabled=true`** registers `ExerisDataSource` as the
  `@Primary` `DataSource` and connects it to the transaction manager. It backs off if the
  application declares its own `DataSource` bean.
- **The schema.** The engine starts after the Spring context has refreshed, so nothing can use the
  `DataSource` during startup. The example lets H2 create the schema whenever a connection opens. On
  PostgreSQL, run your migration tool against the database with its own connection settings.

## 3. Write the transactional service

The `Ledger` service marks each public method `@Transactional` and writes through `JdbcTemplate`:

<!-- example: transactions-jdbc/src/main/java/eu/exeris/examples/ledger/Ledger.java#transfer -->
```java
@Transactional
public void transfer(String from, String to, long amount) {
    // JdbcTemplate takes the transaction's connection, so every statement below runs in one
    // transaction, which commits or rolls back when the method returns.
    adjust(to, amount);
    adjust(from, -amount);
    if (jdbc.queryForObject("SELECT balance FROM account WHERE id = ?", Long.class, from) < 0) {
        // An unchecked exception rolls back both updates.
        throw new InsufficientFundsException(from);
    }
}

private void adjust(String account, long delta) {
    if (jdbc.update("UPDATE account SET balance = balance + ? WHERE id = ?", delta, account) == 0) {
        throw new NoSuchAccountException(account);
    }
}
```

`JdbcTemplate` takes its connection through Spring's `DataSourceUtils`, as it does under any other
transaction manager. Inside an Exeris transaction it gets the transaction's connection, so its
statements commit and roll back with the transaction. The same holds for any code that takes its
connection through `DataSourceUtils`.

You can also use the `DataSource` and the JDBC API directly. The example reads a balance that way:

<!-- example: transactions-jdbc/src/main/java/eu/exeris/examples/ledger/Ledger.java#balance -->
```java
@Transactional(readOnly = true)
public long balance(String account) throws SQLException {
    // Inside the transaction getConnection() returns the transaction's connection, and close()
    // does not end it: the transaction manager ends the transaction when the method returns.
    try (Connection connection = dataSource.getConnection();
         PreparedStatement query = connection.prepareStatement(
                 "SELECT balance FROM account WHERE id = ?")) {
        query.setString(1, account);
        try (ResultSet row = query.executeQuery()) {
            if (!row.next()) {
                throw new NoSuchAccountException(account);
            }
            return row.getLong(1);
        }
    }
}
```

Inside the transaction, every `getConnection()` returns the same connection, the one `JdbcTemplate`
uses. Do not call `commit()` or `rollback()` on it: inside a managed transaction, both calls do
nothing. `setAutoCommit(true)` and `setTransactionIsolation(...)` throw `SQLException` there. Set the
isolation on the annotation, for example `@Transactional(isolation = Isolation.SERIALIZABLE)`.

Spring's own rollback rule applies: a `RuntimeException` or an `Error` rolls the transaction back,
and a checked exception such as `SQLException` commits it. Use `rollbackFor` if a checked exception
must roll back.

## 4. Call the service from a kernel route

The handler reads the transfer from the JSON request body and maps the service's exceptions to
status codes:

<!-- example: transactions-jdbc/src/main/java/eu/exeris/examples/ledger/TransferHandler.java#handler -->
```java
@Component
@ExerisRoute(method = HttpMethod.POST, path = "/transfers")
public class TransferHandler implements ExerisRequestHandler {

    record Transfer(String from, String to, long amount) {
    }

    private final Ledger ledger;

    private final ObjectMapper json;

    public TransferHandler(Ledger ledger, ObjectMapper json) {
        this.ledger = ledger;
        this.json = json;
    }

    @Override
    public ExerisServerResponse handle(ExerisServerRequest request) throws Exception {
        Transfer transfer = json.readValue(request.bodyBytes(), Transfer.class);
        try {
            ledger.transfer(transfer.from(), transfer.to(), transfer.amount());
            return ExerisServerResponse.ok();
        } catch (InsufficientFundsException ex) {
            // The transaction has already rolled back when the exception reaches this point.
            return ExerisServerResponse.status(HttpStatus.CONFLICT).body(ex.getMessage());
        } catch (NoSuchAccountException ex) {
            return ExerisServerResponse.status(HttpStatus.NOT_FOUND).body(ex.getMessage());
        }
    }
}
```

Transactions start where the kernel persistence engine is bound. The runtime binds it for the
duration of each request, and around each flow step. On any other thread, such as `main`, a test
thread or your own executor, `getConnection()` throws `SQLException` ("Exeris PersistenceEngine is
not bound in the current scope"), and `@Transactional` throws `CannotCreateTransactionException`.

A request holds **one database connection** for its whole duration. Each transaction in the request
runs on that connection, one after another.

**Run every write inside `@Transactional`.** On a request route, a write made outside a transaction
is not kept, whether it goes through `JdbcTemplate` or through the `DataSource`. The connection does
not auto-commit, and the uncommitted work is rolled back when the request ends. Nothing reports it:
the request completes normally, and the data is gone.

## 5. Run it

```bash
mvn spring-boot:run
```

```bash
curl -i http://localhost:8080/accounts -H 'Content-Type: application/json' \
     -d '{"id": "alice", "balance": 100}'   # 201 Created
curl -i http://localhost:8080/accounts -H 'Content-Type: application/json' \
     -d '{"id": "bob", "balance": 0}'       # 201 Created
curl -i http://localhost:8080/transfers -H 'Content-Type: application/json' \
     -d '{"from": "alice", "to": "bob", "amount": 30}'
curl 'http://localhost:8080/balance?id=alice'
```

```
HTTP/1.1 200 OK
...
70
```

Now transfer more than Alice has:

```bash
curl -i http://localhost:8080/transfers -H 'Content-Type: application/json' \
     -d '{"from": "alice", "to": "bob", "amount": 500}'
```

```
HTTP/1.1 409 Conflict
...
insufficient funds in alice
```

Both updates ran before the balance check failed, and neither one was kept. Alice still has `70`
and Bob has `30`. A transfer from an account that does not exist answers `404 Not Found`, and the
credit already made to the other account is rolled back the same way.

## Propagation

`ExerisPlatformTransactionManager` follows Spring's propagation rules wherever the kernel can support
them. Where it cannot, the call fails before any of its work runs, and the outer transaction stays
active and usable. [ADR-109](../adr/ADR-109-spring-transaction-propagation-and-the-jdbc-bridges-binding-key-under-a-kernel-that-pins-one-connection-per-request.md)
defines these rules.

With no transaction open, `REQUIRED` and `REQUIRES_NEW` start one. `SUPPORTS`, `NOT_SUPPORTED` and
`NEVER` run without one. `MANDATORY` throws `IllegalTransactionStateException`, and `NESTED` throws
`UnsupportedOperationException`. Inside an open transaction, each propagation behaves as follows:

| Propagation | On a kernel request route | Off a request route |
|:--|:--|:--|
| `REQUIRED` (default) | Joins | Joins |
| `SUPPORTS` | Joins | Joins |
| `MANDATORY` | Joins | Joins |
| `NEVER` | Throws `IllegalTransactionStateException` | Throws `IllegalTransactionStateException` |
| `REQUIRES_NEW` | **Throws `IllegalTransactionStateException`** before the inner work runs | Suspends the outer transaction, runs on a second connection, resumes the outer one |
| `NOT_SUPPORTED` | **Throws `IllegalTransactionStateException`** before the inner work runs | Suspends the outer transaction, runs without one, resumes the outer one |
| `NESTED` | Throws `UnsupportedOperationException` | Throws `UnsupportedOperationException` |

**The request-route rule.** On a kernel request route the kernel holds one connection for the whole
request, so there is no second connection to suspend onto. `REQUIRES_NEW` and `NOT_SUPPORTED` inside
a transaction are refused rather than run inside the outer transaction against their promise. Catch
the exception and the outer transaction carries on and commits. Work that must commit independently
of the request's transaction has to run off the request path.

Off a request route, in a flow step for example, suspension works. To tell the two cases apart,
every suspension borrows one pooled connection and returns it at once, so a suspension needs a free
pool slot beside the one the suspended transaction holds. That includes `NOT_SUPPORTED`. With no slot
free, it waits for `exeris.runtime.persistence.connection-timeout-ms` and fails with
`CannotCreateTransactionException`, and the outer transaction stays usable.

A joined participant that throws marks the whole transaction rollback-only. If the outer method
catches the exception and returns normally, its commit fails with `UnexpectedRollbackException`, and
nothing from the transaction is kept.

**Completion callbacks.** An `afterCommit` or `afterCompletion` callback, or a
`@TransactionalEventListener`, cannot join the transaction that has just ended. `REQUIRED`,
`SUPPORTS` and `MANDATORY` there throw `IllegalTransactionStateException`. Declare `REQUIRES_NEW` for
data access from such a callback: it starts a new transaction, on a request route on the request's
connection.

Two more limits apply. Savepoints do not exist on the kernel connection contract, so `NESTED` has no
implementation. Serialization failures (SQLState `40001`, `40P01`) are not retried. If you need
automatic retry, use the kernel's `TransactionalExecutor` directly.

## JPA and Hibernate on the bridge

Hibernate can use `ExerisDataSource` only within the bounds ADR-017 sets. That ADR targets the
`SessionFactory` bootstrap and core CRUD, nothing more.

- **Bootstrap.** When the bridge is on and Hibernate is on the classpath,
  `ExerisHibernateBootstrapCustomizer` sets `hibernate.boot.allow_jdbc_metadata_access=false`. It
  also derives `hibernate.dialect` from `exeris.runtime.persistence.jdbc-url`. Without this,
  Hibernate would open a connection during context refresh, before the engine exists.
- **Dialects.** The customizer derives a dialect for `jdbc:postgresql:` and `jdbc:h2:` URLs only. For
  any other URL, startup fails until you set `spring.jpa.database-platform`. If you set that property
  or `spring.jpa.properties.hibernate.dialect` yourself, your value wins.
- **Out of scope:** stored procedures, savepoints, scrollable cursors and advanced JDBC types. Also
  out of scope: Hibernate `StatelessSession`, bulk operations that bypass the connection lifecycle,
  and DDL that changes auto-commit behind the bridge's back. `Connection.unwrap(...)` always throws
  `SQLException`, and `getConnection(user, password)` throws `SQLFeatureNotSupportedException`.
- **Telemetry.** Statements issued through the bridge do not appear in the kernel's per-statement
  telemetry. The bridge emits two JFR events, `eu.exeris.spring.runtime.data.JpaConnectionAcquired`
  and `eu.exeris.spring.runtime.data.JpaConnectionBound`, at `getConnection()`.

The transaction rules above apply to JPA too. This repository's tests do not run Hibernate end to
end, so test your mappings and queries against the engine before you rely on them.

## Where this does not apply

- **What the bridge is not.** It is not a connection pool, it does not re-implement JDBC, and it is
  not the recommended persistence path. Each connection is the driver's own `java.sql.Connection`
  inside a thin proxy that only intercepts lifecycle calls. New services should write Level 1
  repositories against `PersistenceEngine`, which you get through the `PersistenceEngineProvider`
  bean.
- **The Enterprise engine.** It is not JDBC-backed. `getConnection()` throws
  `UnsupportedOperationException` when that engine is bound.
- **Work off the request thread.** Scheduled jobs, `@Async` methods and your own executors run
  without the engine bound, and their transactions cannot begin.
- **`JdbcTransactionManager` over the bridge.** It lets Spring commit and roll back raw JDBC
  transactions on a connection the kernel owns, around the kernel's own transaction handling.
  ADR-017 rejects that split ownership. Use `ExerisPlatformTransactionManager`
  (`exeris.runtime.tx.enabled=true`), and do not declare a second transaction manager over
  `ExerisDataSource`.
- **A second `ExerisDataSource` you build yourself.** Only the bean the runtime registers is connected
  to the transaction manager. Another instance runs outside the transaction, on its own connection.
- **Several databases.** The bridge serves the one engine the kernel boots.

## Related

- [Getting started](getting-started.md): the Pure Mode application this guide builds on.
- [Migrating a Spring MVC application](compatibility-mode-migration.md): serving existing
  controllers on Exeris in Compatibility Mode.
- [Configuration reference](configuration-reference.md): every `exeris.runtime.*` property, pool
  sizing included.
- [ADR-017](../adr/ADR-017-jdbc-compact-scope.md): the scope of the JDBC bridge.
- [ADR-109](../adr/ADR-109-spring-transaction-propagation-and-the-jdbc-bridges-binding-key-under-a-kernel-that-pins-one-connection-per-request.md):
  the propagation rules and how `JdbcTemplate` joins an Exeris transaction.
