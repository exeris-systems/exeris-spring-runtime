# exeris-spring-ownership-boundary-review

## Purpose
Check whether architecture and implementation preserve runtime ownership truth:

- Spring = application framework
- Exeris = runtime owner

## Key Questions
1. Who owns ingress?
2. Who owns request lifecycle and cleanup?
3. Is backpressure/load-shedding ownership explicit?
4. Is provider discovery still Exeris-canonical (not IoC inversion)?
5. Is the system still honestly host-runtime, or only helper-library integration?

## Risk Flags
- ownership inversion to servlet/reactive/JDBC-first runtime
- split-brain lifecycle
- fake host-runtime claims
- hidden fallback path ownership outside Exeris

## Output
- `ownership_status`: `EXERIS_OWNS_RUNTIME | OWNERSHIP_AT_RISK | LEGACY_RUNTIME_OWNS_PATH`
- primary risk summary
- minimal safe direction (1-2 steps)
