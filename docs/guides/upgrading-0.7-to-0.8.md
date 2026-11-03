---
title: "Upgrading from 0.7 to 0.8"
type: migration-guide
visibility: public
owning-repo: exeris-spring-runtime
status: active
last-verified: 2026-11-03
---

# Upgrading from 0.7 to 0.8

This guide lists what an application on `exeris-spring-runtime` 0.7.x changes to run on 0.8.0. The
full list of changes is in the [CHANGELOG](../../CHANGELOG.md), section 0.8.0.

## 1. Resolve from Maven Central

0.8.0 releases are on Maven Central under `eu.exeris`. Remove the GitHub Packages `<repository>`
entry and its token, unless you also consume snapshots.

## 2. Move the kernel to 0.13.1

```xml
<dependency>
    <groupId>eu.exeris</groupId>
    <artifactId>exeris-kernel-community</artifactId>
    <version>0.13.1</version>
    <scope>runtime</scope>
</dependency>
```

The kernel's own changes from 0.11 to 0.13 come with this step. Read the kernel's `MIGRATION.md`,
sections "0.11.x → 0.12.x" and "0.12.x → 0.13", for the ones that touch your code. The following
reach you through this runtime:

- **Event publishing under a tenant.** A publish through `ExerisEventPublisher` under a tenant
  context needs a registered isolation key; otherwise the kernel refuses it with `EX-EVENT-6009`.
- **Graph traversals** honour the descriptor's direction, and are refused under an unscopable tenant
  context before any database I/O.
- **The kernel's JVM shutdown hook** is turned off by this runtime. Spring's context close is the
  only stop, and it runs after the beans that use the kernel. Nothing to change, unless your code
  relied on the kernel stopping by itself on `SIGTERM` before Spring did.

## 3. Check how you import the BOM

In 0.7.0, importing `exeris-spring-runtime-bom` also imported the whole Spring Boot 3.5.14 BOM and the
kernel BOM. From 0.8.0 the BOM manages this project's modules and nothing else.

- **Spring Boot 4.** If you avoided the BOM because it pulled in Spring Framework 6.x, you can
  import it now.
- **Applications without Spring Boot's own management.** If you have neither
  `spring-boot-starter-parent` nor a `spring-boot-dependencies` import of your own, your Spring
  dependencies relied on our BOM for their versions. Import Spring Boot's BOM for your line next to
  ours:

  ```xml
  <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-dependencies</artifactId>
      <version>3.5.14</version>
      <type>pom</type>
      <scope>import</scope>
  </dependency>
  ```

- **The kernel version.** The BOM no longer pins it, so declare `exeris-kernel-community` with its
  version, as in step 2.

## 4. Replace path-level authorization in handlers with `ExerisHttpSecurity`

Kernel 0.11.0 removed the `/secure` prefix convention, and 0.7.0 had nothing in its place. If you put
path checks inside handlers to compensate, 0.8.0 lets you declare them once. The kernel enforces them
before your code runs. See [Securing routes](securing-routes.md).

If you declare any rule that requires an identity, set the kernel's verification keys too:
`security.jwks.uri`, `security.jwt.issuer` and `security.jwt.audience`. Without them every token is
refused.

## 5. Review your shutdown timeout

`exeris.runtime.shutdown.timeout-seconds` now also bounds a stop that lands while the kernel is still
booting. Keep it below your platform's kill grace period. With kernel 0.13.1, an idle HTTP/2 client
connection can hold a stop open until that timeout; see
[Health and operations](health-and-operations.md).

## 6. Check code that relies on transaction propagation

In 0.7.0, `ExerisPlatformTransactionManager` started a new transaction for every `@Transactional`
call, even inside another one. 0.8.0 implements Spring's propagation within the limits the kernel
sets, which [ADR-109](../adr/ADR-109-spring-transaction-propagation-and-the-jdbc-bridges-binding-key-under-a-kernel-that-pins-one-connection-per-request.md)
records. Look for code that relied on the old behaviour:

- **A `REQUIRED` call inside a transaction now joins it.** If such a call committed on its own in
  0.7.0, its work now commits or rolls back with the outer transaction. A participant that fails
  marks the whole transaction rollback-only, and the outer commit throws
  `UnexpectedRollbackException`.
- **`REQUIRES_NEW` and `NOT_SUPPORTED` cannot suspend on a request route.** The kernel holds one
  connection for the whole HTTP request, so on a request thread both fail with
  `IllegalTransactionStateException` before the inner work runs. Off the request path, for example in
  a scheduled job, they suspend and resume as Spring defines.
- **`MANDATORY` joins and `NEVER` throws** inside a transaction.
- **Joining from a completion callback is refused.** A `REQUIRED`, `SUPPORTS` or `MANDATORY` call from
  `afterCommit` or `@TransactionalEventListener(AFTER_COMMIT)` throws. Use `REQUIRES_NEW` there, off
  the request path.
- **`JdbcTemplate` works inside an Exeris transaction.** If you used the raw `DataSource` to avoid its
  `ClassCastException`, you can switch back. `JdbcTransactionManager` over `ExerisDataSource` is not
  supported: keep `ExerisPlatformTransactionManager` as the transaction manager.

See [Transactions and JDBC](transactions-and-jdbc.md).

## Where this does not apply

- **0.6 or earlier.** Upgrade to 0.7.x first, following its CHANGELOG section, then apply this guide.
- **Kernel versions other than 0.13.1.** The steps assume the kernel this release is built and tested
  against. An older kernel lacks the edge keys of step 4.
- **Behaviour this guide does not list.** It names the changes that need action from an application.
  The CHANGELOG lists every change, including those that need none.
