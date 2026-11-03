---
title: "Events and flows (sagas) on the Exeris kernel"
type: howto
visibility: public
owning-repo: exeris-spring-runtime
status: active
last-verified: 2026-11-01
---

# Events and flows (sagas) on the Exeris kernel

This guide shows how a Spring application publishes and consumes events on the kernel's event bus,
and how it runs a saga on the kernel's flow engine: steps that run in order, and compensations that
undo the completed steps when a later step fails. The kernel owns the bus, the scheduling, the state
transitions and the compensation. Spring wires the beans that declare them.

The examples are Pure Mode code. Neither bridge depends on the web mode, so a Compatibility Mode
application uses them the same way.

The finished application is [`examples/events-and-flows`](../../examples/events-and-flows). CI builds
it and runs its tests against the Community kernel, so the code below is the code that runs.

**Both bridges are preview modules.** They ship off. Nothing turns them on except their own flag:
`exeris.runtime.events.enabled` and `exeris.runtime.flow.enabled`, both `false` by default.

## Prerequisites

- An application set up as in [Getting started](getting-started.md): JDK 25, Spring Boot 3.5.x or
  4.1.x, the runtime's BOM, and `exeris-kernel-community` 0.13.1.
- A JDBC database the kernel can reach. The kernel's events and flow subsystems depend on its
  persistence subsystem, and that subsystem does not boot without a database. H2 in memory is enough.

## 1. Declare the dependencies

<!-- example: events-and-flows/pom.xml#dependencies -->
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
    <artifactId>exeris-spring-runtime-events</artifactId>
</dependency>
<dependency>
    <groupId>eu.exeris</groupId>
    <artifactId>exeris-spring-runtime-flow</artifactId>
</dependency>
<dependency>
    <groupId>eu.exeris</groupId>
    <artifactId>exeris-kernel-community</artifactId>
    <scope>runtime</scope>
</dependency>
<!-- The kernel's events and flow subsystems depend on its persistence subsystem, which
     needs a JDBC database. H2 in memory is enough to boot it. -->
<dependency>
    <groupId>com.h2database</groupId>
    <artifactId>h2</artifactId>
    <scope>runtime</scope>
</dependency>
```

`exeris-spring-runtime-flow` depends on `exeris-spring-runtime-events`. An application that uses
flows only can leave the events module out of its pom, but it still arrives on the classpath.

## 2. Turn the bridges on

<!-- example: events-and-flows/src/main/resources/application.properties#properties -->
```properties
# Exeris owns the runtime: Spring Boot starts no web server of its own.
spring.main.web-application-type=none

# Both bridges are preview modules and ship off. Each one is turned on explicitly.
exeris.runtime.events.enabled=true
exeris.runtime.flow.enabled=true
# Lets an event start a saga through an ExerisFlowChoreographyMapper bean.
exeris.runtime.flow.choreography-enabled=true

# The kernel's events and flow subsystems depend on memory and persistence. No transport or
# http subsystem is listed, so this application opens no port.
exeris.runtime.subsystems=memory,persistence,events,flow

# The persistence subsystem needs a JDBC database to boot.
exeris.runtime.persistence.jdbc-url=jdbc:h2:mem:orders;DB_CLOSE_DELAY=-1
exeris.runtime.persistence.username=sa
exeris.runtime.persistence.password=
```

- **`exeris.runtime.subsystems`** — `memory,persistence,events,flow` is the smallest set. The kernel
  adds the dependencies of every subsystem you list, so `events,flow` alone resolves to the same
  four. Listing them states what the process boots. Add `transport,http` for HTTP ingress.
- **`exeris.runtime.flow.choreography-enabled`** — `false` by default. Only step 8 needs it.
- **`exeris.runtime.events.require-engine`** and **`exeris.runtime.flow.require-engine`** — both
  `true` by default. If a listener or a flow definition exists and the kernel booted without the
  engine it needs, startup fails. Setting them to `false` turns that failure into a warning, and the
  listeners and flows are dropped.

## 3. Register your event types

A listener subscribes by type name, and the kernel refuses a name its registry does not hold. This
runtime does not register types for you. Register them in the kernel's `EventRegistry` once the
kernel has booted and before the listeners subscribe:

<!-- example: events-and-flows/src/main/java/eu/exeris/examples/orders/OrderEventTypes.java#event-types -->
```java
/**
 * Registers the application's event types in the kernel's event registry.
 *
 * <p>A listener or a choreography mapper subscribes by type name, and the kernel refuses a name it
 * does not hold. The registry exists only once the kernel has booted, and subscriptions are made
 * in the next lifecycle phase, so registration runs in the runtime's own phase. Injecting
 * {@link ExerisRuntimeLifecycle} makes Spring start the runtime before this bean.
 */
