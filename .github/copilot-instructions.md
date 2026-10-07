---
title: "Copilot instructions — exeris-spring-runtime"
type: reference
visibility: public
owning-repo: exeris-spring-runtime
status: active
last-verified: 2026-10-07
---

# Copilot instructions — exeris-spring-runtime

This repository's agent contract is [`AGENTS.md`](../AGENTS.md), and its detailed semantics live in [`.agents/`](../.agents). Read `AGENTS.md` first; it is the entry point every compatible agent can discover. This file exists only because a Copilot client looks for it and states no rule of its own.

Copilot reads [`.agents/skills/`](../.agents/skills) natively. The role profiles and workflows have no Copilot adapter yet, so hand-maintained duplicates in `.github/agents/` and `.github/skills/` were removed. `.agents/manifest.yaml` records the status as deferred.
