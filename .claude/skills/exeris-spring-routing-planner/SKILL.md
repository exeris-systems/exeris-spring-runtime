---
name: exeris-spring-routing-planner
description: Produce a minimal, risk-aware execution order across exeris-spring-runtime agents — choose the primary agent, ordered secondary handoffs, a 3-5 step plan, validation gates, and the single best next action. Use after task-classifier has labelled the work.
---

# exeris-spring-routing-planner

## Purpose
Produce a minimal, risk-aware execution order across Spring Runtime agents.

## Inputs
- task class
- mode impact
- primary risk
- touched modules

## Output
- primary agent
- secondary handoffs (ordered)
- 3-5 step execution plan
- validation gates
- minimal next action

## Routing Priorities
1. If ownership/mode/module ambiguity exists -> `Architect` first.
2. If architecture is clear and concrete file changes are requested -> `Implementer` first.
3. If merge readiness/evidence is requested -> `Verification` early.
4. If architecture wording or guarantees changed -> include `Docs/ADR`.
5. If request-path overhead changed -> optionally include `Performance`.

## Guardrails
- Never skip architecture when host-runtime ownership is uncertain.
- Never skip docs when behavior/guarantees changed.
- Keep plans minimal and merge-focused.
