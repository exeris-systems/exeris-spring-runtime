---
title: "Health, metrics and shutdown in production"
type: howto
visibility: public
owning-repo: exeris-spring-runtime
status: active
last-verified: 2026-11-03
---

# Health, metrics and shutdown in production

This guide is about **Pure Mode**. It shows how to serve Spring Boot Actuator health on the kernel's
listener, wire Kubernetes probes to it, record Micrometer metrics, read the kernel's logs, and size the
shutdown so a rolling restart finishes before the orchestrator kills the pod. It also shows how to
read a start that fails.

The finished application is [`examples/operations`](../../examples/operations). CI boots it, calls its
health routes over a real socket, asserts that an event emitted by a handler moves the
`exeris.events.*` gauges, asserts that a refused start fails at once with the kernel's error code, and
asserts that closing the context stops the kernel and frees the port.

## Prerequisites

- A Pure Mode application that already runs. If you have none, start with
  [Getting started](getting-started.md).
- Spring Boot 3.5.x. The example uses `spring-boot-starter-parent` 3.5.14 and kernel
  `exeris-kernel-community` 0.13.1.

## 1. Add the actuator modules

<!-- example: operations/pom.xml#dependencies -->
```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
<dependency>
    <groupId>eu.exeris</groupId>
    <artifactId>exeris-spring-boot-autoconfigure</artifactId>
</dependency>
<dependency>
    <groupId>eu.exeris</groupId>
    <artifactId>exeris-spring-runtime-web</artifactId>
</dependency>
<!-- Registers the Exeris runtime health indicator and the Micrometer binder. -->
<dependency>
    <groupId>eu.exeris</groupId>
    <artifactId>exeris-spring-runtime-actuator</artifactId>
</dependency>
<dependency>
    <groupId>eu.exeris</groupId>
    <artifactId>exeris-kernel-community</artifactId>
    <scope>runtime</scope>
</dependency>
```

`exeris-spring-runtime-actuator` adds a health contributor named **`exerisRuntimeSpringBoot`**. It is
`UP` while `ExerisRuntimeLifecycle` is running and `DOWN` with `reason: Exeris runtime not running`
before the kernel has started and after it has stopped. Use that name wherever Spring Boot asks for a
contributor name, for example in a health group.

## 2. Exclude the Neo4j auto-configuration

`exeris-kernel-community` brings `neo4j-java-driver`, and with it netty. Spring Boot sees the driver
and auto-configures a Neo4j `Driver` bean, and Actuator adds a `neo4j` health contributor for it. With
no graph database running, that contributor is `DOWN`, so the aggregate health is `DOWN` and every
probe built on it fails.

If the application does not use graph, exclude the auto-configuration:

<!-- example: operations/src/main/resources/application.properties#neo4j -->
```properties
# exeris-kernel-community brings the Neo4j driver. Without this line Spring Boot creates a
# Neo4j Driver and a neo4j health contributor that reports DOWN when no graph database runs.
spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.neo4j.Neo4jAutoConfiguration
```

The example's tests assert that no `Driver` bean exists and that the health has no `neo4j`
component. If the application does use graph, keep the driver and see
[Graph queries](graph-queries.md).

## 3. Serve health on the kernel listener

Pure Mode runs with `spring.main.web-application-type=none`, so Spring Boot starts no web server and
serves no `/actuator/*` endpoint over HTTP. The Actuator beans still exist. Serve the ones you need
from `@ExerisRoute` handlers.

Render a health result as a status code and a small JSON body. `UP` answers `200`; any other status
answers `503`, which is what a probe needs:

<!-- example: operations/src/main/java/eu/exeris/examples/operations/HealthResponses.java#render -->
```java
/** Renders an actuator health result the way a probe reads it: the status code decides. */
final class HealthResponses {

    private HealthResponses() {
    }

    static ExerisServerResponse render(HealthComponent health) {
        Status status = health == null ? Status.UNKNOWN : health.getStatus();
        HttpStatus code = Status.UP.equals(status) ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE;
        return ExerisServerResponse.status(code)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"status\":\"" + status.getCode() + "\"}");
    }
}
```

Then one handler per path. The aggregate health:

