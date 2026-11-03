---
title: "Graph queries with ExerisGraphTemplate"
type: howto
visibility: public
owning-repo: exeris-spring-runtime
status: active
last-verified: 2026-11-03
---

# Graph queries with ExerisGraphTemplate

This guide shows how to write graph edges and run breadth-first traversals from Spring beans through
the kernel graph engine, using Neo4j as the backend, and how to run the same code on the SQL/PGQ
backend over PostgreSQL instead. You use `ExerisGraphTemplate` for imperative calls and
`@ExerisGraphQuery` for a declarative method.

**Status: preview.** `exeris-spring-runtime-graph` is default-off and carries no GA promise. Graph GA
is kernel-gated: the kernel's `GraphChurnRatioTck` runs as a CI gate for the Cypher (Neo4j) path only,
and the SQL/PGQ path has no churn binding. The kernel classifies its graph SPI as preview.

**Mode.** The graph bridge is not request-path code and imports nothing from `*.compat.*`, so it is
the same in a Pure Mode and a Compatibility Mode application. The example boots no HTTP at all.

The working application is [`examples/graph-queries`](../../examples/graph-queries). One test starts
Neo4j and PostgreSQL in containers, writes a small graph, and runs every traversal below against it.
A second test runs the same application on the SQL/PGQ backend with PostgreSQL alone.

## Prerequisites

- An application set up as in [Getting started](getting-started.md): JDK 25, Spring Boot 3.5.x or
  4.1.x, the runtime BOM, and `exeris-kernel-community` 0.13.1.
- A reachable **Neo4j** (Bolt) for the Cypher backend. The example uses `neo4j:5-community`. The
  SQL/PGQ backend (step 7) needs none.
- A reachable **PostgreSQL**. The kernel graph subsystem depends on the persistence subsystem, and
  persistence opens a JDBC pool at boot, even when graph data lives in Neo4j.
- **Docker**, to run the example's test. Without it the test fails; it is not skipped.

## 1. Add the dependencies

<!-- example: graph-queries/pom.xml#dependencies -->
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
    <artifactId>exeris-spring-runtime-graph</artifactId>
</dependency>
<!-- Provides the graph engine (Neo4j Bolt or SQL/PGQ) and the persistence engine the
     graph subsystem depends on. -->
<dependency>
    <groupId>eu.exeris</groupId>
    <artifactId>exeris-kernel-community</artifactId>
    <scope>runtime</scope>
</dependency>
<!-- The kernel graph subsystem requires the persistence subsystem, which opens a JDBC
     pool at boot. The kernel declares the PostgreSQL driver optional. -->
<dependency>
    <groupId>org.postgresql</groupId>
    <artifactId>postgresql</artifactId>
    <scope>runtime</scope>
</dependency>
```

`exeris-kernel-community` already brings the Neo4j Java driver. Do not add Spring Data Neo4j: the
graph bridge does not integrate with it.

## 2. Configure the kernel graph engine and the bridge

<!-- example: graph-queries/src/main/resources/application.properties#properties -->
```properties
# Boot the graph subsystem. The kernel adds its dependencies, memory and persistence, on its own;
# they are listed so the set is explicit.
exeris.runtime.subsystems=memory,persistence,graph

# The persistence subsystem opens a JDBC pool at boot, even when graph data lives in Neo4j.
exeris.runtime.persistence.jdbc-url=jdbc:postgresql://localhost:5432/exeris
exeris.runtime.persistence.username=exeris
exeris.runtime.persistence.password=exeris

# Kernel graph engine. These are raw kernel keys, read verbatim: there is no
# exeris.runtime.graph.* alias for them.
graph.backendType=neo4j
graph.neo4j.uri=bolt://localhost:7687
graph.neo4j.user=neo4j
graph.neo4j.password=change-me-please

