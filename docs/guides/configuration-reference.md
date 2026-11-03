---
title: "Configuration reference"
type: reference
visibility: public
owning-repo: exeris-spring-runtime
status: active
last-verified: 2026-11-01
---

# Configuration reference

This page lists every property `exeris-spring-runtime` 0.8.0 reads. Set them like any other Spring
property: `application.properties`, YAML, environment variables, or command-line arguments. Relaxed
binding applies.

The properties fall into three groups:

- **Runtime properties.** This runtime binds them itself, and their defaults are its own.
- **Kernel settings.** This runtime forwards them to the kernel through `ExerisSpringConfigProvider`.
  When unset, the kernel's default applies; the defaults listed are those of kernel 0.13.1.
- **Kernel raw keys.** The kernel reads these under their own names. Any Spring property with that
  exact name reaches the kernel unchanged, because `ExerisSpringConfigProvider` is the kernel's
  configuration provider.

## Runtime and lifecycle — `exeris.runtime.*`

| Property | Default | Meaning |
|:--|:--|:--|
| `exeris.runtime.enabled` | `true` | Master switch. `false` registers no runtime beans at all. |
| `exeris.runtime.auto-start` | `true` | Boot the kernel during context refresh. `false` leaves it to code that starts `ExerisRuntimeLifecycle`. |
| `exeris.runtime.subsystems` | empty | Kernel subsystems to boot, as a list. Empty boots the whole Community set: `memory`, `crypto`, `security`, `persistence`, `scheduling`, `storage`, `graph`, `transport`, `http`, `events`, `flow` and `websocket`. Persistence fails the boot when no database is reachable, so a service without one names its subsystems. |
| `exeris.runtime.lifecycle.startup-timeout-seconds` | `30` | How long Spring waits for the kernel to reach `READY`. |
| `exeris.runtime.shutdown.graceful` | `true` | With `true`, the stop releases the kernel and waits for it, up to `timeout-seconds`, so ingress closes and in-flight requests finish. With `false`, the stop interrupts the boot thread and then waits for the kernel's own stop without that bound, which can take longer. Keep `true`. |
| `exeris.runtime.shutdown.timeout-seconds` | `30` | How long a graceful stop waits for the kernel's boot thread to finish. It bounds Spring's wait, not the kernel's own 60 s drain deadline. Keep it below your platform's kill grace period. See [Health and operations](health-and-operations.md). |

## Web — `exeris.runtime.web.*`

| Property | Default | Meaning |
|:--|:--|:--|
| `exeris.runtime.web.mode` | `pure` | `pure` dispatches to `@ExerisRoute` handlers. `compatibility` dispatches to Spring MVC controllers without a servlet container. See [Migrating a Spring MVC application](compatibility-mode-migration.md). |
| `exeris.runtime.web.compat.security.reject-invalid-token` | `true` | Compatibility Mode. A bearer token that is present but invalid is answered `401` before the handler runs. `false` lets the request continue as anonymous. |
| `exeris.runtime.web.compat.security.allow-unenforced-filter-chain` | `false` | Compatibility Mode. A `SecurityFilterChain` bean fails startup, because no servlet filter chain runs on Exeris. `true` downgrades the failure to a warning. The chain still does not run. |
| `exeris.runtime.context.scope.enabled` | `false` | Binds a `ScopedValue` request scope (Phase 3B-α) for the duration of each request. |

`spring.security.oauth2.resourceserver.jwt.*` configures the Compatibility Mode `JwtDecoder`. It does
not configure the kernel's edge verification; for that, see [Security](#security--kernel-raw-keys).

## Network — kernel settings

| Property | Kernel default | Meaning |
|:--|:--|:--|
| `exeris.runtime.network.port` | `8443` | The port the kernel listens on. When unset, `server.port` is used if set. |
| `exeris.runtime.network.bind-host` | kernel's | Listen address. When unset, `server.address` is used if set. |
| `exeris.runtime.network.buffer-size` | `65536` | Transport buffer size, in bytes. |
| `exeris.runtime.network.native-transport-preferred` | `true` | Prefer the native socket backend when available. |
| `exeris.runtime.network.reactor-count` | `0` | Reactor threads. `0` lets the kernel choose. |
| `exeris.runtime.network.quic-enabled` | `true` | Allow QUIC where the kernel tier supports it. |

## Kernel profile, memory and telemetry — kernel settings