<!-- example: operations/src/main/java/eu/exeris/examples/operations/HealthHandler.java#handler -->
```java
/** Serves the aggregate actuator health on the kernel listener. */
@Component
@ExerisRoute(method = HttpMethod.GET, path = "/actuator/health")
public class HealthHandler implements ExerisRequestHandler {

    private final HealthEndpoint health;

    public HealthHandler(HealthEndpoint health) {
        this.health = health;
    }

    @Override
    public ExerisServerResponse handle(ExerisServerRequest request) {
        return HealthResponses.render(health.health());
    }
}
```

Inject `HealthEndpoint` through the constructor like any other bean. The handler is routed even though
the endpoint depends on the Exeris runtime indicator, and so on the runtime that serves the route.

The liveness and readiness handlers are the same class with a different path and
`healthForPath("liveness")` or `healthForPath("readiness")`; see
[`ReadinessHandler`](../../examples/operations/src/main/java/eu/exeris/examples/operations/ReadinessHandler.java).

```bash
curl -i http://localhost:8080/actuator/health
```

```
HTTP/1.1 200 OK
Content-Type: application/json
...
{"status":"UP"}
```

**The kernel's own `/health` routes.** The Community kernel has built-in `/health`, `/health/live` and
`/health/ready` routes, but it installs them only when no application handler is bound to the
listener. `exeris-spring-runtime-web` always binds its dispatcher, so in a Pure Mode application those
paths answer `404` unless you declare them yourself. Point probes at your own routes.

## 4. Define the probe groups

<!-- example: operations/src/main/resources/application.properties#health -->
```properties
# Liveness and readiness groups, also outside Kubernetes. Readiness includes the
# Exeris runtime indicator, so an instance whose kernel has stopped is taken out of rotation.
management.endpoint.health.probes.enabled=true
management.endpoint.health.group.readiness.include=readinessState,exerisRuntimeSpringBoot
```

Spring Boot creates the `liveness` and `readiness` groups by itself when it detects Kubernetes; the
first line creates them everywhere, so the same routes work locally. Keep `exerisRuntimeSpringBoot`
out of liveness: a stopped kernel is a reason to stop sending traffic, and a restart will not fix it.

Wire the probes to the kernel's port:

```yaml
spec:
  terminationGracePeriodSeconds: 30
  containers:
    - name: app
      ports:
        - containerPort: 8080
      livenessProbe:
        httpGet: { path: /actuator/health/liveness, port: 8080 }
      readinessProbe:
        httpGet: { path: /actuator/health/readiness, port: 8080 }
      startupProbe:
        httpGet: { path: /actuator/health/liveness, port: 8080 }
        failureThreshold: 30
        periodSeconds: 1
```

The kernel listener opens only once the kernel has started, and the Spring context refresh waits for
that, up to `exeris.runtime.lifecycle.startup-timeout-seconds` (default `30`). Give the startup probe
at least that long.

## 5. Record metrics

`spring-boot-starter-actuator` puts Micrometer on the classpath and Spring Boot creates a
`MeterRegistry`. Inject it into a handler and record what the route does:

<!-- example: operations/src/main/java/eu/exeris/examples/operations/GreetingHandler.java#handler -->
```java
/** A business route that records its own Micrometer counter. */
@Component
@ExerisRoute(method = HttpMethod.GET, path = "/greeting")
public class GreetingHandler implements ExerisRequestHandler {

    private final Counter greetings;

    public GreetingHandler(MeterRegistry registry) {
        this.greetings = Counter.builder("greetings.served")
                .description("Greetings answered")
                .register(registry);
    }

    @Override
    public ExerisServerResponse handle(ExerisServerRequest request) {
        greetings.increment();
        return ExerisServerResponse.ok().body("Hello from Exeris");
    }
}
```

The `/actuator/metrics` and `/actuator/prometheus` endpoints are not served over HTTP, for the same
reason as health. Use a push-based Micrometer registry, or serve the registry from a route of your
own.

### Count events in `exeris.events.*`

`exeris-spring-runtime-actuator` also registers `ExerisActuatorTelemetryBridge`, which adds four gauges
to the registry: `exeris.events.info`, `exeris.events.warn`, `exeris.events.error` and
`exeris.events.fatal`. Each counts the `KernelEvent`s of its level that the bridge receives since the
application started.

During a Pure Mode request, the dispatcher binds `KernelProviders.TELEMETRY_SINKS` to a sink list that
includes the bridge. A handler that emits a `KernelEvent` through that slot moves the gauge for the
event's level:

