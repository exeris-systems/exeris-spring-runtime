/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.boot.autoconfigure;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.springframework.core.env.Environment;

import eu.exeris.kernel.spi.config.ConfigProvider;
import eu.exeris.kernel.spi.config.KernelProfile;

/**
 * Bridges the Spring {@link Environment} into the Exeris kernel {@link ConfigProvider} SPI.
 *
 * <h2>Discovery</h2>
 * <p>This class is registered as a {@code ServiceLoader} provider via:
 * <pre>
 * META-INF/services/eu.exeris.kernel.spi.config.ConfigProvider
 * </pre>
 * The kernel's {@code KernelBootstrap} discovers all {@code ConfigProvider} implementations
 * on the classpath and uses the one with the highest {@link #priority()}. This implementation
 * reports priority {@code 150} when a Spring {@link Environment} is available, and {@code 0}
 * in fixture-only bootstrap paths where no prepared {@code Environment} is present. That keeps
 * the Spring-backed provider preferred during normal application startup while deferring to the
 * kernel/community providers for isolated fixtures.
 *
 * <h2>The Wall</h2>
 * <p>This class is in {@code exeris-spring-runtime}, not in {@code exeris-kernel-spi} or
 * {@code exeris-kernel-core}. The kernel SPI ({@code ConfigProvider}) remains Spring-free.
 * Spring depends on Exeris; Exeris does not depend on Spring.
 *
 * <h2>Lifecycle Ordering</h2>
 * <p>This bean is created after the Spring {@code ApplicationContext} has been refreshed
 * (bean post-construction). The {@code KernelBootstrap} is triggered by
 * {@link ExerisRuntimeLifecycle#start()}, which runs after context refresh. Therefore,
 * the {@code Environment} is fully populated when this provider is first used.
 *
 * <h2>Threading</h2>
 * <p>{@link #kernelSettings()} returns an immutable {@code Supplier} suitable for
 * caching by the kernel. The {@code Environment} reference is safe to read from any
 * thread after context refresh.
 *
 * @since 0.1.0
 */
public final class ExerisSpringConfigProvider implements ConfigProvider {

    private static final System.Logger LOGGER =
            System.getLogger(ExerisSpringConfigProvider.class.getName());

    /**
     * Bounded static holder populated by {@link #prepareBootstrap()} immediately before
     * {@code KernelBootstrap.boot()} and cleared in the corresponding finally block.
     *
     * <p>This holder bridges the gap between ServiceLoader instantiation (which requires
     * a no-arg constructor) and the need for a Spring {@link Environment} during the
     * synchronous kernel bootstrap phase. It is never read outside that window and is
     * never used on the hot request path.
     */
    private static final AtomicReference<Environment> BOOTSTRAP_ENVIRONMENT = new AtomicReference<>();
    private static final AtomicBoolean BOOTSTRAP_PREPARED = new AtomicBoolean(false);
    private static final AtomicReference<Map<String, String>> LEGACY_HTTP_SYSTEM_PROPERTIES =
            new AtomicReference<>(Map.of());

    private final Environment environment;

    /**
     * No-arg constructor required by {@link java.util.ServiceLoader}.
     *
     * <p>The kernel's {@code KernelBootstrap.resolveConfigProvider()} discovers this
     * implementation via ServiceLoader and instantiates it through this constructor.
     * The {@link Environment} reference is obtained from {@link #BOOTSTRAP_ENVIRONMENT},
     * which must be set by {@link #prepareBootstrap()} before {@code boot()} is called.
     */
    public ExerisSpringConfigProvider() {
        this(BOOTSTRAP_ENVIRONMENT.get());
    }

    public ExerisSpringConfigProvider(Environment environment) {
        this.environment = environment;
    }

    /**
     * Makes this instance's {@link Environment} available for ServiceLoader-created instances
     * during the synchronous boot window.
     * Must be called immediately before {@code KernelBootstrap.boot()}.
     */
    void prepareBootstrap() {
        if (!BOOTSTRAP_PREPARED.compareAndSet(false, true)) {
            throw new IllegalStateException("Exeris bootstrap environment already prepared");
        }
        BOOTSTRAP_ENVIRONMENT.set(this.environment);
        publishLegacyHttpAliases(this.environment);
    }

