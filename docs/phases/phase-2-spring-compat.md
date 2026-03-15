# Phase 2: Spring MVC Compatibility Bridge (Opt-In)

**Status:** Not Started  
**Depends on:** Phase 1 complete and exit criteria verified  
**Milestone:** M2  
**Mode:** Compatibility Mode — opt-in, never default

---

## Goal

Allow developers to write `@RestController` + `@RequestMapping` beans and have them
dispatched through the Exeris request path — **without reintroducing Tomcat, Netty, or
DispatcherServlet as the canonical runtime path**.

Compatibility mode is:
- an additive layer on top of the Exeris request path from Phase 1
- always explicitly activated: `exeris.runtime.web.mode=compatibility`
- never the default
- not a replacement for pure mode
- explicitly inferior in allocation profile to pure mode

---

## What Phase 2 Enables

| Feature | Supported | Notes |
|:--------|:----------|:------|
| `@RestController` + `@RequestMapping` | Yes (scoped subset) | |
| `@GetMapping`, `@PostMapping`, etc. | Yes | |
| `@PathVariable`, `@RequestParam` | Yes | Limited to string/primitives initially |
| `@RequestBody` (JSON) | Yes | With known heap allocation overhead |
| `@ResponseBody` (JSON) | Yes | With known heap allocation overhead |
| `@ResponseStatus` | Yes | |
| `@ExceptionHandler` | Yes | Scoped to controller classes |
| `@ControllerAdvice` | Partial | Only `@ExceptionHandler` methods |
| `@RequestHeader` | Yes | |
| `HttpServletRequest` / `HttpServletResponse` | No | Banned in all modes |
| `MultipartFile` | No | Deferred, possible Phase 3+ |
| `ResponseEntity<T>` | Yes | |
| `Model` / `ModelAndView` | No | View resolution not supported |
| `WebMvcConfigurer` | Partial | Limited interceptor support |
| `HandlerInterceptor` | Partial | `preHandle` and `afterCompletion` only |

---

## Architecture: What Changes vs Phase 1

Phase 1 path:
```
HttpExchange → ExerisHttpDispatcher → ExerisRouteRegistry → ExerisRequestHandler
```

Phase 2 compatibility path (opt-in):
```
HttpExchange → ExerisCompatDispatcher → ExerisSpringMvcBridge
                                            → RequestMappingHandlerMapping (Spring)
                                            → RequestMappingHandlerAdapter (Spring)
                                            → @RestController method
                                            → return value → HttpResponse
```

The `ExerisCompatDispatcher` replaces `ExerisHttpDispatcher` when `mode=compatibility`.
Both modes cannot be active simultaneously for the same route unless explicitly configured.

---

## Key Design Constraints

### DispatcherServlet is NOT used

Instead, `ExerisSpringMvcBridge` uses Spring's handler infrastructure directly:
- `RequestMappingHandlerMapping`: discovers `@RequestMapping` methods
- `RequestMappingHandlerAdapter`: invokes handler methods with argument resolution
- No `DispatcherServlet`, no `ViewResolver`, no `ModelAndView` propagation

The bridge constructs a `ServerHttpRequest` / `ServerHttpResponse` adapter (from `spring-web`,
not from servlet API) to feed to Spring's handler adapter.

### Servlet API remains banned

`jakarta.servlet.*` must NOT appear in any compatibility mode code path. Spring's
`RequestMappingHandlerAdapter` can work without servlet API if the request/response model
is provided via `spring-web`'s `ServerHttpRequest` / `ServerHttpResponse` interfaces.

### ThreadLocal isolation

Compatibility mode may bind Spring context holders for the duration of a handler call:
```
invoke handler:
    bind SecurityContextHolder (if SecurityContext present in ScopedValue)
    bind LocaleContextHolder
    → handler.invoke()
    clear SecurityContextHolder
    clear LocaleContextHolder
```

This `ThreadLocal` binding must be:
- in `eu.exeris.spring.runtime.web.compat.context.ExerisThreadLocalBridge`
- executed within the virtual thread's scope only
- cleared deterministically in `finally` on both normal and exceptional paths

### Heap allocation acknowledgement

JSON body deserialization in compatibility mode WILL result in heap allocations.
The known minimum:
- Jackson `ObjectMapper.readValue()` → object graph on heap
- Argument resolution → wrapper instances per request

