# Mission

`exeris-spring-runtime` enables Spring applications to run on top of the Exeris runtime model without making the Exeris kernel Spring-aware.

This repository exists to bridge two worlds:

- the **Spring application model** (DI, configuration, lifecycle, developer ergonomics),
- the **Exeris runtime model** (zero-copy transport, off-heap memory, Loom-first execution, deterministic lifecycle, and strict architectural boundaries).

The integration is designed around one principle:

> Spring remains the application framework. Exeris becomes the runtime owner.

This project is **not** a thin starter-only wrapper.  
It is a runtime integration layer that may provide:

- boot autoconfiguration,
- web/runtime bridging,
- transaction/context integration,
- optional persistence compatibility,
- actuator and diagnostics integration.

At the same time, it must preserve the kernel’s core invariants:

- no Spring dependencies in Exeris SPI,
- no Spring-aware Exeris Core,
- no replacement of canonical provider discovery with IoC ownership,
- no hidden fallback to legacy servlet/reactive runtime while claiming Exeris-hosted execution.

The long-term goal is to make Spring applications deployable on Exeris without surrendering data-plane ownership to Tomcat, Netty, or conventional JDBC-centric runtime assumptions.