    /**
     * Clears the bootstrap environment holder. Must be called in a finally block
     * after {@code KernelBootstrap.boot()} returns or throws.
     */
    void clearBootstrap() {
        restoreLegacyHttpAliases();
        BOOTSTRAP_ENVIRONMENT.set(null);
        BOOTSTRAP_PREPARED.set(false);
    }

    @Override
    public int priority() {
        // Prefer this provider only when a Spring Environment is actually available.
        // In fixture-only bootstrap paths (no prepared Environment), defer to kernel/community providers.
        //
        // NOTE: "defer" is no longer expressible by priority alone. Kernel 0.10.0 re-based the
        // open-core priorities to Community=0 / Enterprise=100 (kernel CHANGELOG 0.10.0, #217).
        // CommunityConfigProvider therefore now reports 0 — the same value this provider reports
        // in the no-Environment path — and KernelBootstrap.resolveConfigProvider() selects via
        // Stream.max, which keeps the FIRST element on a tie, i.e. ServiceLoader classpath order.
        // The SPI contracts priority as ">= 0", so there is no value meaning "abstain".
        // Consequence: this provider can win the tie while holding no Environment, so the
        // no-Environment path must still answer lookups rather than returning empty — see
        // systemPropertyFallback(...). Do not "fix" that by returning a negative priority; it
        // would violate the SPI contract.
        return environment == null ? 0 : 150;
    }

    /**
     * Fallback used only by the no-{@link Environment} instance (fixture/bootstrap paths).
     *
     * <p>Without this, an instance created by {@code ServiceLoader} before
     * {@link #prepareBootstrap()} answers every lookup with {@link Optional#empty()}. That was
     * harmless while the community provider outranked it, but since kernel 0.10.0 the two tie at
     * priority {@code 0} and this provider can be selected instead — silently blanking kernel
     * configuration that the caller supplied via system properties (e.g. the kernel testkit's
     * {@code exeris.http.mode=SERVER}, without which the HTTP subsystem builds no server engine
     * and {@code HTTP_SERVER_ENGINE} is never bound).
     *
     * <p>Reading system properties here matches what the community provider does for the same
     * keys, so the selection tie stops being observable.
     */
    private static Optional<String> systemPropertyFallback(String key) {
        return Optional.ofNullable(System.getProperty(key));
    }

    /**
     * Parses a system-property fallback value as an {@link Integer}, degrading to
     * {@link Optional#empty()} rather than propagating {@link NumberFormatException}.
     *
     * <p>Before the fallback existed, the no-{@link Environment} path answered every lookup with
     * {@code Optional.empty()} and could not throw. Parsing raw system properties reintroduces a
     * throwing path into a kernel SPI method called during bootstrap, where an unrelated stray
     * property (e.g. {@code -Dexeris.runtime.network.port=abc}) would abort the boot instead of
     * letting the kernel apply its own default. Returning empty restores the previous
     * non-throwing contract.
     *
     * <p>The malformed value is logged rather than swallowed: silently booting on a default port
     * because a supplied value was unparseable is precisely the kind of hidden cost this repo
     * refuses to ship.
     */
    private static Optional<Integer> parseIntOrWarn(String key, String value) {
        try {
            return Optional.of(Integer.valueOf(value));
        } catch (NumberFormatException ex) {
            warnUnparseable(key, value, "int");
            return Optional.empty();
        }
    }

    /**
     * {@link Long} counterpart of {@link #parseIntOrWarn(String, String)}; same rationale.
     */
    private static Optional<Long> parseLongOrWarn(String key, String value) {
        try {
            return Optional.of(Long.valueOf(value));
        } catch (NumberFormatException ex) {
            warnUnparseable(key, value, "long");
            return Optional.empty();
        }
    }