<!-- example: operations/src/main/java/eu/exeris/examples/operations/QuoteHandler.java#handler -->
```java
/** Answers with a fallback quote, and reports each fallback as a WARN event. */
@Component
@ExerisRoute(method = HttpMethod.GET, path = "/quote")
public class QuoteHandler implements ExerisRequestHandler {

    static final String FALLBACK_SERVED = "QUOTE-FALLBACK-001";

    @Override
    public ExerisServerResponse handle(ExerisServerRequest request) {
        // This example has no quote service, so every answer is the fallback.
        emit(KernelEvent.warn(FALLBACK_SERVED, "quote", null));
        return ExerisServerResponse.ok().body("Simplicity is prerequisite for reliability.");
    }

    /** Emits through the sinks bound for this request. Unbound outside a Pure Mode request. */
    private static void emit(KernelEvent event) {
        if (!KernelProviders.TELEMETRY_SINKS.isBound()) {
            return;
        }
        for (TelemetrySink sink : KernelProviders.TELEMETRY_SINKS.get()) {
            sink.emit(event);
        }
    }
}
```

Each `GET /quote` adds `1` to `exeris.events.warn`. Check `isBound()` first: reading an unbound
`ScopedValue` throws.

Only that path reaches the gauges. These do not:

- **The kernel's own events.** Kernel 0.13.1 does not emit through a `TelemetrySink`; its subsystems
  record Flight Recorder events directly (or SLF4J when JFR is off). A request whose handler emits
  nothing leaves all four gauges where they were, and so does a kernel warning or failure. Do not
  alert on these gauges as a measure of kernel health; read the kernel's JFR events or its logs.
- **Compatibility Mode requests.** They run outside the dispatcher's binding, so `TELEMETRY_SINKS` is
  unbound in a `@RestController`.
- **Code outside a request.** A scheduled task or a startup hook runs with the slot unbound.
- **The sink's other methods.** The bridge ignores `increment`, `gauge` and `latency`: only `emit`
  counts. Record your own measurements with Micrometer, as above.

## 6. Read the kernel's logs

The kernel logs through `System.Logger`, and Spring Boot's default logging routes it into the same
output as the application. The logger names start with `eu.exeris.kernel`, abbreviated in the
default pattern:

```
INFO ... [ntime-lifecycle] e.e.k.c.bootstrap.SubsystemOrchestrator  : Selector resolved 4 subsystem(s): memory, crypto, transport, http
INFO ... [ntime-lifecycle] e.e.k.c.bootstrap.SubsystemOrchestrator  : STARTED in 88 ms
```

Set their level like any other logger:

<!-- example: operations/src/main/resources/application.properties#logging -->
```properties
# The kernel logs through System.Logger under eu.exeris.kernel; Spring Boot routes it here.
logging.level.eu.exeris.kernel=INFO
```

At `INFO` the kernel logs each subsystem as it starts, about 40 lines, all on the
`exeris-runtime-lifecycle` thread. `WARN` silences them; `DEBUG` adds detail. The line
`HTTP/1.1 h2c upgrade requested; switching to HTTP/2 frame-loop mode` tells you a client is using
HTTP/2, which matters for shutdown (below).

## 7. Read a boot failure

If the kernel refuses to start, the context refresh fails at once, without waiting for
`exeris.runtime.lifecycle.startup-timeout-seconds`. Spring Boot logs `Application run failed` with
`Failed to start bean 'exerisRuntimeLifecycle'`, and the first `Caused by` line says what went wrong.
It starts with the kernel's error code in brackets. For a subsystem, it also names the subsystem, the
phase that failed, and the detail. With only two of the three edge-key properties set:

```
org.springframework.context.ApplicationContextException: Failed to start bean 'exerisRuntimeLifecycle'
...
Caused by: java.lang.IllegalStateException: Exeris runtime startup failed: [EX-BOOT-0002] subsystem 'security' failed in INITIALIZE: security.jwks.uri is not set, but another token verification key is; set security.jwks.uri, security.jwt.issuer and security.jwt.audience together, or none of them
```

Read that line, not the lines under it: the kernel's own exceptions end in a constant message such as
`Subsystem lifecycle failure`.

**One runtime per JVM.** A second Exeris runtime started in a JVM where one already runs fails the
same way, at once:

