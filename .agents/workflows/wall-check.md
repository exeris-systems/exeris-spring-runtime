---
name: wall-check
description: Verify The Wall — no Spring type leaks into `eu.exeris.kernel.spi.*` or `eu.exeris.kernel.core.*`, and no cap reaches into Spring internals.
argument-hint: PR diff or kernel-boundary / cap-boundary change to audit
steps:
  - {skill: exeris-spring-kernel-wall-check}
  - {agent: exeris-spring-runtime-architect}
gates:
  - hook:guardrails-gate-on-stop
---

Audit this change against The Wall.

The Wall rules:
- The kernel (`exeris-kernel-spi`, `exeris-kernel-core`) is consumed as binary dependencies and must remain Spring-free.
- No Spring types inside `eu.exeris.kernel.spi.*` or `eu.exeris.kernel.core.*`.
- Cap-tier Wall (per HLA §4): no `exeris-caps-*` may reach into Spring internals.
- No Spring annotations (`@Component`, `@Autowired`) in kernel touchpoints.
- Provider discovery relies strictly on canonical `ServiceLoader`, not Spring DI.

Change:
$ARGUMENTS

Please review:
1. Does this change introduce any Wall-banned type — Spring (`org.springframework.*`), servlet (`jakarta.servlet.*`), Netty (`io.netty.*`), or Reactor (`io.projectreactor.*`) — into a kernel SPI/Core touchpoint?
2. Does this change introduce a Spring annotation into a kernel-facing class?
3. Does this change bypass canonical Exeris provider discovery (`ServiceLoader`) with a Spring DI lookup?
4. Does the change assume Spring is present in a place where the kernel runs standalone?
5. Does any cap reach into Spring internals (cap-tier Wall violation)?
6. Minimal correction if The Wall is at risk.

Validate against `WallIntegrityTest` ArchUnit assertion. Never weaken any kernel-boundary assertion.
