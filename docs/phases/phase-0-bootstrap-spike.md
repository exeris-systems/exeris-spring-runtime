# Phase 0: Architecture Spike & Bootstrap Validation

**Status:** In Progress  
**Target:** Maven skeleton + ADR + bootstrap POC  
**Milestone:** M0

---

## Goal

Prove that `KernelBootstrap` and Spring `ApplicationContext` can coexist in a single JVM process
with clear, non-conflicting lifecycle ownership, without touching `exeris-kernel-spi` or
`exeris-kernel-core`.

This phase is completed when the "single process" question is answered with code, not theory.

---

## Deliverables

| # | Deliverable | Status |
|:-:|:------------|:-------|
| 1 | Maven multi-module reactor with module skeletons | Done |
| 2 | ADR-010 accepted | Done |
| 3 | Architecture documentation (`overview.md`, `module-boundaries.md`, `kernel-integration-seams.md`) | Done |
| 4 | `ExerisSpringConfigProvider` — Spring `Environment` → kernel `ConfigProvider` | Not Started |
| 5 | `ExerisRuntimeLifecycle` — `SmartLifecycle` triggering `KernelBootstrap` | Not Started |
| 6 | Bootstrap POC: Spring + Exeris start/stop in a single JVM process | Not Started |
| 7 | Architecture guard test — Wall verification | Not Started |
| 8 | Phase 0 invariants document | Not Started |

---

## Scope

### In Scope

- Module skeleton with correct Maven dependency graph
- `ExerisSpringConfigProvider implements ConfigProvider` — registered via `META-INF/services`
- `ExerisRuntimeLifecycle implements SmartLifecycle` — triggers kernel bootstrap/shutdown
- `ExerisRuntimeProperties` — `@ConfigurationProperties(prefix = "exeris.runtime")`
- Minimal boot autoconfiguration wiring the above
- Integration test: Spring context + Exeris runtime start → log KERNEL READY → clean shutdown
- ArchUnit test: no Spring types in `eu.exeris.kernel.spi.*`

### Out of Scope

- HTTP port binding (Phase 1)
- Any request handling (Phase 1)
- Servlet API (Phase 2, opt-in only)
- Transactions (Phase 3)
- Persistence (Phase 3)

---

## Key Classes to Implement

### `ExerisRuntimeProperties`
**Package:** `eu.exeris.spring.boot.autoconfigure`  
**Module:** `exeris-spring-boot-autoconfigure`

```java
@ConfigurationProperties(prefix = "exeris.runtime")
public record ExerisRuntimeProperties(
    boolean enabled,
    int port,
    Mode mode,
    boolean gracefulShutdownEnabled,
    int gracefulShutdownTimeoutSeconds
) {
    public enum Mode { PURE, COMPATIBILITY }

    public ExerisRuntimeProperties() {
        this(true, 8080, Mode.PURE, true, 30);
    }
}
```

### `ExerisSpringConfigProvider`
**Package:** `eu.exeris.spring.boot.autoconfigure`  
**Module:** `exeris-spring-boot-autoconfigure`  
**Implements:** `eu.exeris.kernel.spi.config.ConfigProvider`  
**Registered via:** `META-INF/services/eu.exeris.kernel.spi.config.ConfigProvider`

Responsibility: read `exeris.*` properties from Spring `Environment` and return the kernel
`KernelSettings` record. Priority = 150 (higher than community default of 100).

Important: this class may only be created after the Spring `ApplicationContext` has been
refreshed. The `KernelBootstrap` is triggered by `ExerisRuntimeLifecycle`, which runs after
context refresh, so this ordering is safe.

### `ExerisRuntimeLifecycle`
**Package:** `eu.exeris.spring.boot.autoconfigure`  
**Module:** `exeris-spring-boot-autoconfigure`  
**Implements:** `org.springframework.context.SmartLifecycle`

Responsibilities:
- `getPhase()` returns `Integer.MAX_VALUE - 100` (start after most beans, stop before most beans)
- `start()` initialises `KernelBootstrap` using `ExerisSpringConfigProvider`
- `stop(Runnable callback)` triggers graceful kernel shutdown with timeout