    private static void warnUnparseable(String key, String value, String targetType) {
        LOGGER.log(System.Logger.Level.WARNING,
                () -> "Exeris kernel config key '" + key + "' has system-property value '" + value
                        + "' which is not a valid " + targetType
                        + "; ignoring it and letting the kernel apply its default.");
    }

    @Override
    public Supplier<KernelSettings> kernelSettings() {
        return () -> {
                if (environment == null) {
                    return KernelSettings.defaults();
                }
                KernelSettings defaults = KernelSettings.defaults();

            KernelProfile profile = environment
                    .getProperty("exeris.runtime.profile", KernelProfile.class, defaults.profile());

            long globalMemoryMb = environment
                    .getProperty("exeris.runtime.memory.global-mb", Long.class, defaults.globalMemoryMb());

            NetworkSettings defaultNetwork = defaults.network();
            Integer configuredPort = environment.getProperty("exeris.runtime.network.port", Integer.class);
            if (configuredPort == null) {
                configuredPort = environment.getProperty("server.port", Integer.class, defaultNetwork.port());
            }
            NetworkSettings network = new NetworkSettings(
                    configuredPort,
                    environment.getProperty("exeris.runtime.network.buffer-size", Integer.class, defaultNetwork.bufferSize()),
                    environment.getProperty("exeris.runtime.network.native-transport-preferred", Boolean.class, defaultNetwork.nativeTransportPreferred()),
                    environment.getProperty("exeris.runtime.network.reactor-count", Integer.class, defaultNetwork.reactorCount()),
                    environment.getProperty("exeris.runtime.network.quic-enabled", Boolean.class, defaultNetwork.quicEnabled())
            );

            PersistenceSettings defaultPersistence = defaults.persistence();
            PersistenceSettings persistence = new PersistenceSettings(
                    environment.getProperty("exeris.runtime.persistence.jdbc-url", defaultPersistence.jdbcUrl()),
                    environment.getProperty("exeris.runtime.persistence.username", defaultPersistence.username()),
                    environment.getProperty("exeris.runtime.persistence.password", defaultPersistence.password()),
                    environment.getProperty("exeris.runtime.persistence.max-pool-size", Integer.class, defaultPersistence.maxPoolSize()),
                    environment.getProperty("exeris.runtime.persistence.run-migrations", Boolean.class, defaultPersistence.runMigrations())
            );

            TelemetrySettings defaultTelemetry = defaults.telemetry();
            TelemetrySettings telemetry = new TelemetrySettings(
                    environment.getProperty("exeris.runtime.telemetry.jfr-enabled", Boolean.class, defaultTelemetry.jfrEnabled()),
                    environment.getProperty("exeris.runtime.telemetry.metrics-enabled", Boolean.class, defaultTelemetry.metricsEnabled()),
                    environment.getProperty("exeris.runtime.telemetry.tracing-enabled", Boolean.class, defaultTelemetry.tracingEnabled()),
                    environment.getProperty("exeris.runtime.telemetry.node-id", defaultTelemetry.nodeId()),
                    environment.getProperty("exeris.runtime.telemetry.region", defaultTelemetry.region())
            );

            return new KernelSettings(profile, globalMemoryMb, network, persistence, telemetry);
        };
    }

    @Override
    public Optional<String> getString(String key) {
        if (environment == null) {
            return systemPropertyFallback(key);
        }
        String direct = environment.getProperty(key);
        if (direct != null) {
            return Optional.of(direct);
        }
        Optional<String> legacy = legacyHttpStringAlias(key, environment);
        if (legacy.isPresent()) {
            return legacy;
        }
        return flowKernelKeyAlias(key, environment, String.class)
                .or(() -> persistenceKernelKeyAlias(key, environment, String.class));
    }

    @Override
    public Optional<Integer> getInt(String key) {
        if (environment == null) {
            return systemPropertyFallback(key).flatMap(value -> parseIntOrWarn(key, value));
        }
        Integer direct = environment.getProperty(key, Integer.class);
        if (direct != null) {
            return Optional.of(direct);
        }
        Optional<Integer> legacy = legacyHttpIntAlias(key, environment);
        if (legacy.isPresent()) {
            return legacy;
        }
        return flowKernelKeyAlias(key, environment, Integer.class)
                .or(() -> persistenceKernelKeyAlias(key, environment, Integer.class));
    }

