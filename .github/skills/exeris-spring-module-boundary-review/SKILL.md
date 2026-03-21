# exeris-spring-module-boundary-review

## Purpose
Enforce module responsibility boundaries for:
- `exeris-spring-boot-autoconfigure`
- `exeris-spring-runtime-web`
- `exeris-spring-runtime-tx`
- `exeris-spring-runtime-data`
- `exeris-spring-runtime-actuator`

## Anti-Patterns to Flag
- web/runtime logic in autoconfigure
- tx semantics in actuator
- persistence ownership in web
- compatibility sludge spread across modules
- runtime orchestration moved into boot layer

## Output
- boundary assessment per touched module
- boundary violations (if any)
- relocation recommendation