These allocations must be measured and documented. Compatibility mode must NOT claim
zero-heap performance. Pure mode remains the performance reference.

---

## Key Classes (Compatibility Mode)

All compatibility classes must reside in `eu.exeris.spring.runtime.web.compat.*`.

### `ExerisCompatDispatcher`
Extends or replaces `ExerisHttpDispatcher` in compatibility mode.
Delegates to `ExerisSpringMvcBridge` for `@RequestMapping` handler lookup.

### `ExerisSpringMvcBridge`
Owns `RequestMappingHandlerMapping` and `RequestMappingHandlerAdapter`.
Translates `ExerisServerRequest` → `ServerHttpRequest` (spring-web).
Translates handler return value → `ExerisServerResponse`.

### `ExerisMvcServerHttpRequest`
Implements `org.springframework.http.server.ServerHttpRequest`.
Backed by `HttpRequest` (Exeris SPI) — no `HttpServletRequest` involved.
Body backed by `LoanedBuffer` — copying only occurs when `getBody()` is called by the adapter.

### `ExerisMvcServerHttpResponse`
Implements `org.springframework.http.server.ServerHttpResponse`.
Accumulates response headers and status.
On `flush()`: builds `HttpResponse` with `LoanedBuffer` body and calls `exchange.respond()`.

### `ExerisCompatAutoConfiguration`
Conditional activation class:
```java
@Configuration
@ConditionalOnClass(RequestMappingHandlerMapping.class)
@ConditionalOnProperty("exeris.runtime.web.mode", havingValue = "compatibility")
public class ExerisCompatAutoConfiguration { ... }
```

---

## Test Requirements

**Test: `ExerisCompatMvcIntegrationTest`**

Scenarios:
1. `@GetMapping("/hello") String hello()` returns 200 with expected body.
2. `@PostMapping("/echo") @RequestBody Payload p` returns echoed JSON.
3. `@PathVariable` extraction works correctly.
4. `@ExceptionHandler` in the controller handles thrown exceptions.
5. `@ControllerAdvice` exception handler handles cross-controller exceptions.
6. Pure mode route continues to work alongside compatibility mode route.

**Heap allocation comparison test:**

Measures allocation rate for:
- Pure mode: `ExerisRequestHandler.handle()` returning a plain string body.
- Compatibility mode: `@RestController` method returning a `String`.

Documents the measured difference. There is no pass/fail on this test — it generates the
"compatibility cost" report.

**Architecture guard: compatibility isolation test:**

Verifies:
1. No `jakarta.servlet.*` import in any class under `eu.exeris.spring.runtime.web`.
2. All `ThreadLocal` bindings in compatibility mode are inside `*.compat.context.*`.
3. `ExerisHttpDispatcher` (pure mode class) does not import any `*.compat.*` class.

---

## Activation Configuration

```yaml
# application.yaml to activate compatibility mode
exeris:
  runtime:
    web:
      mode: compatibility
```

Pure mode (default):
```yaml
exeris:
  runtime:
    web:
      mode: pure  # default, no explicit config needed
```

---

## Exit Criteria

Phase 2 is complete when:

1. A `@RestController` with `@GetMapping` and `@PostMapping` is dispatched correctly.
2. JSON request/response body works.
3. `@ExceptionHandler` is invoked for handler exceptions.
4. No `jakarta.servlet.*` on the classpath in compatibility mode.
5. Compatibility mode heap allocation report is generated and documented.
6. Pure mode allocation baseline is unchanged (no regression).
7. Architecture guard: `ExerisHttpDispatcher` has zero imports from `*.compat.*`.
8. Phase 1 integration tests continue to pass unchanged.

---

## Risks

| Risk | Mitigation |
|:-----|:-----------|
| `RequestMappingHandlerAdapter` requires `ApplicationContext` initialization order | Pre-initialize adapter in `ExerisCompatAutoConfiguration` after context refresh |
| Body deserialization pulling in large Jackson dependency tree | Jackson is already on classpath via spring-boot; no additional dependency needed |
| `ServerHttpRequest` abstraction inadequate for some Spring argument resolvers | Scope supported resolvers to a whitelist; document unsupported patterns |
| `ThreadLocal` bridge not cleared on exception | `finally` block enforcement; tested via deliberately thrown exception in integration test |
| Compatibility mode accidentally activated in production (performance regression) | Requires explicit property; `@ConditionalOnProperty` with no default value |
