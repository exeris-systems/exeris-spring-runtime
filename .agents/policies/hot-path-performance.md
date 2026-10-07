# Policy: Hot-Path Performance Discipline

Modules adjacent to the Exeris request path (`web`, `tx`, `data`) sit on the high-throughput hot path and must adhere to zero-copy memory and lifecycle discipline.

## The Rule

- **Zero-Copy Ingress**: Codecs and handlers must operate directly on `MemorySegment` slices. Never copy payload bodies from `LoanedBuffer` into `byte[]` or `InputStream` on the primary pure-mode path.
- **Zero Allocation Churn**: No per-request wrapper DTOs or unnecessary object allocation on the pure request path.
- **LoanedBuffer Ownership**:
  - The handler must explicitly release or transfer the inbound `LoanedBuffer`.
  - Once `exchange.respond(response)` is invoked, the HTTP engine assumes ownership of the response body buffer; the caller must NOT release it afterwards.
- **Single Completion Contract**: `HttpHandler.handle` must complete exactly once: either call `respond(...)` or throw an `HttpException`, never both.
- **No Hidden Compatibility Cost**: Compatibility mode memory and allocation overhead must be measured and documented, and must never bleed into pure-mode execution paths.
- **Context Access**: Hot paths must access context via `ScopedValue` rather than `ThreadLocal`.
