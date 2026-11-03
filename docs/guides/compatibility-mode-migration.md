---
title: "Migrating a Spring MVC application to Compatibility Mode"
type: howto
visibility: public
owning-repo: exeris-spring-runtime
status: active
last-verified: 2026-11-01
---

# Migrating a Spring MVC application to Compatibility Mode

This guide moves an existing Spring MVC application, with `@RestController` classes, JSON bodies,
`@Valid` and `@ControllerAdvice`, onto the Exeris runtime in **Compatibility Mode**. The Exeris kernel
owns the socket and the request lifecycle. Your controllers run through the runtime's own MVC bridge.
There is no Tomcat, no `DispatcherServlet` and no servlet API.

The finished application is [`examples/compat-mvc`](../../examples/compat-mvc). Its test calls every
endpoint over a real socket, so the code below is the code that runs.

## Prerequisites

- An application that runs on Spring Boot 3.5.x or 4.1.x. The example uses
  `spring-boot-starter-parent` 3.5.14.
- JDK 25 or newer.
- The runtime BOM and the kernel implementation in `<dependencyManagement>`, as in
  [Getting started](getting-started.md#1-declare-the-dependencies).
- Your security configuration checked against
  [Spring Security in Compatibility Mode](../architecture/compat-spring-security-support.md).
  A `SecurityFilterChain` bean stops the application from starting (step 5).

## 1. Replace the web starter

Remove `spring-boot-starter-web`, together with any `spring-boot-starter-tomcat`,
`spring-boot-starter-jetty` or `spring-boot-starter-undertow`. Then declare:

<!-- example: compat-mvc/pom.xml#dependencies -->
```xml
<!-- spring-boot-starter, not spring-boot-starter-web: the web starter brings Tomcat and
     spring-webmvc, and Exeris owns ingress. -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter</artifactId>
</dependency>
<!-- Jackson, for JSON request and response bodies. -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-json</artifactId>
</dependency>
<!-- Bean Validation, for @Valid on a request body. -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-validation</artifactId>
</dependency>
<dependency>
    <groupId>eu.exeris</groupId>
    <artifactId>exeris-spring-boot-autoconfigure</artifactId>
</dependency>
<!-- Brings spring-web, which holds @RestController, @RequestMapping and the message
     converters. -->
<dependency>
    <groupId>eu.exeris</groupId>
    <artifactId>exeris-spring-runtime-web</artifactId>
</dependency>
<dependency>
    <groupId>eu.exeris</groupId>
    <artifactId>exeris-kernel-community</artifactId>
    <scope>runtime</scope>
</dependency>
```

- **Do not add `spring-webmvc`.** The bridge does not use it. The annotations your controllers
  import (`@RestController`, `@GetMapping`, `@RequestBody`, `@ExceptionHandler`, ...) are in
  `spring-web`, which `exeris-spring-runtime-web` brings at compile scope.
- **`spring-boot-starter-json` is required on Spring Boot 3.5.** `exeris-kernel-community` brings
  Jackson 3, and Spring Framework 6 has no converter for it. Without Jackson 2 on the classpath, the
  context fails to start with `A Jackson databind is on the classpath but no matching Spring JSON
  converter could be constructed`.
- **`spring-boot-starter-validation` is needed only for `@Valid`.** It brings `tomcat-embed-el`, which
  is the expression language library that Hibernate Validator uses for messages. It is not a server.

## 2. Select Compatibility Mode

<!-- example: compat-mvc/src/main/resources/application.properties#properties -->
```properties
# Exeris owns HTTP ingress: Spring Boot starts no web server of its own.
spring.main.web-application-type=none

# Serve @RestController beans. Unset, the runtime runs Pure Mode and serves only @ExerisRoute
# handlers.
exeris.runtime.web.mode=compatibility
```

`exeris.runtime.web.mode` accepts `pure` (the default) and `compatibility`. The mode applies to the
whole application. Set `exeris.runtime.network.port` and `exeris.runtime.subsystems` as in
[Getting started](getting-started.md#2-configure-the-runtime). See the
[Configuration reference](configuration-reference.md) for the other properties.

## 3. Keep your controllers

Controllers need no change. This controller from the example uses a class-level `@RequestMapping`, a
`@PathVariable`, a `@Valid @RequestBody` record and a `ResponseEntity`:

<!-- example: compat-mvc/src/main/java/eu/exeris/examples/compat/OrderController.java#controller -->
```java
@RestController
@RequestMapping("/orders")
public class OrderController {

    private final Map<Long, Order> orders = new ConcurrentHashMap<>();
    private final AtomicLong ids = new AtomicLong();

    @GetMapping("/{id}")
    public Order find(@PathVariable("id") long id) {
        Order order = orders.get(id);
        if (order == null) {
            throw new OrderNotFoundException(id);
        }
        return order;
    }

    @PostMapping
    public ResponseEntity<Order> create(@Valid @RequestBody CreateOrder request) {
        Order order = new Order(ids.incrementAndGet(), request.product(), request.quantity());
        orders.put(order.id(), order);
        return ResponseEntity.created(URI.create("/orders/" + order.id())).body(order);
    }
}
```

The request body is validated against its constraints:

<!-- example: compat-mvc/src/main/java/eu/exeris/examples/compat/CreateOrder.java#request -->
```java
public record CreateOrder(@NotBlank String product, @Positive int quantity) {
}
```

A failed `@Valid` check throws `MethodArgumentNotValidException`, the same type that Spring MVC throws.
`@ExceptionHandler` methods on the controller and on `@ControllerAdvice` or `@RestControllerAdvice`
beans handle it, and `@ResponseStatus` on a handler method sets the status:

<!-- example: compat-mvc/src/main/java/eu/exeris/examples/compat/ApiErrors.java#advice -->
```java
@RestControllerAdvice
public class ApiErrors {

    @ExceptionHandler(OrderNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Map<String, String> notFound(OrderNotFoundException ex) {
        return Map.of("error", ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, List<String>> invalid(MethodArgumentNotValidException ex) {
        List<String> fields = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField())
                .sorted()
                .toList();
        return Map.of("invalid", fields);
    }
}
```

Keep the explicit names in `@PathVariable("id")` and `@RequestParam("name")` unless you compile with
`-parameters`. The Spring Boot parent turns that flag on.

## 4. Run it and call it

```bash
mvn spring-boot:run
```

```bash
curl -i -H 'Content-Type: application/json' -d '{"product":"book","quantity":2}' http://localhost:8080/orders
```

```
HTTP/1.1 201 Created
Content-Type: application/json
Location: /orders/1
...

{"id":1,"product":"book","quantity":2}
```

`GET /orders/1` returns the same body with `200`. `GET /orders/999` returns `404` with
`{"error":"order 999 does not exist"}`. A body of `{"product":"","quantity":0}` returns `400` with
`{"invalid":["product","quantity"]}`.

## 5. Fix what does not carry over

The bridge supports a fixed set of Spring MVC features. The authoritative list is the feature support
matrix in [`phase-2-spring-compat.md`](../phases/phase-2-spring-compat.md#feature-support-matrix-corrected).

**Works unchanged:** `@RestController`, `@RequestMapping` with a class-level prefix, `@GetMapping`,
`@PostMapping`, `@PutMapping`, `@DeleteMapping`, `@PatchMapping`, `@PathVariable`, `@RequestParam`
(with enums and your `Converter<String, T>` beans), `@RequestHeader`, `@RequestBody` with JSON,
`@Valid` on `@RequestBody`, `ResponseEntity<T>` with status and headers, `@ExceptionHandler` on the
controller and on `@ControllerAdvice`, an `Authentication` parameter, and `HEAD` on a `GET` mapping.

**Change these before you switch:**

| In your application | On Exeris | What to do |
|:--|:--|:--|
| A `SecurityFilterChain` bean | The context fails to start. The chain could never run, because there is no servlet container and no `FilterChainProxy`. | Move authentication to the JWT resource server path and authorization to `@PreAuthorize`. Rewrite per-path rules as an `ExerisHttpSecurity` bean. See [Securing routes](securing-routes.md) and [Spring Security in Compatibility Mode](../architecture/compat-spring-security-support.md#startup-failures). |
| `HttpServletRequest`, `HttpServletResponse`, servlet `Filter` beans | Not on the classpath. | Read values through `@RequestHeader`, `@RequestParam` and `@PathVariable`. Move cross-cutting logic into a service or into `@ControllerAdvice`. |
| `DispatcherServlet`, `HandlerInterceptor`, `WebMvcConfigurer` | Not present. Interceptors and configurers are not called. | Move interceptor logic into the handler, a service, or an exception handler. |
| `@ResponseStatus` on a controller method | Ignored: the response is `200`. It works on `@ExceptionHandler` methods. | Return `ResponseEntity.status(...)` or `ResponseEntity.created(...)`. |
| A `String` return value | Written as a JSON string (`"plain"`) with `content-type: application/json`. | Return a record or a `ResponseEntity` with an explicit content type if clients expect plain text. |
| Spring MVC's default error statuses | A wrong HTTP method answers `404`, not `405`. A malformed JSON body, an unsupported `Content-Type` or a missing `@RequestParam` answers `500`, not `400` or `415`. | Add `@ExceptionHandler` methods for the failures your clients cause. An exception that no handler matches is a body-less `500`, logged at `ERROR` by `ExerisCompatDispatcher`. |
| `Model`, `ModelAndView`, views, `MultipartFile` | Not supported. | Keep these endpoints on their existing stack. |
| `RouterFunction`, Spring Cloud Gateway | Out of scope ([ADR-021](../adr/ADR-021-gateway-class-workloads-out-of-compatibility-scope.md)). | Keep these services on their existing stack. |
| `ServerHttpRequest#getLocalAddress`, `getRemoteAddress` | `getLocalAddress` is `null`. `getRemoteAddress` comes only from `Forwarded`, `X-Forwarded-For` or `X-Real-IP` headers. | Read the client address from your proxy's headers. |
| CORS and security headers | Neither handles them. | Handle them at your edge proxy. |

Check `consumes`, `produces`, `headers` and `params` on your mappings with a test. The matrix lists
them as partial support.

## 6. Know the cost

Compatibility Mode allocates more per request than Pure Mode. Each request gets adapter objects for
the Spring request and response, argument resolution runs, and Jackson builds an object graph. The
request body is also copied from the kernel buffer to the heap before Jackson reads it.

`ExerisCompatAllocationCostReportTest` dispatched the same empty-body `GET` through both paths. It
measured about 176 bytes per dispatch for Pure Mode and about 5095 bytes for Compatibility Mode,
about 29 times as much. That report is informational: no budget limits the Compatibility Mode number,
and JSON bodies add to it.

## 7. Decide which routes move to Pure Mode

Each application runs one mode. In Compatibility Mode the runtime serves `@RestController` methods
only. A Pure Mode `@ExerisRoute` handler in the same application is not registered, and its path
answers `404`. To move a route to Pure Mode, you therefore move a whole application, or you split the
route into its own Pure Mode application.

Move a route when all of these are true:

- It is on a hot path, and allocation per request or tail latency matters to you.
- Its handler is small enough to rewrite as an `ExerisRequestHandler` (see
  [Getting started](getting-started.md#3-write-the-application-and-a-handler)).
- It does not depend on the MVC features from step 5, such as validation through `@Valid` or advice.

Keep a route in Compatibility Mode when it is a low-traffic admin or CRUD endpoint, or when it relies
on Spring MVC argument resolution, validation and exception mapping.

## Where this does not apply

- **New applications.** Start in Pure Mode with [Getting started](getting-started.md). Compatibility
  Mode exists for code that already uses Spring MVC.
- **Reactive applications.** WebFlux, `ServerWebExchange` and Reactor are not part of either mode.
- **Applications whose authorization lives only in a `SecurityFilterChain`.** They do not migrate
  until the rules are rewritten (step 5).
- **Gateway workloads, server-side views and multipart uploads.** Keep them on their existing stack.

## Related

- [Securing routes](securing-routes.md): per-path rules that the kernel enforces before your code runs.
- [Transactions and JDBC](transactions-and-jdbc.md): `@Transactional` and a `DataSource` on Exeris.
- [Configuration reference](configuration-reference.md): every `exeris.runtime.*` property.
- [ADR-011](../adr/ADR-011-pure-mode-vs-compatibility-mode.md): why the two modes exist.