# Spring-side bridge: ExerisGraphTemplate and @ExerisGraphQuery. Off unless set.
exeris.runtime.graph.enabled=true
```

Two groups of keys, read by two different parties:

| Key | Read by | Default | Meaning |
|:--|:--|:--|:--|
| `graph.backendType` | kernel | `postgresql` | `neo4j`, `memgraph` or `falkordb` select the Cypher backend. Anything else selects SQL/PGQ over the persistence engine (step 7). |
| `graph.neo4j.uri` | kernel | none | Bolt URI. Without it the Cypher engine has no client (see below). Not read on SQL/PGQ. Environment fallback: `EXERIS_GRAPH_NEO4J_URI`. |
| `graph.neo4j.user`, `graph.neo4j.password` | kernel | none | Required once `graph.neo4j.uri` is set. Fallbacks: `EXERIS_GRAPH_NEO4J_USER`, `EXERIS_GRAPH_NEO4J_PASSWORD`. |
| `graph.neo4j.database` | kernel | driver default | Optional. Fallback: `EXERIS_GRAPH_NEO4J_DATABASE`. |
| `exeris.runtime.graph.enabled` | Spring | `false` | Creates `ExerisGraphTemplate` and the `@ExerisGraphQuery` processor. |
| `exeris.runtime.graph.require-engine` | Spring | `true` | With `false`, the template is created when no engine is bound; every call still throws `IllegalStateException`. |

The kernel publishes its engine in the `ScopedValue` slot `GraphKernelProviders.GRAPH_ENGINE`. The
runtime captures it from that slot at boot, and `ExerisGraphTemplate` reads the captured engine on
every call. You do not read the slot yourself.

The graph backends also read two other kernel providers on the calling thread: the SQL/PGQ backend
opens its connections from the persistence engine, and the streaming traversals allocate from the
memory allocator. `ExerisGraphTemplate` runs every session inside a scope that binds both from the
references the runtime captured at boot, so the template works from any Spring thread: a bean method,
a test, a scheduler or `main`. A slot the caller already has bound, as on a kernel request thread,
keeps its binding.

## 3. Understand Spring Boot's own Neo4j `Driver`

Because the Neo4j driver is on the classpath, Spring Boot's `Neo4jAutoConfiguration` creates a
`Driver` bean of its own from `spring.neo4j.*`. That bean and the kernel graph engine are two
independent clients with two connection pools:

- The kernel graph engine reads **only** `graph.neo4j.*` (or its environment variables). It never
  reads `spring.neo4j.*` and never uses the Spring `Driver` bean.
- Spring Boot's `Driver` reads **only** `spring.neo4j.*`. Unset, it points at
  `bolt://localhost:7687` and connects on first use, so it costs nothing until you inject it.

If you set `spring.neo4j.uri` and leave `graph.neo4j.uri` unset, the application still boots, and
the first traversal fails with `GraphQueryException` (`EX-GRPH-5002`, raw arguments
`CYPHER_BACKEND`, `Neo4j client is not configured for Cypher backend`).

If you also want the Spring `Driver`, for example to run Cypher that the graph SPI does not cover,
point both groups at the same database. The example's test does that:

<!-- example: graph-queries/src/test/java/eu/exeris/examples/graph/GraphQueriesApplicationTest.java#test-properties -->
```java
@DynamicPropertySource
static void backends(DynamicPropertyRegistry registry) {
    // Read by the kernel graph engine.
    registry.add("graph.neo4j.uri", NEO4J::getBoltUrl);
    registry.add("graph.neo4j.user", () -> "neo4j");
    registry.add("graph.neo4j.password", () -> NEO4J_PASSWORD);
    // Read by Spring Boot's Neo4jAutoConfiguration for its own Driver bean, not by the kernel.
    registry.add("spring.neo4j.uri", NEO4J::getBoltUrl);
    registry.add("spring.neo4j.authentication.username", () -> "neo4j");
    registry.add("spring.neo4j.authentication.password", () -> NEO4J_PASSWORD);
    // Read by the kernel persistence subsystem, which the graph subsystem depends on.
    registry.add("exeris.runtime.persistence.jdbc-url", POSTGRES::getJdbcUrl);
    registry.add("exeris.runtime.persistence.username", POSTGRES::getUsername);
    registry.add("exeris.runtime.persistence.password", POSTGRES::getPassword);
}
```

An edge written through `ExerisGraphTemplate` is then visible to a query through the Spring
`Driver`. If you do not want the Spring `Driver` at all, exclude it on Spring Boot 3.5 with
`spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.neo4j.Neo4jAutoConfiguration`.

## 4. Write edges and traverse with `ExerisGraphTemplate`

<!-- example: graph-queries/src/main/java/eu/exeris/examples/graph/FollowGraph.java#template -->
```java
@Component
public class FollowGraph {

    // User -[FOLLOWS]-> User, walked from source to target.
    static final GraphEdgeDescriptor FOLLOWS = GraphEdgeDescriptor.create("User", "FOLLOWS", "User");

    // The same relationship walked from target to source.
    static final GraphEdgeDescriptor FOLLOWED_BY = new GraphEdgeDescriptor(
            "User", "FOLLOWS", "User", 1.0, false, Direction.INCOMING, null);

    private final ExerisGraphTemplate graph;

    public FollowGraph(ExerisGraphTemplate graph) {
        this.graph = graph;
    }

    public void follow(UUID follower, UUID followed) {
        graph.inTransaction(session -> session.upsertEdge(FOLLOWS, follower, followed, 1.0, null));
    }

    // Everyone reachable from start in 1 to maxDepth FOLLOWS steps.
    public List<UUID> followedWithin(UUID start, int maxDepth) {
        return graph.traverseBfs(GraphTraversal.create(start, FOLLOWS, maxDepth));
    }

    public List<UUID> followersOf(UUID user) {
        return graph.traverseBfs(GraphTraversal.create(user, FOLLOWED_BY, 1));
    }

    // The same traversal as followedWithin, as a JSON array of ids in an off-heap buffer.
    public String followedWithinJson(UUID start, int maxDepth) {
        try (LoanedBuffer buffer = graph.streamBfsJson(GraphTraversal.create(start, FOLLOWS, maxDepth))) {
            // The segment spans capacity(); the payload is its first size() bytes.
            MemorySegment json = buffer.segment().asSlice(0, buffer.size());
            // Decoding copies to the heap. To avoid the copy, write the segment where it goes
            // before the buffer is released.
            return new String(json.toArray(ValueLayout.JAVA_BYTE), StandardCharsets.UTF_8);
        }
    }
}
```