```
Caused by: java.lang.IllegalStateException: Exeris runtime startup failed: Exeris bootstrap environment already prepared: another Exeris runtime is running in this JVM. Only one Exeris runtime per JVM is supported; stop the earlier runtime before starting another.
```

A runtime that has stopped releases the JVM for the next one, so contexts started one after another
work. In tests, close a Spring test context before another test class starts its own runtime, for
example with `@DirtiesContext` on the class.

## 8. Size the shutdown

<!-- example: operations/src/main/resources/application.properties#shutdown -->
```properties
# Spring waits this long for the kernel to stop after the context closes. Keep it below
# the orchestrator's grace period so the stop finishes before the process is killed.
exeris.runtime.shutdown.graceful=true
exeris.runtime.shutdown.timeout-seconds=20
```

How the stop runs:

1. `SIGTERM` reaches the JVM. The kernel's own JVM shutdown hook is disabled, so the only stop is
   Spring's context close. The runtime stops after the Spring beans that still use it.
2. `ExerisRuntimeLifecycle` releases the kernel. On the kernel's thread, the kernel closes the
   listener, drains the requests in flight, and stops its subsystems.
3. Spring waits for that thread for at most `exeris.runtime.shutdown.timeout-seconds` (default `30`,
   minimum `1`). If the time runs out, Spring logs
   `WARN ... Failed to stop bean 'exerisRuntimeLifecycle'` with
   `Exeris runtime shutdown timed out after 20000 ms`, and the context close continues.

The timeout bounds **Spring's wait**, not the drain. The kernel's drain has its own 60 s deadline,
which Spring cannot change. It is not a budget for in-flight requests either: size it for how long
you accept a stop to take.

Set the timeout **below `terminationGracePeriodSeconds`** (Kubernetes default `30`). The kubelet
sends `SIGKILL` when the grace period ends; with a timeout of `20` and a grace period of `30`, the
process always exits on its own first. Leave room for the other beans to close after the runtime.

Keep `exeris.runtime.shutdown.graceful=true`. With `false`, Spring does not wait on the kernel's
thread, but it then calls the kernel's own shutdown, which blocks until the kernel's stop ends. The
close can take longer than with `true`, because `timeout-seconds` no longer bounds it.

### Known limitation: idle HTTP/2 connections hold the stop

An idle HTTP/1.1 keep-alive connection does not delay the stop. An idle HTTP/2 connection, reached
through an `h2c` upgrade, does: the kernel's drain waits on it until the client closes it, the 60 s
drain deadline passes, or Spring's timeout runs out (exeris-kernel#821). Measured on kernel 0.13.1
with the 30 s timeout, from stop to process exit:

| Idle client connection | Stop to exit |
|:--|:--|
| HTTP/1.1 keep-alive | 0.23 s |
| HTTP/2 after `h2c` upgrade | 28.8 s |

The JDK's `HttpClient` asks for `h2c` by default, and so does any proxy that speaks HTTP/2 to its
backends. Until the kernel fixes this:

- **Keep the timeout short.** It is the worst case for every stop with an HTTP/2 client attached.
- **Close idle connections on the client side.** The drain ends as soon as the client closes the
  connection. For the JDK `HttpClient`, `-Djdk.httpclient.keepalive.timeout.h2=<seconds>` sets that;
  for a proxy, set its upstream idle timeout. Clients that need no HTTP/2 can pin
  `HttpClient.Version.HTTP_1_1`.

## Where this does not apply

- **Compatibility Mode.** With `exeris.runtime.web.mode=compatibility`, the runtime registers
  `ExerisCompatibilityActuatorController`, which already serves `/actuator/health`, `/health` and
  `/actuator/info`; you do not write the handlers from step 3. The `exeris.events.*` gauges are
  registered but receive nothing from Compatibility Mode requests (step 5). See
  [Migrating a Spring MVC application](compatibility-mode-migration.md).
- **Applications without `exeris-spring-runtime-web`.** With no application handler bound, the kernel
  serves its built-in `/health`, `/health/live` and `/health/ready` routes and nothing else.
- **Kernels older than 0.11.0.** They cut in-flight requests during the stop instead of draining
  them. Take the instance out of rotation before sending `SIGTERM`.

## Related guides

- [Configuration reference](configuration-reference.md) — every `exeris.runtime.*` property.
- [Securing routes](securing-routes.md) — with a closed `anyRequest()`, permit the
  `/actuator/health` paths as well, or the probes are refused.
