# exeris-spring-task-classifier

## Purpose
Classify incoming work for `exeris-spring-runtime` into one primary task class and one mode-impact assessment.

## Output
- `task_class`: `ARCHITECTURE | INTEGRATION_IMPLEMENTATION | VERIFICATION | DOCS_ADR | PERFORMANCE | MULTI_DOMAIN`
- `mode_impact`: `PURE_MODE | COMPATIBILITY_MODE | MIXED | UNCLEAR`
- `confidence`: `HIGH | MEDIUM | LOW`
- `why`: one short rationale

## Rules
1. If ownership model, The Wall, mode semantics, or module placement is ambiguous -> prefer `ARCHITECTURE` or `MULTI_DOMAIN`.
2. If code edits are clearly scoped and architecture intent is known -> `INTEGRATION_IMPLEMENTATION`.
3. If evidence strategy/testing depth is the core ask -> `VERIFICATION`.
4. If docs claims, ADR impact, or wording honesty is central -> `DOCS_ADR`.
5. If request-path cost/overhead is the central ask -> `PERFORMANCE`.

## Mode Decision Heuristics
- `PURE_MODE`: Exeris-owned ingress/request lifecycle remains primary.
- `COMPATIBILITY_MODE`: Spring compatibility semantics are intentionally enabled with trade-offs.
- `MIXED`: both paths touched.
- `UNCLEAR`: insufficient evidence in current request.
