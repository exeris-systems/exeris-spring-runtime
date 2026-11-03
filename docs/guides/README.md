---
title: "Guides"
type: reference
visibility: public
owning-repo: exeris-spring-runtime
status: active
last-verified: 2026-11-03
---

# Guides

Task-oriented documentation for `exeris-spring-runtime` 0.8.0. Every guide quotes a runnable application
under [`examples/`](../../examples). CI builds each one against the runtime of the same commit and calls
it over a real socket. `tools/check_guide_snippets.py` fails the build when a quoted block stops matching
its example.

| Guide | Type | Example |
|:--|:--|:--|
| [Getting started: your first Pure Mode application](getting-started.md) | tutorial | `pure-mode-hello` |
| [Migrating a Spring MVC application to Compatibility Mode](compatibility-mode-migration.md) | how-to | `compat-mvc` |
| [Securing routes with ExerisHttpSecurity](securing-routes.md) | how-to | `secured-routes` |
| [Transactions and JDBC](transactions-and-jdbc.md) | how-to | `transactions-jdbc` |
| [Events and flows (sagas)](events-and-sagas.md) | how-to | `events-and-flows` |
| [Graph queries with ExerisGraphTemplate](graph-queries.md) | how-to | `graph-queries` |
| [Health, metrics and shutdown in production](health-and-operations.md) | how-to | `operations` |
| [Configuration reference](configuration-reference.md) | reference | — |
| [Upgrading from 0.7 to 0.8](upgrading-0.7-to-0.8.md) | migration guide | — |

To build and run the examples locally, see
[Getting started — Run the examples from this repository](getting-started.md#run-the-examples-from-this-repository).

For the architecture behind these guides, see [`docs/architecture/overview.md`](../architecture/overview.md).
