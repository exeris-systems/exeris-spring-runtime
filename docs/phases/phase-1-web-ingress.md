# Phase 1: Exeris-Owned Web Ingress (Pure Mode)

**Status:** Not Started  
**Depends on:** Phase 0 (bootstrap POC complete, Wall verified)  
**Milestone:** M1  
**Mode:** Pure Mode only

---

## Goal

> Request enters through Exeris. Spring handles business logic. Response exits through Exeris.
> No Tomcat, no Netty, no Servlet API, no Spring MVC DispatcherServlet.

This is the proof that Exeris genuinely "takes over" from the application framework's usual
transport layer. If this works cleanly, the host-runtime claim is legitimate.

---

## Deliverables

| # | Deliverable | Module | Status |
|:-:|:------------|:-------|:-------|
| 1 | `ExerisRouteRegistry` — maps URI patterns to Spring handler beans | `web` | Not Started |
| 2 | `ExerisServerRequest` — thin view over `HttpRequest` (no body copy) | `web` | Not Started |
| 3 | `ExerisServerResponse` / `ExerisResponseBuilder` — builds `HttpResponse` | `web` | Not Started |
| 4 | `ExerisHttpDispatcher implements HttpHandler` — the primary bridge | `web` | Not Started |
| 5 | `ExerisHttpHandlerAutoConfiguration` — wires dispatcher into `HttpServerEngine` | `autoconfigure` | Not Started |
| 6 | `ExerisJsonCodec` — minimal JSON body support using `LoanedBuffer` | `web` | Not Started |
| 7 | `ExerisErrorMapper` — exception → HTTP status + body | `web` | Not Started |
| 8 | `@ExerisRoute` — annotation for pure mode handler registration | `web` | Not Started |
| 9 | Runtime integration test: full HTTP round-trip | `web` (test) | Not Started |
| 10 | Performance smoke test: alloc rate, latency baseline | `web` (test) | Not Started |

---

## Pure Mode Programming Model

In Phase 1, the developer writes handlers as Spring beans implementing a thin contract:

```java
@Component
@ExerisRoute(method = HttpMethod.GET, path = "/hello")
public class HelloHandler implements ExerisRequestHandler {

    @Override
    public ExerisServerResponse handle(ExerisServerRequest request) {
        return ExerisServerResponse.ok()
            .contentType(MediaType.TEXT_PLAIN)
            .body("Hello from Exeris");
    }
}
```

This is NOT `@RestController`. There is no `@RequestMapping` dispatch in Phase 1.
The `@ExerisRoute` annotation is scanned at startup and registered into `ExerisRouteRegistry`.
This is intentionally narrow — the explicit constraint on compatibility surface.

**Important:** `ExerisRequestHandler` is a contract defined in `exeris-spring-runtime-web`.
It is NOT a kernel SPI type. It is the Spring-facing application extension point.
The kernel SPI extension point is `HttpHandler` — implemented by `ExerisHttpDispatcher`.

---

## Request Flow (Pure Mode)

```
TCP/QUIC byte stream arrives at Exeris transport layer
    → Exeris HttpServerEngine: HTTP/1.1 or HTTP/2 framing
    → HttpExchange created (off-heap, LoanedBuffer body)
    → PAQS scheduler: priority check, backpressure gate
    → Virtual Thread spawned (1 per request)

Virtual Thread executes:
    → ExerisHttpDispatcher.handle(exchange)
        → ExerisRouteRegistry.resolve(method, path)
        → ExerisServerRequest wraps HttpRequest (no copy)
        → handler.handle(request) → ExerisServerResponse
        → ExerisResponseBuilder builds HttpResponse (LoanedBuffer body)
        → exchange.respond(response)   ← ownership of LoanedBuffer transferred to engine
    → Virtual Thread completes

Exeris engine writes response bytes to wire
    → LoanedBuffer released (off-heap, ref-counted)
```

---

## Key Design Decisions

### No body copy in primary path

`ExerisServerRequest.body()` MUST return a view backed by the kernel's `LoanedBuffer.segment()`
without copying to `byte[]`. Codec implementations (JSON, binary) must work with `MemorySegment`
directly.

If a specific use case requires `byte[]`, it must be in a dedicated method (`bodyBytes()`)
that explicitly documents the heap allocation.

### No ThreadLocal

Context propagation within the request scope must use `ScopedValue`, not `ThreadLocal`.
`ExerisRequestContext` will hold per-request state (trace ID, principal placeholder, locale, etc.)
and be bound as a `ScopedValue` by `ExerisHttpDispatcher` before invoking the handler.

### No DispatcherServlet

`ExerisHttpDispatcher` does NOT delegate to Spring's `DispatcherServlet`. It uses
`ExerisRouteRegistry` for routing. Annotation-based routing (`@RequestMapping`) is Phase 2,
compatibility mode only.

### Serialisation choices

