---
title: "CLAUDE.md — exeris-spring-runtime"
type: reference
visibility: public
owning-repo: exeris-spring-runtime
status: active
last-verified: 2026-10-07
---

# CLAUDE.md — exeris-spring-runtime

This repository's agent contract is [`AGENTS.md`](AGENTS.md), and its detailed semantics live in [`.agents/`](.agents) — policies, references, skills, role profiles and workflows. Read `AGENTS.md` first; it is the entry point every compatible agent can discover.

This file exists only because a Claude client looks for it. It states no rule of its own: a rule written here would be a second place to author project semantics, which is forbidden.

Claude-specific adapters generated from `.agents/` are in [`.claude/`](.claude), each carrying a do-not-edit marker naming its source.