    @Override
    public Optional<Long> getLong(String key) {
        if (environment == null) {
            return systemPropertyFallback(key).flatMap(value -> parseLongOrWarn(key, value));
        }
        Long direct = environment.getProperty(key, Long.class);
        if (direct != null) {
            return Optional.of(direct);
        }
        return flowKernelKeyAlias(key, environment, Long.class)
                .or(() -> persistenceKernelKeyAlias(key, environment, Long.class));
    }

    @Override
    public Optional<Boolean> getBoolean(String key) {
        if (environment == null) {
            return systemPropertyFallback(key).map(Boolean::valueOf);
        }
        Boolean direct = environment.getProperty(key, Boolean.class);
        if (direct != null) {
            return Optional.of(direct);
        }
        return flowKernelKeyAlias(key, environment, Boolean.class)
                .or(() -> persistenceKernelKeyAlias(key, environment, Boolean.class));
    }

    @Override
    public <T> Optional<T> get(String key, Class<T> type) {
        if (environment == null) {
            if (type == String.class) {
                return systemPropertyFallback(key).map(type::cast);
            }
            if (type == Integer.class) {
                return systemPropertyFallback(key)
                        .flatMap(value -> parseIntOrWarn(key, value))
                        .map(type::cast);
            }
            if (type == Long.class) {
                return systemPropertyFallback(key)
                        .flatMap(value -> parseLongOrWarn(key, value))
                        .map(type::cast);
            }
            if (type == Boolean.class) {
                return systemPropertyFallback(key).map(Boolean::valueOf).map(type::cast);
            }
            return Optional.empty();
        }
        T direct = environment.getProperty(key, type);
        if (direct != null) {
            return Optional.of(direct);
        }
        if (type == String.class) {
            Optional<String> legacy = legacyHttpStringAlias(key, environment);
            if (legacy.isPresent()) {
                return legacy.map(type::cast);
            }
            return flowKernelKeyAlias(key, environment, type)
                    .or(() -> persistenceKernelKeyAlias(key, environment, type));
        }
        if (type == Integer.class) {
            Optional<Integer> legacy = legacyHttpIntAlias(key, environment);
            if (legacy.isPresent()) {
                return legacy.map(type::cast);
            }
            return flowKernelKeyAlias(key, environment, type)
                    .or(() -> persistenceKernelKeyAlias(key, environment, type));
        }
        return flowKernelKeyAlias(key, environment, type)
                .or(() -> persistenceKernelKeyAlias(key, environment, type));
    }

    @Override
    public void watch(String namespace, String key, Consumer<Object> listener) {
        // Spring Environment does not expose a standard cross-source watch API.
        // Phase 0 behavior: no-op callback registration.
    }

    private static void publishLegacyHttpAliases(Environment environment) {
        Map<String, String> previous = captureLegacyHttpSystemProperties();
        LEGACY_HTTP_SYSTEM_PROPERTIES.set(previous);

        if (environment == null) {
            return;
        }

        Integer port = legacyHttpIntAlias("exeris.http.port", environment).orElse(null);
        String mode = legacyHttpStringAlias("exeris.http.mode", environment).orElse(null);
        String bindHost = legacyHttpStringAlias("exeris.http.bindHost", environment).orElse(null);

        setIfPresent("exeris.http.mode", mode);
        setIfPresent("http.mode", mode);
        setIfPresent("exeris.http.bindHost", bindHost);
        setIfPresent("http.bindHost", bindHost);
        if (port != null) {
            setIfPresent("exeris.http.port", Integer.toString(port));
            setIfPresent("http.port", Integer.toString(port));
        }
    }

