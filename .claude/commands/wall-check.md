---
description: Verify The Wall — no Spring type leaks into `eu.exeris.kernel.spi.*` or `eu.exeris.kernel.core.*`, and no cap reaches into Spring internals.
argument-hint: PR diff or kernel-boundary / cap-boundary change to audit
---

Audit this change against The Wall.

Wall rules (per repo `CLAUDE.md`):
- The kernel (`exeris-kernel-spi`, `exeris-kernel-core`) is consumed as binary deps and MUST remain Spring-free.
- No Spring types inside `eu.exeris.kernel.spi.*` or `eu.exeris.kernel.core.*`.
- Cap-tier Wall (per HLA §4): no `exeris-caps-*` may reach into Spring internals — keeps cap manifests reusable across kernel-direct AND Spring-Runtime-hosted deployments without manifest changes.

Change:
$ARGUMENTS

Please review:
1. Does this change introduce any Wall-banned type — Spring (`org.springframework.*`), servlet (`jakarta.servlet.*`), Netty (`io.netty.*`), or Reactor (`io.projectreactor.*`) — into a kernel SPI/Core touchpoint?
2. Does this change introduce a Spring `@Component` / `@Service` / `@Autowired` annotation into a kernel-facing class?
3. Does this change bypass canonical Exeris provider discovery (`ServiceLoader`) with a Spring DI lookup?
4. Does the change assume Spring is present in a place where the kernel runs standalone?
5. Does any cap reach into Spring internals (cap-tier Wall violation)?
6. Minimal correction if The Wall is at risk.

Validate against `WallIntegrityTest` ArchUnit assertion. If you weakened any kernel-boundary-class assertion, that's a regression — fix the underlying issue, not the test.
