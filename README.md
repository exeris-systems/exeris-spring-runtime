# exeris-spring-runtime

**Exeris as the host runtime for Spring applications.**

`exeris-spring-runtime` allows Spring-based applications to run on top of the Exeris execution model without making the Exeris kernel depend on Spring.

## What this project is

This repository provides the integration layer between:

- the **Spring application model** — dependency injection, configuration, bean lifecycle, developer ergonomics,
- and the **Exeris runtime model** — zero-copy transport, off-heap memory, Loom-first execution, deterministic lifecycle, and provider-driven runtime composition.

## What this project is not

This is **not just a thin Spring Boot starter**.

While it includes Boot autoconfiguration, its broader goal is to let **Exeris own the runtime path** for Spring applications, including:
- transport ingress,
- request execution,
- backpressure and runtime lifecycle,
- selected transaction/resource boundaries,
- operational diagnostics.

## Architectural stance

This repository exists outside `exeris-kernel` by design.

It preserves the core Exeris invariants:
- no Spring dependencies in `exeris-kernel-spi`,
- no Spring-aware `exeris-kernel-core`,
- no replacement of canonical provider discovery,
- no hidden fallback to servlet/reactive ownership while claiming Exeris-hosted execution.

## Modules

- `exeris-spring-boot-autoconfigure` — Boot configuration and lifecycle integration
- `exeris-spring-runtime-web` — web/runtime bridge and request handling
- `exeris-spring-runtime-tx` — transaction and resource-boundary integration
- `exeris-spring-runtime-data` — optional persistence/data-access compatibility
- `exeris-spring-runtime-actuator` — health, metrics, diagnostics, and graceful shutdown

## Modes

The repository is expected to support two explicit modes:

- **Pure Mode** — Exeris-native request path, minimal compatibility surface, performance-first
- **Compatibility Mode** — selected Spring programming-model conveniences with explicitly documented trade-offs

## Status

Early architecture and bootstrap stage.

## Documentation precedence

When documents differ, use this source-of-truth order:

1. **Strategic architecture truth**
	- `docs/adr/*`
	- `docs/architecture/module-boundaries.md`
	- `docs/architecture/kernel-integration-seams.md`
2. **Delivery truth**
	- `docs/phases/phase-*.md`
3. **Repo-wide review behavior**
	- `.github/copilot-instructions.md`

Conflict handling:
- ADRs win on architecture intent.
- module boundaries + integration seams win on structural contracts.
- phase docs win on current delivery scope unless explicitly superseded by ADR.

## Canonical roadmap semantics

- **Phase 0** proves bootstrap coexistence and Wall integrity.
- **Phase 1** proves host-runtime legitimacy (Exeris-owned ingress path).
- **Phase 2** adds explicitly scoped, opt-in Spring compatibility.
- **Phase 3** expands into high-risk tx/context/persistence concerns.

## Getting the artefacts

**Releases** are published to **Maven Central** under `eu.exeris`. They need no repository entry
and no credentials. Import the BOM and depend on the modules you use:

```xml
<dependencyManagement>
	<dependencies>
		<dependency>
			<groupId>eu.exeris</groupId>
			<artifactId>exeris-spring-runtime-bom</artifactId>
			<version><!-- release version --></version>
			<type>pom</type>
			<scope>import</scope>
		</dependency>
	</dependencies>
</dependencyManagement>
```

The kernel this runtime binds to (`eu.exeris:exeris-kernel-*`) is on Maven Central as well.

**Snapshots** (`-SNAPSHOT`, built from every commit to `main`) are published to GitHub Packages
only. A consumer of snapshots declares the repository
`https://maven.pkg.github.com/exeris-systems/exeris-spring-runtime` and a matching `<server>` in
its own `settings.xml`, with a token that has `read:packages`.

Building this repository needs no package credentials. `.github/maven-settings.xml` carries only
the server entries CI uses to publish: GitHub Packages for snapshots, and `central` for releases.

## License

Licensed under the Apache License, Version 2.0 (`Apache-2.0`) — the same licence as the
Exeris kernel this repository bridges to. See [`LICENSE`](LICENSE), [`NOTICE`](NOTICE) and
[`LICENSING.md`](LICENSING.md).

## Contributing

Please use `CONTRIBUTING.md` for contribution rules, architecture guardrails, testing scope, and docs/ADR update requirements.
