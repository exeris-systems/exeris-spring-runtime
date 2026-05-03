# `.claude/` — Claude Code workspace

This directory mirrors `.github/{agents,skills}` and works alongside `CLAUDE.md` at the repo root so Claude Code has the same operating context as GitHub Copilot.

## Layout

- `agents/` — sub-agents Claude can launch via the `Agent` tool (or the user can invoke directly):
  - `exeris-spring-runtime-router.md` — entrypoint triage; classifies and routes work
  - `exeris-spring-runtime-architect.md` — Wall, ownership, mode, module placement gatekeeper
  - `exeris-spring-runtime-implementer.md` — concrete code changes once architecture is clear
  - `exeris-spring-runtime-verification.md` — test depth and runtime-ownership evidence
  - `exeris-spring-runtime-docs-adr.md` — documentation/ADR drift control
  - `exeris-spring-runtime-performance.md` — request-path overhead review
- `skills/` — invocable skills (`/<skill-name>`):
  - `exeris-spring-task-classifier`, `exeris-spring-routing-planner`
  - `exeris-spring-ownership-boundary-review`, `exeris-spring-mode-clarity-review`
  - `exeris-spring-module-boundary-review`, `exeris-spring-kernel-wall-check`
  - `exeris-spring-docs-adr-check`, `exeris-spring-verification-planner`
  - `exeris-spring-runtime-path-performance-review`

## Instructions / doctrine — single source

Project doctrine deliberately is **not** duplicated under `.claude/` to avoid drift:

- **`/CLAUDE.md`** (repo root) — auto-loaded operating context for Claude Code (build, modes, module rules, kernel seams, hot-path discipline).
- **`.github/copilot-instructions.md`** — long-form architectural review checklist; shared between Claude and Copilot. Referenced from `CLAUDE.md` under documentation precedence.
- **`docs/adr/`, `docs/architecture/`, `docs/phases/`** — strategic, structural, and delivery truth in that precedence order (see `CLAUDE.md`).

Counterpart files in `.github/`:

| `.github/`                         | `.claude/`                       | Notes                              |
|------------------------------------|----------------------------------|------------------------------------|
| `agents/*.agent.md`                | `agents/*.md`                    | Frontmatter translated to Claude   |
| `skills/<name>/SKILL.md`           | `skills/<name>/SKILL.md`         | Frontmatter added (`name`, `description`) |
| `copilot-instructions.md`          | (referenced from `/CLAUDE.md`)   | Single source — not duplicated     |
| `maven-settings.xml`               | (used directly via `mvn -s`)     | Build config, no mirror needed     |
