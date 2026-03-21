# exeris-spring-docs-adr-check

## Purpose
Detect documentation and ADR drift against runtime ownership and mode reality.

## Drift Classes
- `NO_ACTION`
- `MINOR_DOC_UPDATE`
- `DOCS_UPDATE_REQUIRED`
- `ADR_IMPACT`
- `NEW_ADR_REQUIRED`

## Checks
1. Host-runtime wording is architecture-honest.
2. Pure Mode vs Compatibility Mode is explicit and consistent.
3. Compatibility guarantees are not over-claimed.
4. Module responsibility docs match implementation.
5. Major directional changes trigger ADR handling.

## Output
- drift class
- affected docs
- minimal documentation delta
- merge recommendation for docs/ADR timing