This class must:
- NOT block the Spring `start()` call beyond bootstrap initialization
- preserve a reference to the running kernel instance for `isRunning()` and `stop()`

### `ExerisRuntimeAutoConfiguration`
**Package:** `eu.exeris.spring.boot.autoconfigure`  
**Module:** `exeris-spring-boot-autoconfigure`

```java
@Configuration
@ConditionalOnClass(name = "eu.exeris.kernel.spi.bootstrap.SubsystemProvider")
@EnableConfigurationProperties(ExerisRuntimeProperties.class)
@ConditionalOnProperty(prefix = "exeris.runtime", name = "enabled", matchIfMissing = true)
public class ExerisRuntimeAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ExerisRuntimeLifecycle exerisRuntimeLifecycle(
            ExerisRuntimeProperties properties,
            ExerisSpringConfigProvider configProvider) {
        return new ExerisRuntimeLifecycle(properties, configProvider);
    }
}
```

Registered in:
`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`

---

## Bootstrap Sequence Diagram

```
Spring ApplicationContext.refresh()
    → singleton beans created
    → ExerisRuntimeProperties populated from Environment
    → ExerisSpringConfigProvider bean created (Spring-managed, reads from Environment)
    → ExerisRuntimeLifecycle bean created
    → refresh() completes

SmartLifecycle.start() called (ordered)
    → ExerisRuntimeLifecycle.start()
        → KernelBootstrap.bootstrap(configProvider)
            → ServiceLoader → ConfigProvider → ExerisSpringConfigProvider (priority 150)
            → Memory subsystem init (off-heap)
            → Transport + Persistence subsystems init (parallel)
            → KERNEL READY signal
        → ExerisRuntimeLifecycle stores KernelHandle

[Application running]

SmartLifecycle.stop() called on shutdown
    → ExerisRuntimeLifecycle.stop(callback)
        → HttpServerEngine.closeIngress() (no new connections)
        → Drain in-flight requests (timeout: exeris.runtime.graceful-shutdown-timeout-seconds)
        → KernelBootstrap.shutdown()
        → callback.run()
```

---

## Integration Test Requirements

**Test: `ExerisBootstrapIntegrationTest`**
**Module:** `exeris-spring-boot-autoconfigure` (test scope)

Verifies:
1. Spring `ApplicationContext` loads without exception.
2. `ExerisRuntimeLifecycle.isRunning()` returns `true` after context start.
3. No Tomcat, Netty, or Servlet container starts.
4. Graceful `context.close()` results in `isRunning()` returning `false`.
5. No `ThreadLocal` operations on the virtual-thread-owned boot path.

**Architecture guard test: `WallIntegrityTest`**
**Module:** `exeris-spring-boot-autoconfigure` (test scope)
**Tool:** ArchUnit

Verifies:
1. No class in `eu.exeris.kernel.spi.*` imports `org.springframework.*`.
2. No class in `eu.exeris.kernel.core.*` imports `org.springframework.*`.
3. No class in `eu.exeris.spring.boot.autoconfigure` imports `eu.exeris.spring.runtime.web.*`.

---

## Exit Criteria

Phase 0 is complete when:

1. `mvn clean install` succeeds across all modules (compile + test).
2. `ExerisBootstrapIntegrationTest` passes: Spring starts, Exeris bootstraps, clean shutdown.
3. `WallIntegrityTest` passes: no Spring leakage into kernel.
4. No Tomcat/Netty dependency on effective classpath (verified by `mvn dependency:tree`).
5. Phase 1 invariants are documented.

---

## Risks

| Risk | Mitigation |
|:-----|:-----------|
| `KernelBootstrap` API not yet public in 0.5.0-SNAPSHOT | Coordinate with kernel team; may need stub or forward-looking API surface |
| ServiceLoader ordering conflict between `ExerisSpringConfigProvider` and kernel defaults | Priority = 150 resolves this; verified in integration test |
| Spring lifecycle ordering too early/late for kernel bootstrap | `SmartLifecycle.getPhase()` tuning; tested in bootstrap integration test |
| Off-heap init on the Spring thread causes carrier thread pinning | KernelBootstrap init is synchronous on a virtual thread — verified in POC |