@Component
public class OrderEventTypes implements SmartLifecycle {

    public static final String ORDER_PLACED = "order.placed";

    private final ExerisRuntimeLifecycle runtime;
    private volatile boolean running;

    public OrderEventTypes(ExerisRuntimeLifecycle runtime) {
        this.runtime = runtime;
    }

    @Override
    public void start() {
        EventRegistry registry = runtime.getEventEngine()
                .orElseThrow(() -> new IllegalStateException("the kernel booted without an event engine"))
                .registry();
        if (!registry.isRegistered(ORDER_PLACED)) {
            registry.register(EventTypeSpec.of(ORDER_PLACED, 1024));
        }
        running = true;
    }

    @Override
    public void stop() {
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 100;
    }
}
```

The runtime lifecycle runs in phase `Integer.MAX_VALUE - 100`. The listener registrar runs in phase
`Integer.MAX_VALUE - 99`, and so does the flow definition registrar. The choreography bridge runs in
`Integer.MAX_VALUE - 98`. The registry rejects a second type with an ordinal it already holds, so give
each type its own ordinal.

## 4. Publish an event

Inject `ExerisEventPublisher`:

<!-- example: events-and-flows/src/main/java/eu/exeris/examples/orders/OrderEvents.java#publisher -->
```java
@Component
public class OrderEvents {

    private final ExerisEventPublisher publisher;

    public OrderEvents(ExerisEventPublisher publisher) {
        this.publisher = publisher;
    }

    /** Publishes {@code order.placed} on the stream of the order. The event carries no payload. */
    public void orderPlaced(UUID orderId) {
        publisher.publish(OrderEventTypes.ORDER_PLACED, orderId, EventPayload.empty());
    }
}
```

`publish(typeName, streamId, payload)` builds the descriptor with a fresh event id and returns
without waiting for the handlers. `publishAndAwait(descriptor, payload)` runs every handler on the
calling thread and returns when they have finished. Publishing hands your reference to the payload
to the bus, so do not use the payload afterwards. This runtime has no payload builder of its own. The
example carries the order id as the event's stream id and publishes `EventPayload.empty()`.

`ExerisEventPublisher` is not Spring's `ApplicationEventPublisher`, and the two are never connected
in either direction (ADR-027). Spring's events are in-process callbacks inside one application
context. The kernel bus carries descriptor-typed events, feeds flow choreography and is what the
kernel's event stores record. A bridge would make Spring's multicaster the router of kernel events,
the ownership inversion this runtime exists to prevent. An application that needs both keeps both:
`@EventListener` methods receive Spring events, `@ExerisEventListener` methods receive kernel events,
and an event published on one bus never reaches the other.

## 5. Listen for an event

Annotate a public method on a bean with `@ExerisEventListener`. The method must take an
`EventDescriptor` and an `EventPayload` and return `void`; any other signature fails startup.

<!-- example: events-and-flows/src/main/java/eu/exeris/examples/orders/OrderAudit.java#listener -->
```java
@Component
public class OrderAudit {

    private final Set<UUID> placed = ConcurrentHashMap.newKeySet();

    // The kernel owns the payload: do not keep it, or anything read from it, past this call.
    @ExerisEventListener(eventTypes = OrderEventTypes.ORDER_PLACED)
    public void onOrderPlaced(EventDescriptor descriptor, EventPayload payload) {
        placed.add(new UUID(descriptor.streamIdHigh(), descriptor.streamIdLow()));
    }