    private static void restoreLegacyHttpAliases() {
        Map<String, String> previous = LEGACY_HTTP_SYSTEM_PROPERTIES.getAndSet(Map.of());
        previous.forEach((key, value) -> {
            if (value == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, value);
            }
        });
    }

    private static Map<String, String> captureLegacyHttpSystemProperties() {
        Map<String, String> previous = new LinkedHashMap<>();
        previous.put("exeris.http.mode", System.getProperty("exeris.http.mode"));
        previous.put("exeris.http.bindHost", System.getProperty("exeris.http.bindHost"));
        previous.put("exeris.http.port", System.getProperty("exeris.http.port"));
        previous.put("http.mode", System.getProperty("http.mode"));
        previous.put("http.bindHost", System.getProperty("http.bindHost"));
        previous.put("http.port", System.getProperty("http.port"));
        return previous;
    }

    private static Optional<String> legacyHttpStringAlias(String key, Environment environment) {
        return switch (key) {
            case "exeris.http.mode", "http.mode" -> Optional.of("SERVER");
            case "exeris.http.bindHost", "http.bindHost" -> Optional.ofNullable(
                    firstNonBlank(
                            environment.getProperty("exeris.runtime.network.bind-host"),
                            environment.getProperty("server.address"),
                            System.getProperty("exeris.http.bindHost"),
                            "0.0.0.0"
                    ));
            default -> Optional.empty();
        };
    }

    private static Optional<Integer> legacyHttpIntAlias(String key, Environment environment) {
        if (!"exeris.http.port".equals(key) && !"http.port".equals(key)) {
            return Optional.empty();
        }
        Integer configuredPort = environment.getProperty("exeris.runtime.network.port", Integer.class);
        if (configuredPort == null) {
            configuredPort = environment.getProperty("server.port", Integer.class);
        }
        return Optional.ofNullable(configuredPort);
    }

    private static void setIfPresent(String key, String value) {
        if (value != null && !value.isBlank()) {
            System.setProperty(key, value);
        }
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    /**
     * Bridges kernel-side {@code flow.X} configuration lookups to the Spring property
     * surface under {@code exeris.runtime.flow.*}. Returns {@link Optional#empty()} for
     * keys that don't start with the {@code flow.} prefix.
     *
     * <p>The kernel's {@code CommunityFlowSubsystem.buildFlowConfig()} reads keys such as
     * {@code flow.persistenceEnabled}, {@code flow.maxConcurrentFlows}, etc. directly from
     * the {@link eu.exeris.kernel.spi.config.ConfigProvider} (camelCase, no namespace
     * prefix). Spring application properties live under {@code exeris.runtime.flow.*}.
     * This alias adds the prefix so kernel lookups resolve to the Spring values.
     *
     * <p>Both naming conventions are attempted — see {@link #namespaceAlias} for why.
     *
     * <p>Without this bridge, setting {@code exeris.runtime.flow.persistence-enabled=true}
     * in Spring config has no effect on the kernel's flow subsystem (the lookup misses,
     * the kernel falls back to {@code FlowEngineConfig.defaults()}, and saga state stays
     * in-memory). This is the second half of the Phase 4B Step 4 closure pair with
     * kernel ADR-022 (which fixed the kernel-side wiring of {@code JdbcFlowSnapshotStore}).
     */
    private static <T> Optional<T> flowKernelKeyAlias(String key, Environment environment, Class<T> type) {
        return namespaceAlias(key, "flow.", environment, type);
    }

    /**
     * Maps a kernel key {@code <namespace>.<suffix>} onto the Spring property
     * {@code exeris.runtime.<namespace>.<suffix>}, trying camelCase first and kebab-case second.
     * Returns {@link Optional#empty()} when the key is outside the namespace or neither form is set.
     *
     * <p>Both naming conventions are attempted because Spring's relaxed binding only applies
     * automatically when {@code ConfigurationPropertySources} is attached to the {@link Environment}
     * — true in a full Spring Boot context, but NOT for a bare {@code MockEnvironment} (used in some
     * integration tests). Whichever form is present wins.
     *
     * <p>Shared by {@link #flowKernelKeyAlias} and the generic tail of
     * {@link #persistenceKernelKeyAlias}; those two carry the rationale for why each namespace needs
     * bridging at all, which is not the same rationale in both cases.
     *
     * @param kernelPrefix kernel-side namespace including the trailing dot, e.g. {@code "flow."}
     */
    private static <T> Optional<T> namespaceAlias(String key,
                                                  String kernelPrefix,
                                                  Environment environment,
                                                  Class<T> type) {
        if (!key.startsWith(kernelPrefix)) {
            return Optional.empty();
        }
        String springPrefix = "exeris.runtime." + kernelPrefix;
        String suffix = key.substring(kernelPrefix.length());
        T direct = environment.getProperty(springPrefix + suffix, type);
        if (direct != null) {
            return Optional.of(direct);
        }
        String kebab = camelToKebab(suffix);
        if (!kebab.equals(suffix)) {
            T kebabValue = environment.getProperty(springPrefix + kebab, type);
            if (kebabValue != null) {
                return Optional.of(kebabValue);
            }
        }
        return Optional.empty();
    }

    /**
     * Bridges kernel-side persistence-pool sizing lookups to the Spring property surface
     * under {@code exeris.runtime.persistence.*}. Returns {@link Optional#empty()} for any
     * key outside the small fixed set below.
     *
     * <p>The kernel's {@code CommunityPersistenceConfigResolver} builds its
     * {@link eu.exeris.kernel.spi.persistence.PersistenceConfig} by asking this
     * {@link eu.exeris.kernel.spi.config.ConfigProvider} for raw keys — including
     * {@code persistence.minIdleConnections} (alias {@code persistence.pool.minSize}), the
     * pool-warmup keys {@code persistence.pool.warmup.enabled} / {@code .connections} (bare
     * aliases {@code pool.warmup.*}), and {@code persistence.connectionTimeoutMs} (read via
     * {@code getLong}).
     *
     * <p><b>The raw key API is the only path that reaches the pool.</b> The typed
     * {@link PersistenceSettings} record carries {@code maxPoolSize}, but the resolver reads that
     * record for {@code jdbcUrl} / {@code username} / {@code password} / {@code runMigrations}
     * <em>only</em>; pool sizing is resolved exclusively from raw keys
     * ({@code resolveMaxPoolSize} tries {@code persistence.maxPoolSize}, then
     * {@code persistence.pool.maxSize}, then falls back to an adaptive
     * {@code clamp(availableProcessors() * 2, 2, 32)}). Populating
     * {@code PersistenceSettings.maxPoolSize()} from {@link #kernelSettings()} therefore has no
     * effect on the pool at all — no kernel code reads that field.
     *
     * <p>This provider wins {@code ConfigProvider} selection outright:
     * {@code KernelBootstrap.resolveConfigProvider()} picks a <em>single</em> winner via
     * {@code Stream.max(comparingInt(ConfigProvider::priority))}, so at priority 150 it displaces
     * {@code CommunityConfigProvider} entirely rather than layering on top of it. Every raw key
     * this class answers with {@link Optional#empty()} is a key the kernel resolves from its own
     * hardcoded default — the application's Spring configuration is silently discarded, with no
     * warning on either side. That is why the alias table must mirror the kernel's raw-key surface
     * rather than the subset we happen to have needed so far, and why the generic
     * {@code persistence.*} tail below exists.
     *
     * <p><b>Regression this closes.</b> Before the generic tail, min-idle was aliased and
     * max-pool-size was not. An application setting
     * {@code exeris.runtime.persistence.min-pool-size=16} together with
     * {@code .max-pool-size=256} had its min honoured and its max dropped, so on a host pinned to
     * four CPUs the kernel derived {@code maxPoolSize=8} and
     * {@link eu.exeris.kernel.spi.persistence.PersistenceConfig} rejected the pair at boot:
     * {@code IllegalArgumentException: minIdleConnections (16) > maxPoolSize (8)}. The asymmetry
     * was ours — a clean kernel resolves both halves from one source and cannot split them.
     * Note the two keys are declared as constants in the resolver rather than inline literals,
     * which is why a grep of the kernel for quoted key names does not surface them.
     *
     * <p>Without this alias those raw lookups miss (a {@code MockEnvironment} or a Spring
     * {@link Environment} has no literal {@code persistence.minIdleConnections} property), the
     * kernel falls back to its default min-idle (~1), and the shared pool
     * ({@code exeris-community-shared}) starts cold: a burst of concurrent virtual threads at
     * startup races pool growth from 1 → max and some acquisitions time out
     * ({@code PersistenceProviderException.connectionExhausted} → 500) until the pool warms.
     * Mapping the raw kernel keys onto {@code exeris.runtime.persistence.min-pool-size} and
     * {@code .pool-warmup-{enabled,connections}} lets an application pre-warm the shared pool the
     * same way a Spring/Hikari or Quarkus/Agroal target does via its native min-idle knob.
     *
     * <p><b>Connection-timeout and fair-leveling.</b> Pre-warm blunts cold-start, but a sustained
     * error spike can remain under load: the kernel pool fail-fasts on acquisition (a short acquire
     * timeout {@code ->} {@code connectionExhausted} {@code ->} 500) where a default Spring/Hikari
     * pool <em>blocks</em> for ~30s (contention surfaces as latency, not errors). Without exposing
     * {@code connection-timeout-ms}, that asymmetry cannot be levelled from configuration. Mapping
     * it lets a deployment set the same acquire timeout the JDBC-native targets use, so contention
     * shows up as latency on both sides rather than 500s on the compat path only.
     *
     * <p>Symmetric with {@link #flowKernelKeyAlias(String, Environment, Class)} (Phase 4B Step 4).
     */
    private static <T> Optional<T> persistenceKernelKeyAlias(String key, Environment environment, Class<T> type) {
        String springKey = switch (key) {
            case "persistence.maxPoolSize", "persistence.pool.maxSize" ->
                    "exeris.runtime.persistence.max-pool-size";
            case "persistence.minIdleConnections", "persistence.pool.minSize" ->
                    "exeris.runtime.persistence.min-pool-size";
            case "persistence.pool.warmup.enabled", "pool.warmup.enabled" ->
                    "exeris.runtime.persistence.pool-warmup-enabled";
            case "persistence.pool.warmup.connections", "pool.warmup.connections" ->
                    "exeris.runtime.persistence.pool-warmup-connections";
            case "persistence.connectionTimeoutMs" ->
                    "exeris.runtime.persistence.connection-timeout-ms";
            default -> null;
        };
        if (springKey != null) {
            return Optional.ofNullable(environment.getProperty(springKey, type));
        }
        // Generic tail. The switch above only covers keys whose Spring-side name differs from a
        // mechanical translation (pool sizing is exposed as min-/max-pool-size, warmup is flattened
        // out of its dotted kernel namespace, and two warmup keys are also read without the
        // `persistence.` prefix). Every other `persistence.X` lookup maps onto
        // `exeris.runtime.persistence.X` via the same namespace rule the flow alias uses. This
        // covers idleTimeoutMs, maxLifetimeMs, maxTenantPools, rlsEnabled, perTenantPooling and
        // useTls, none of which had a path before, and keeps future kernel keys working without a
        // code change here.
        return namespaceAlias(key, "persistence.", environment, type);
    }

    /**
     * Converts {@code camelCaseName} → {@code camel-case-name}. Pure ASCII; no Unicode
     * letter-class handling needed for kernel config key suffixes (all known suffixes
     * are pure ASCII identifiers).
     */
    private static String camelToKebab(String camel) {
        StringBuilder sb = new StringBuilder(camel.length() + 4);
        for (int i = 0; i < camel.length(); i++) {
            char c = camel.charAt(i);
            if (i > 0 && Character.isUpperCase(c)) {
                sb.append('-');
            }
            sb.append(Character.toLowerCase(c));
        }
        return sb.toString();
    }
}