What each call does:

- **`inTransaction`** opens a graph session, begins the session's own transaction, runs the action,
  commits, and rolls back if the action throws. It is the kernel graph session's transaction, not a
  Spring one: `@Transactional` and `ExerisPlatformTransactionManager` do not cover it.
- **`upsertEdge`** creates both endpoint nodes if they do not exist, then creates or updates the one
  edge between them. Calling it twice does not add a second edge.
- **`traverseBfs`** returns the distinct nodes reached in 1 to `maxDepth` steps, in no defined order.
  For the chain `ann -> bob -> cid -> dan`, depth 1 from `ann` is `[bob]` and depth 2 is `[bob, cid]`.
  The Community engine refuses a depth above 64 before any I/O.
- **Direction** comes from the descriptor. `create(...)` is `OUTGOING`. The `INCOMING` descriptor
  walks the same `FOLLOWS` edges backwards, so `followersOf(cid)` is `[bob]`. `BOTH`, or
  `bidirectional = true`, walks both ways.
- **`streamBfsJson`** runs the same traversal and writes the result as a JSON array of id strings,
  for example `["<bob>","<cid>"]`, into a `LoanedBuffer` allocated off-heap from the kernel memory
  allocator. The session is closed before the buffer is returned, and the caller owns the buffer:
  close it, with try-with-resources. Its segment spans the buffer's `capacity()`; the payload is the
  first `size()` bytes, so read `segment().asSlice(0, size())`. The example decodes it to a `String`
  for readability; to keep the result off the heap, write the slice where it goes before closing the
  buffer.

For any other session operation, `execute(session -> ...)` opens a session, passes it to your
callback, and closes it afterwards.

## 5. Declare a traversal with `@ExerisGraphQuery`

<!-- example: graph-queries/src/main/java/eu/exeris/examples/graph/FollowQueries.java#query -->
```java
// Not final: the @ExerisGraphQuery processor installs a class-based proxy.
@Component
public class FollowQueries {

    // The proxy routes the call to ExerisGraphTemplate.traverseBfs(traversal);
    // this body never runs.
    @ExerisGraphQuery
    public List<UUID> reachable(GraphTraversal traversal) {
        throw new IllegalStateException("routed by ExerisGraphQueryProcessor");
    }

    // Routed to ExerisGraphTemplate.streamBfsJson(traversal). The caller owns the
    // returned buffer and must close it.
    @ExerisGraphQuery
    public LoanedBuffer reachableJson(GraphTraversal traversal) {
        throw new IllegalStateException("routed by ExerisGraphQueryProcessor");
    }
}
```

The processor checks every annotated method when the bean is created and fails the startup if the
method is not `public`, if the class is `final`, if the method does not take exactly one
`GraphTraversal`, or if it returns anything but `List<UUID>` or `LoanedBuffer`. A `List<UUID>`
method routes to `traverseBfs`; a `LoanedBuffer` method routes to `streamBfsJson`, and its caller
owns and closes the buffer as in step 4. The annotation's `value` string is not parsed: the
`GraphTraversal` argument is the whole query.

## 6. Expect refusal under a tenant context

The kernel scopes every traversal (`traverseBreadthFirst`, `streamBfsJson`, `traversePath`,
`streamPathJson`) to the `StorageContext` bound in `KernelProviders.STORAGE_CONTEXT`. With no context
bound, the system context applies and the traversal runs. The Neo4j backend can scope no tenant
isolation strategy, so a traversal under any tenant context is refused before a query is sent:

<!-- example: graph-queries/src/test/java/eu/exeris/examples/graph/GraphQueriesApplicationTest.java#tenant-refusal -->
```java
@Test
void traversalUnderATenantContextIsRefused() {
    // The Neo4j binding can scope no tenant isolation strategy, so a traversal under a
    // tenant context is refused before any query is sent.
    assertThatThrownBy(() -> ScopedValue
            .where(KernelProviders.STORAGE_CONTEXT, ImmutableStorageContext.shared("tenant-a"))
            .call(() -> graph.followedWithin(ann, 1)))
            .isInstanceOfSatisfying(GraphQueryException.class, refused -> {
                assertThat(refused.errorCode()).isEqualTo("EX-GRPH-5002");
                assertThat(refused.rawArgs()[0]).isEqualTo("ISOLATION_SCOPE");
            });
}
```