    public boolean hasSeen(UUID orderId) {
        return placed.contains(orderId);
    }
}
```

The registrar subscribes every listener when the context starts and unsubscribes them all when it
stops. To keep the payload past the method, call `payload.retain()` and close it yourself later.

## 6. Define a saga

A flow is a Spring bean that implements `ExerisFlowDefinition`. It is an interface, not an
annotation. The registrar calls `define` once at startup and compiles the result.

<!-- example: events-and-flows/src/main/java/eu/exeris/examples/orders/OrderSaga.java#saga -->
```java
@Component
public class OrderSaga implements ExerisFlowDefinition {

    public static final String NAME = "order-saga";

    private final OrderBook orders;
    private final Inventory inventory;
    private final Payments payments;

    public OrderSaga(OrderBook orders, Inventory inventory, Payments payments) {
        this.orders = orders;
        this.inventory = inventory;
        this.payments = payments;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public FlowDefinition define(FlowDefinitionBuilder builder) {
        return builder
                .step("reserve-stock", this::reserveStock, this::releaseStock)
                .step("charge-payment", this::chargePayment, null)
                .transition(0, 1)
                .timeoutDuration(30_000_000_000L)
                .build();
    }

    private FlowOutcome reserveStock(FlowContext flow) {
        inventory.reserve(orderId(flow));
        return FlowOutcome.CONTINUE;
    }

    private FlowOutcome releaseStock(FlowContext flow) {
        inventory.release(orderId(flow));
        return FlowOutcome.CONTINUE;
    }

    private FlowOutcome chargePayment(FlowContext flow) {
        UUID orderId = orderId(flow);
        return payments.charge(orderId, orders.amountOf(orderId)) ? FlowOutcome.COMPLETE : FlowOutcome.FAIL;
    }

    /** The saga instance id is the order id. */
    private static UUID orderId(FlowContext flow) {
        return new UUID(flow.instanceIdMost(), flow.instanceIdLeast());
    }
}
```

- `step(name, action, compensation)` adds a step. Steps are numbered from 0 in the order you add
  them. Pass `null` when a step has nothing to undo.
- A step returns `CONTINUE` to go on to the next step, `COMPLETE` to end the flow, `PARK` to suspend it
  until something wakes it, or `FAIL` to roll it back. A step that throws counts as `FAIL`.
- On `FAIL`, the engine runs the compensations of the steps that already succeeded, newest first, and
  the instance ends in `FAILED_ROLLEDBACK`. The failing step's own compensation does not run. In the
  example, a declined payment releases the reserved stock.
- Steps run on the kernel's virtual threads. Collaborators come in through the constructor. A step
  reads its input by the instance id, here from `OrderBook`. Do not keep a `FlowContext` after the
  step returns.
- `name()` must be unique across the application. A duplicate fails startup.

## 7. Start a saga

Inject `ExerisFlowTemplate`. `schedule(name)` mints an instance id and returns the seed context. To
know the id before the first step runs, take a seed from `newContext`, record your input under its
id, then schedule it:

<!-- example: events-and-flows/src/main/java/eu/exeris/examples/orders/Checkout.java#schedule -->
```java
/** Starts the saga for a new order and returns the order id. The saga runs asynchronously. */
public UUID placeOrder(long amountCents) {
    FlowContext seed = flows.newContext(OrderSaga.NAME);
    UUID orderId = new UUID(seed.instanceIdMost(), seed.instanceIdLeast());
    // Record the order before scheduling: the first step can run before schedule returns.
    orders.put(orderId, amountCents);
    flows.schedule(OrderSaga.NAME, seed);
    return orderId;
}
```

`schedule` returns before the saga finishes. The template also exposes `wake`, `lookupParked` and
`stats()`. `stats()` counts completed and rolled-back instances and compensations run since the
engine started.

## 8. Start a saga from an event (choreography)

With `exeris.runtime.flow.choreography-enabled=true` and the events bridge on, every
`ExerisFlowChoreographyMapper` bean is registered with the kernel. The kernel calls `map` for each
event of the listed types and acts on the decision. A mapper sees only the descriptor, not the
payload.

<!-- example: events-and-flows/src/main/java/eu/exeris/examples/orders/OrderPlacedStartsSaga.java#choreography -->
```java
/** Starts the order saga for every {@code order.placed} event, with the order id as instance id. */
@Component
public class OrderPlacedStartsSaga implements ExerisFlowChoreographyMapper {

    private final ExerisFlowTemplate flows;

