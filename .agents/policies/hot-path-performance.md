# Policy: Hot-Path Performance Discipline

Modules adjacent to the Exeris request path (`web`, `tx`, `data`) sit on the high-throughput hot path and must adhere to zero-copy memory and lifecycle discipline.

## The Rule

- **Zero-Copy Ingress**: Codecs and handlers must operate directly on `MemorySegment` slices. Never copy payload bodies from `LoanedBuffer` into `byte[]` or `InputStream` on the primary pure-mode path.
- **Zero Allocation Churn**: No per-request wrapper DTOs or unnecessary object allocation on the pure request path.
- **LoanedBuffer Ownership**:
  - The handler must explicitly release or transfer the inbound `LoanedBuffer`.
  - Once `exchange.respond(response)` is invoked, the HTTP engine assumes ownership of the response body buffer; the caller must NOT release it afterwards.
- **Single Completion Contract**: `HttpHandler.handle` must complete exactly once: either call `respond(...)` or throw an `HttpException`, never both.
- **Zero-Allocation Discipline (ADR-007 §4)**: No per-request heap buffer copies (`new byte[]`), wrapper DTOs, or intermediate payload copies on the request hot path. Codecs and bridges operate directly on `MemorySegment` slices.
- **Unscoped ThreadLocal Ban (ADR-007 §2)**: `ThreadLocal` is banned everywhere across all modules and source sets, not merely on hot paths. Context propagation is strictly through `ScopedValue`. `ThreadLocalRandom` is a distinct type and is not covered.
