---
name: runtime-path-perf
description: Review request-path integration overhead — wrapper churn, body copies from LoanedBuffer, codec layering, reflection paths, and context propagation cost.
disable-model-invocation: true
---

<!-- DO NOT EDIT. Generated from .agents/workflows/runtime-path-perf.md by agents_render.py
     (exeris-systems/exeris-agents; agents-md-schema.md rule 7). Edit the source. -->
Audit this change for request-path performance.

Hot-path discipline:
- No per-request wrapper DTO allocation in pure mode.
- No body copy from `LoanedBuffer` to `byte[]` / `InputStream` on the primary path — codecs operate on `MemorySegment` directly.
- `LoanedBuffer` ownership: handler must release or transfer; after `exchange.respond(response)` the engine owns the response body — caller must not release it.
- `HttpHandler.handle` must complete exactly once: respond OR throw `HttpException`, never both.
- Compatibility-mode allocation cost must be measured and documented, never silently applied to pure paths.
- `ScopedValue` for context — `ThreadLocal` banned on hot paths.

Change:
$ARGUMENTS

Please review:
1. Does this change introduce per-request wrapper DTO allocation in pure mode?
2. Does it copy body from `LoanedBuffer` to `byte[]` / `InputStream`?
3. Does it violate `LoanedBuffer` ownership?
4. Does `HttpHandler.handle` risk completing twice?
5. Does it introduce reflection-heavy paths on the primary request path?
6. Does context propagation use `ScopedValue`?
7. Does it introduce hidden fallback overhead?
8. Minimal correction if request-path discipline is at risk.