    public OrderPlacedStartsSaga(ExerisFlowTemplate flows) {
        this.flows = flows;
    }

    @Override
    public Set<String> eventTypeNames() {
        return Set.of(OrderEventTypes.ORDER_PLACED);
    }

    @Override
    public ChoreographyDecision map(EventDescriptor event) {
        return new ChoreographyDecision.Start(
                flows.planFor(OrderSaga.NAME), event.streamIdHigh(), event.streamIdLow());
    }
}
```

Publishing the event is then enough to start the saga:

<!-- example: events-and-flows/src/main/java/eu/exeris/examples/orders/Checkout.java#announce -->
```java
/** Records a new order and publishes {@code order.placed}. A choreography mapper starts the saga. */
public UUID announceOrder(long amountCents) {
    UUID orderId = UUID.randomUUID();
    orders.put(orderId, amountCents);
    events.orderPlaced(orderId);
    return orderId;
}
```

The other decisions are `Wake(instanceIdMost, instanceIdLeast)`, which resumes a parked instance, and
`Ignore()`. A `Start` for an instance id the engine already holds does nothing, so a redelivered event
does not start a second saga. The Community flow engine supports choreography. On an engine that does
not, a declared mapper fails startup.

## Publishing under a tenant

Kernel 0.13 scopes events to tenants (kernel ADR-101). When the publishing thread has a storage
context with an isolation key bound (`KernelProviders.STORAGE_CONTEXT`), the kernel stamps the event
with that key's ordinal. If the key is not registered, the publish is refused with
`EventBusException` carrying `EX-EVENT-6009`, and no handler receives the event.

`ExerisEventPublisher` does not catch, translate or retry this. The exception reaches your code from
`publish` or `publishAndAwait`. Register each tenant's key before publishing under it, through the
engine's `isolationRegistry()`:

```java
runtime.getEventEngine().orElseThrow()
        .isolationRegistry()
        .register(IsolationKeySpec.of(tenantKey, ordinal));
```

There is no unregister: a key and its ordinal stay registered for the life of the engine. A publish with no storage context bound,
or with an empty isolation key, is not scoped and is not refused.

## Durable saga state

Saga state is kept in memory unless you turn persistence on. To have the kernel write each
instance's state to its `exeris_saga_state` table, set:

<!-- example: events-and-flows/src/test/resources/application-durable.properties#durable -->
```properties
# Write saga state to the kernel's exeris_saga_state table. Off unless set.
exeris.runtime.flow.persistence-enabled=true
# Let the kernel create its tables, exeris_saga_state among them, at boot.
exeris.runtime.persistence.run-migrations=true
```

- The engine writes to the database of the persistence subsystem
  (`exeris.runtime.persistence.jdbc-url`). The example's test does this against H2 in memory and finds
  a `COMPLETED` row for a saga that succeeded and a `FAILED_ROLLEDBACK` row for one that rolled back.
- The table must exist. With `persistence-enabled=true` and no `exeris_saga_state` table,
  scheduling a saga fails with `FlowEngineException` ("Failed to load flow snapshot"). Either let the
  kernel run its migrations or create the table yourself.
- State is as durable as the database. In-memory H2 loses it with the process. Use a database that
  outlives the process when parked sagas must survive a restart.
- After a restart, define the same flows under the same names. The engine matches a stored instance
  to its plan by definition name.

## Where this does not apply

- **Spring `@EventListener` and `ApplicationEventPublisher`.** They keep working as Spring's own
  in-process events and never reach the kernel bus.
- **`@Async` for background work.** Background work that can fail partway, must undo partial work, or
  waits for an external signal belongs in a flow.
- **A process with no database.** The events and flow subsystems need the persistence subsystem.
- **Retries.** `FlowDefinitionBuilder.maxRetries` exists, but this guide does not rely on it. The
  example treats a failed step as final.
- **Transporting events between processes.** Whether the bus crosses process boundaries is a
  property of the kernel's event engine, not of this runtime.

## Next steps

- [Transactions and JDBC](transactions-and-jdbc.md) — the persistence subsystem these bridges run on.
- [Configuration reference](configuration-reference.md) — every `exeris.runtime.*` property.
- [Health and operations](health-and-operations.md) — what to watch on a running application.