| Property | Kernel default | Meaning |
|:--|:--|:--|
| `exeris.runtime.profile` | `prod` | Kernel profile. |
| `exeris.runtime.memory.global-mb` | `512` | Off-heap memory budget, in MiB. |
| `exeris.runtime.telemetry.jfr-enabled` | `true` | Kernel JFR events. |
| `exeris.runtime.telemetry.metrics-enabled` | `true` | Kernel metrics. |
| `exeris.runtime.telemetry.tracing-enabled` | `false` | Forwarded to the kernel. This release has no tracing bridge, so setting it produces no spans. |
| `exeris.runtime.telemetry.node-id` | `local` | Node identifier on telemetry. |
| `exeris.runtime.telemetry.region` | `default` | Region label on telemetry. |

## Persistence — kernel settings and raw keys

Read by the kernel's persistence subsystem.

| Property | Kernel default | Meaning |
|:--|:--|:--|
| `exeris.runtime.persistence.jdbc-url` | `jdbc:postgresql://localhost:5432/exeris` | JDBC URL. |
| `exeris.runtime.persistence.username` | kernel's | Database user. |
| `exeris.runtime.persistence.password` | kernel's | Database password. |
| `exeris.runtime.persistence.run-migrations` | `false` | Run the kernel's migrations at boot. |
| `exeris.runtime.persistence.max-pool-size` | `clamp(2 × CPUs, 2, 32)` | Pool maximum. |
| `exeris.runtime.persistence.min-pool-size` | kernel's | Pool minimum idle connections. |
| `exeris.runtime.persistence.pool-warmup-enabled` | kernel's | Open connections at boot. |
| `exeris.runtime.persistence.pool-warmup-connections` | kernel's | How many to open. |
| `exeris.runtime.persistence.connection-timeout-ms` | kernel's | Connection acquisition timeout. |

## Transactions and JDBC — `exeris.runtime.tx.*`, `exeris.runtime.data.*`

| Property | Default | Meaning |
|:--|:--|:--|
| `exeris.runtime.tx.enabled` | `false` | Registers `ExerisPlatformTransactionManager` for `@Transactional`. |
| `exeris.runtime.data.compat-datasource.enabled` | `false` | Registers `ExerisDataSource`, the bounded JDBC bridge of ADR-017, for `JdbcTemplate` and JPA. |

See [Transactions and JDBC](transactions-and-jdbc.md).

## Events, flows and graph

| Property | Default | Meaning |
|:--|:--|:--|
| `exeris.runtime.events.enabled` | `false` | `ExerisEventPublisher` and `@ExerisEventListener` over the kernel `EventBus`. The kernel's `events` and `flow` subsystems depend on `persistence`, so they need a JDBC URL. |
| `exeris.runtime.events.require-engine` | `true` | Fail the lifecycle start when listeners are declared and no kernel event engine is bound. |
| `exeris.runtime.flow.enabled` | `false` | `ExerisFlowTemplate` and flow definitions over the kernel flow engine. |
| `exeris.runtime.flow.persistence-enabled` | unset: off | Durable saga snapshots in `exeris_saga_state`. Forwarded to the kernel as `flow.persistenceEnabled`; unset, the Community engine's default applies, which is off. Set `true` and give the kernel a database with the saga table, for example via `exeris.runtime.persistence.run-migrations=true`. |
| `exeris.runtime.flow.choreography-enabled` | `false` | Event-driven flow triggers. |
| `exeris.runtime.flow.require-engine` | `true` | Fail startup when definitions are declared and no flow engine is bound. |
| `exeris.runtime.graph.enabled` | `false` | `ExerisGraphTemplate` and `@ExerisGraphQuery` over the kernel graph engine. |
| `exeris.runtime.graph.require-engine` | `true` | With no graph engine bound, every `ExerisGraphTemplate` call throws `IllegalStateException`. The failure comes at first use, not at startup. |

See [Events and sagas](events-and-sagas.md) and [Graph queries](graph-queries.md).

## Security — kernel raw keys

The kernel's edge reads these to verify bearer tokens, for the per-path rules of `ExerisHttpSecurity`.
The kernel reads them under these exact names; there is no `exeris.runtime.` prefix.

| Property | Default | Meaning |
|:--|:--|:--|
| `security.jwks.uri` | unset | JWKS document tokens are verified against: an absolute `http` or `https` URI with a host. |
| `security.jwt.issuer` | unset | The `iss` claim a token must carry. |
| `security.jwt.audience` | unset | The `aud` claim a token must carry. |

Set all three or none. With none, every token is refused, so every route that requires an identity
answers `401`. A partial set fails the kernel boot with `EX-BOOT-0002`. See
[Securing routes](securing-routes.md).

## Legacy aliases

The kernel's HTTP subsystem also reads `exeris.http.port`, `exeris.http.bindHost` and `exeris.http.mode`,
and their bare `http.*` forms. This runtime answers them from the properties above, and always answers
`SERVER` for the mode. Do not set them directly.