Phase 1 uses a minimal codec approach. Two initial codecs:
- `ExerisJsonCodec`: JSON serialization via Jackson, operating on `MemorySegment`
- `ExerisTextCodec`: `text/plain` direct write

Object mapper allocation (Jackson `ObjectMapper`) must be a pre-constructed singleton,
not allocated per request.

### Error handling

`ExerisErrorMapper` translates exceptions to HTTP responses:
- `HttpException` (kernel) → mapped HTTP status
- `RuntimeException` (unhandled) → 500 with safe error body
- No stack traces in response bodies in production

---

## `ExerisHttpDispatcher` Contract

```java
package eu.exeris.spring.runtime.web;

import eu.exeris.kernel.spi.http.HttpExchange;
import eu.exeris.kernel.spi.http.HttpHandler;
import eu.exeris.kernel.spi.exceptions.http.HttpException;

public final class ExerisHttpDispatcher implements HttpHandler {

    private final ExerisRouteRegistry routeRegistry;
    private final ExerisErrorMapper errorMapper;

    public ExerisHttpDispatcher(ExerisRouteRegistry routeRegistry,
                                 ExerisErrorMapper errorMapper) {
        this.routeRegistry = routeRegistry;
        this.errorMapper = errorMapper;
    }

    @Override
    public void handle(HttpExchange exchange) throws HttpException {
        var request = new ExerisServerRequest(exchange.request());
        try {
            var handler = routeRegistry.resolve(request.method(), request.path());
            var response = handler.handle(request);
            exchange.respond(response.toKernelResponse());
        } catch (HttpException ex) {
            exchange.respond(errorMapper.map(ex));
            throw ex;
        } catch (Exception ex) {
            exchange.respond(errorMapper.mapUnhandled(ex));
        }
    }
}
```

Note: no `synchronized`, no `ThreadLocal`, no heap allocation of wrappers that outlive
the request scope on the primary path.

---

## Autoconfiguration Wiring

`ExerisHttpHandlerAutoConfiguration` (in `exeris-spring-boot-autoconfigure`):
- Detects `exeris-spring-runtime-web` on classpath
- Creates `ExerisRouteRegistry` from all `@ExerisRoute`-annotated beans
- Creates `ExerisHttpDispatcher` bean
- Registers dispatcher with `HttpServerEngine` during `ExerisRuntimeLifecycle.start()`

Activation condition: `@ConditionalOnClass(ExerisHttpDispatcher.class)` and
`@ConditionalOnProperty("exeris.runtime.enabled", matchIfMissing = true)`.

---

## Integration Test Requirements

**Test: `ExerisWebIntegrationTest`**
**Module:** `exeris-spring-runtime-web` (test scope)

Setup: Spring context with `ExerisRuntimeAutoConfiguration` + `ExerisHttpHandlerAutoConfiguration`.
Kernel driver: real community transport provider on localhost ephemeral port.

Verifies:
1. `GET /hello` returns HTTP 200 with correct body.
2. `GET /missing` returns HTTP 404.
3. Handler exception is mapped to correct HTTP 500.
4. Connection closes cleanly after response.
5. Concurrent requests (10 simultaneous) all succeed correctly.
6. Application stops cleanly with in-flight request drain.

**Performance smoke test: `ExerisWebAllocSmokeTest`**

Verifies:
1. Per-request heap allocation rate does not exceed the 65 B/req threshold from the
   kernel performance contract (target: 0 B/req for pure mode in-process routing).
2. P99 latency for 1KB payload response < 500 µs on L0 developer hardware.

---

## Exit Criteria

Phase 1 is complete when:

1. A Spring application starts without Tomcat/Netty/Jetty/Undertow as ingress.
2. HTTP request reaches Exeris transport → `ExerisHttpDispatcher` → Spring handler bean.
3. HTTP response exits through Exeris transport.
4. `WallIntegrityTest` still passes (The Wall remains intact).
5. `ExerisWebIntegrationTest` passes all scenarios including concurrent.
6. `ExerisWebAllocSmokeTest` confirms allocation and latency targets.
7. No `jakarta.servlet.*` imports in module compile scope (verified by `mvn dependency:tree`).
8. No `io.netty.*` imports on effective classpath (verified by enforcer rules).

---

## Risks

| Risk | Mitigation |
|:-----|:-----------|
| `HttpServerEngine.start()` API shape not yet final in 0.5.0-SNAPSHOT | Coordinate with kernel; create thin facade if needed, documented in integration seams doc |
| Body `LoanedBuffer` lifecycle: handler forgets to release | Verify in integration test; dispatcher tracks release on error paths |
| `@ExerisRoute` scanning conflicts with existing `@RequestMapping` beans | Guard with `@ConditionalOnPureMode`; compatibility mode handler registration is separate |
| Serialization path creates heap objects per-request in Jackson | Pre-allocate codec instances; profile allocation in smoke test |
| Handler bean lookup in `ExerisRouteRegistry` adds latency under concurrent load | Pre-compute route map at startup; route resolution must be O(1) read from an immutable structure |
