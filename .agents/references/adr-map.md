# Reference: ADRs That Bind This Repository

The authoritative text of local decisions lives in `docs/adr/`. This reference maps each ADR to when it applies in this repository and states the source-of-truth precedence.

| ADR | Title & Scope | Applies when |
|:--|:--|:--|
| ADR-010 | Host-Runtime Model | Validating ownership: Spring is application framework, Exeris is runtime owner. Ingress, request lifecycle, and memory belong to Exeris. |
| ADR-011 | Pure Mode vs Compatibility Mode | Any change to web dispatch, context propagation, or mode toggles (`PURE_MODE` vs `COMPATIBILITY_MODE`). |
| ADR-017 | JDBC Compact Scope | Persistence bridging: read-only/narrow JDBC compatibility, no HikariCP/JPA ownership. |
| ADR-021 | Gateway Workloads Out of Compatibility Scope | Verifying that edge gateway concerns stay outside Spring Cloud Gateway emulation. |
| ADR-027 | EventBus / ApplicationEventPublisher Boundary | Event bridging: Exeris events ↔ Spring events without circular publish loops. |
| ADR-028 | Spring Boot 4 Nominal Compatibility Scope | Spring Boot 3/4 baseline and dual-matrix compatibility checks. |
| ADR-029 | Phase 3B-α Request Scope and Structured Concurrency | Request scope boundaries, Virtual Threads, and Structured Concurrency integration. |
| ADR-030 | Phase 4C Spring-Side Seam for Kernel Graph SPI | `exeris-spring-runtime-graph` integration: default-off, no Spring Data Neo4j. |
| ADR-041 | Compat Resource Server Security Under None | Security bridging under compat mode: narrow `ThreadLocal` cleared in `finally`. |
| ADR-063 | ExerisHttpSecurity Route Policy Binding | Route policy compilation at startup; kernel decides, runtime compiles. |
| ADR-067 | Binary Neutrality of the Published Artefact | Ensuring published JARs have no preview stamps and maintain multi-runtime neutrality. |
| ADR-068 | Two-Track JDK Artefact Model | GA track (Java 25 LTS, major 69) vs preview track. No preview flags on GA line. |

## Precedence When Documents Disagree

When documentation sources conflict, resolve by precedence:
1. `docs/adr/*` — architectural intent and non-negotiables.
2. `docs/architecture/module-boundaries.md` and `kernel-integration-seams.md` — structural contracts.
3. `docs/phases/phase-*.md` — current milestone scope.
4. `.agents/` and `AGENTS.md` — agent operational instructions.

## ADR Status Convention

- **`ACCEPTED`** (accepted-on-merge): Used for ADRs with a single decider and no gating event, ratified by the merging PR.
- **`PROPOSED`**: Used when a decision is open and deliberated across stakeholders before commitment.