Writes, `getRootNode` and `findShortestPath` are not scoped. The SQL/PGQ backend accepts
`SEPARATED_SCHEMA` and `DEDICATED` contexts and opens the connection under the context through the
persistence engine the template binds. It refuses a `SHARED` tenant context the same way, with
`ISOLATION_SCOPE`. The example tests only the refusals; a schema-per-tenant or dedicated database
setup is persistence configuration this guide does not cover.

## 7. Run on the SQL/PGQ backend instead

With `graph.backendType=postgresql` the graph lives in PostgreSQL tables and is read and written
through the kernel persistence engine, the one `exeris.runtime.persistence.*` configures. No Neo4j
runs, and the `graph.neo4j.*` keys are not read. The example selects the backend with a profile:

<!-- example: graph-queries/src/main/resources/application-sqlpgq.properties#properties -->
```properties
# SQL/PGQ backend: graph edges live in PostgreSQL tables, read and written through the kernel
# persistence engine configured by exeris.runtime.persistence.*. The graph.neo4j.* keys are
# not read, and no Neo4j is needed.
graph.backendType=postgresql
```

`FollowGraph` and `FollowQueries` are unchanged. One thing is different: each edge type is stored in
its own table, named by the descriptor's `tableName()` (`follows_edges` for `FOLLOWS`), and a write
does not create it. Create it in your schema migrations; the dialect generates the DDL:

<!-- example: graph-queries/src/test/java/eu/exeris/examples/graph/SqlPgqGraphQueriesTest.java#edge-table -->
```java
// The SQL/PGQ backend stores an edge type in its own table and does not create it on a
// write. Run this DDL from your schema migrations; the dialect generates it.
String ddl = template.dialect().buildCreateEdgeTable(FollowGraph.FOLLOWS);
```

The table has the columns `source_id`, `target_id`, `weight`, `properties` and `created_at`, with the
primary key `(source_id, target_id)`; `upsertEdge` updates the row for an existing pair. Without the
table, the first write fails with `PersistenceProviderException` ("Persistence query execution
failure"), caused by PostgreSQL's `relation "follows_edges" does not exist`.

`SqlPgqGraphQueriesTest` boots the application with the `sqlpgq` profile against a PostgreSQL
container only, and calls every method of steps 4 and 5 from the test thread, which is outside any
kernel scope.

## 8. Run the tests

```bash
mvn -f examples/graph-queries/pom.xml verify
```

The two test classes boot the application against fresh containers, one on Neo4j and one on
SQL/PGQ, and assert the traversal depths, the direction, the streamed JSON, the `@ExerisGraphQuery`
routing of both return types, and the tenant refusal. The Neo4j class also asserts that the Spring
`Driver` sees the edges the kernel wrote. Only one Exeris runtime runs per JVM, so the module's
Surefire configuration gives each test class a fresh fork (`reuseForks=false`).

At startup Spring logs several `is not eligible for getting processed by all BeanPostProcessors`
warnings for the graph and runtime beans. They come from the `@ExerisGraphQuery` processor being a
bean post-processor that depends on the template, and the example runs correctly with them.

## Where this does not apply

- **A fluent query DSL or a cursor.** There is no `GraphQueryBuilder` and no `GraphCursor`; both are
  out of scope. A traversal is a `GraphTraversal` (one edge type) or, on the session, a
  `GraphPathSpec` (several hops). A result is materialised whole.
- **Label filtering in Cypher traversals.** On the Neo4j backend the traversal matches the start
  node by `id` and follows the edge type and direction; the descriptor's node labels are used when
  writing, not when traversing.
- **Churn-gated SQL/PGQ.** The kernel's `GraphChurnRatioTck` gate covers the Cypher path only; the
  SQL/PGQ path works through the bridge but has no churn binding.
- **Edge tables created for you.** On SQL/PGQ a write does not create its edge table (step 7).
- **Multi-tenant graph data on Neo4j, or with a `SHARED` tenant key on either backend.** Those
  traversals are refused (step 6).
- **Spring Data Neo4j, repositories and derived queries.** Not bridged.
- **One transaction across graph and JDBC.** Graph session transactions stay inside the graph
  session.
- **Several graph engines in one application.** The bridge exposes exactly one.

## Next steps

- [Configuration reference](configuration-reference.md) — every `exeris.runtime.*` property.
- [Transactions and JDBC](transactions-and-jdbc.md) — the persistence engine the graph subsystem
  depends on.
- [Health and operations](health-and-operations.md) — running and stopping the kernel.
