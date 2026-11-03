---
title: "Getting started: your first Pure Mode application"
type: tutorial
visibility: public
owning-repo: exeris-spring-runtime
status: active
last-verified: 2026-11-01
---

# Getting started: your first Pure Mode application

You will build a Spring Boot application whose HTTP ingress is owned by the Exeris kernel: no Tomcat,
no Netty, no servlet API. Spring wires your beans; Exeris owns the socket, the request lifecycle and
the shutdown. At the end, `curl http://localhost:8080/hello` answers from a Spring bean.

The finished application is [`examples/pure-mode-hello`](../../examples/pure-mode-hello). CI builds it
and calls it over a real socket on every commit, so the code below is the code that runs.

## Prerequisites

- **JDK 25** or newer. No `--enable-preview` flag is needed anywhere.
- **Maven 3.9** or newer.
- **Spring Boot 3.5.x or 4.1.x.** This tutorial uses `spring-boot-starter-parent` 3.5.14.

## 1. Declare the dependencies

Import the runtime's BOM to align its modules. It manages this project's modules and nothing else,
so your Spring Boot line keeps its own versions.

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>eu.exeris</groupId>
            <artifactId>exeris-spring-runtime-bom</artifactId>
            <version>0.8.0</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
        <dependency>
            <groupId>eu.exeris</groupId>
            <artifactId>exeris-kernel-community</artifactId>
            <version>0.13.1</version>
        </dependency>
    </dependencies>
</dependencyManagement>
```

Then the modules themselves:

<!-- example: pure-mode-hello/pom.xml#dependencies -->
```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter</artifactId>
</dependency>
<dependency>
    <groupId>eu.exeris</groupId>
    <artifactId>exeris-spring-boot-autoconfigure</artifactId>
</dependency>
<dependency>
    <groupId>eu.exeris</groupId>
    <artifactId>exeris-spring-runtime-web</artifactId>
</dependency>
<!-- The kernel implementation that owns the socket. The runtime compiles against the
     kernel SPI only; a deployment chooses the implementation. -->
<dependency>
    <groupId>eu.exeris</groupId>
    <artifactId>exeris-kernel-community</artifactId>
    <scope>runtime</scope>
</dependency>
```

`exeris-kernel-community` is the kernel implementation. The runtime compiles against the kernel's SPI
only, so the implementation is yours to choose and to version; the BOM does not pin it.

## 2. Configure the runtime

<!-- example: pure-mode-hello/src/main/resources/application.properties#properties -->
```properties
# Exeris owns HTTP ingress: Spring Boot starts no web server of its own.
spring.main.web-application-type=none

# The port the kernel listens on.
exeris.runtime.network.port=8080

# Boot only what a request path needs. The default set includes persistence, which needs a
# reachable database.
exeris.runtime.subsystems=memory,transport,http
```

- **`spring.main.web-application-type=none`** — Spring Boot starts no embedded server. If a servlet
  container is on the classpath and this line is missing, two servers compete for the process.
- **`exeris.runtime.subsystems`** — the kernel subsystems to boot. Unset, the kernel boots its whole
  Community set, persistence included, and persistence fails the boot when no database is reachable.
  A request path needs `memory`, `transport` and `http`.

## 3. Write the application and a handler

The application class is an ordinary Spring Boot application:

<!-- example: pure-mode-hello/src/main/java/eu/exeris/examples/hello/HelloApplication.java#application -->
```java
@SpringBootApplication
public class HelloApplication {

    public static void main(String[] args) {
        SpringApplication.run(HelloApplication.class, args);
    }
}
```

A Pure Mode handler is a Spring bean that implements `ExerisRequestHandler` and declares its route
with `@ExerisRoute`:

<!-- example: pure-mode-hello/src/main/java/eu/exeris/examples/hello/HelloHandler.java#handler -->
```java
@Component
@ExerisRoute(method = HttpMethod.GET, path = "/hello")
public class HelloHandler implements ExerisRequestHandler {

    @Override
    public ExerisServerResponse handle(ExerisServerRequest request) {
        return ExerisServerResponse.ok().body("Hello from Exeris");
    }
}
```

One bean serves one route. The request view is valid only while `handle` runs, so do not keep it or
its body after returning.

## 4. Run it

```bash
mvn spring-boot:run
```

```bash
curl -i http://localhost:8080/hello
```

```
HTTP/1.1 200 OK
...
Hello from Exeris
```

A path no handler declares answers `404`. An exception escaping a handler answers `500`.

## 5. Stop it

Press Ctrl-C, or send `SIGTERM`. Spring closes the context and the runtime stops the kernel after the
beans that still use it: ingress closes, in-flight requests finish, then the subsystems stop.
`exeris.runtime.shutdown.timeout-seconds` (default `30`) bounds how long Spring waits for that.

## Where this does not apply

- **Existing `@RestController` code.** Pure Mode does not run Spring MVC. To serve existing
  controllers on Exeris, see [Migrating a Spring MVC application](compatibility-mode-migration.md).
- **Servlet filters, `HttpServletRequest`, `DispatcherServlet`.** None of them exist on this path.
- **Reactive stacks.** WebFlux and Reactor are not part of either mode.

## Next steps

- [Securing routes](securing-routes.md) — per-path rules the kernel enforces before your code runs.
- [Configuration reference](configuration-reference.md) — every `exeris.runtime.*` property.
