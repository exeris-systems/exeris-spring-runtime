---
description: Review request-path integration overhead — wrapper/DTO churn, body copy from `LoanedBuffer`, codec layering, reflection-heavy paths, context propagation cost, hidden fallback overhead.
argument-hint: PR diff or request-path / web / tx change to audit
---

Audit this change for request-path performance.

Hot-path discipline (per repo `CLAUDE.md`):
- No per-request wrapper DTO allocation in pure mode.
- No body copy from `LoanedBuffer` to `byte[]` / `InputStream` on the primary path — codecs operate on `MemorySegment` directly.
- `LoanedBuffer` ownership: handler MUST release or transfer; after `exchange.respond(response)` the engine owns the response body — caller MUST NOT release it.
- `HttpHandler.handle` MUST complete exactly once: respond OR throw `HttpException`, never both.
- Compatibility-mode allocation cost MUST be measured and documented, never silently applied to pure-mode paths.
- `ScopedValue` for context — `ThreadLocal` banned on hot paths (except narrow compatibility-mode `SecurityContextHolder` bridge, cleared in `finally`).

Change:
$ARGUMENTS

Please review:
1. Does this change introduce per-request wrapper DTO allocation in pure mode?
2. Does it copy body from `LoanedBuffer` to `byte[]` / `InputStream`? Codecs MUST operate on `MemorySegment` directly.
3. Does it violate `LoanedBuffer` ownership (handler doesn't release / transfer; or caller releases response body after `exchange.respond`)?
4. Does `HttpHandler.handle` risk completing twice (respond + throw)?
5. Does it introduce reflection-heavy paths on the primary request path?
6. Does context propagation use `ScopedValue` (or compatibility-mode narrow `ThreadLocal` cleared in `finally`)?
7. Does it introduce hidden fallback overhead (e.g. silently routing to compatibility-mode codec on a pure-mode path)?
8. Is compatibility-mode allocation cost measured + documented?
9. Minimal correction if request-path discipline is at risk.

If the change materially affects request path, attach JMH micro evidence or note "performance review required" for downstream evidence.
