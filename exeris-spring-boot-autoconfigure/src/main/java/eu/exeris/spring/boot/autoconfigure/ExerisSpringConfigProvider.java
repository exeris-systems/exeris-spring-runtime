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
        return environment == null ? 0 : 150;
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
            return Optional.empty();
        }
        String direct = environment.getProperty(key);
        if (direct != null) {
            return Optional.of(direct);
        }
        return legacyHttpStringAlias(key, environment);
    }

    @Override
    public Optional<Integer> getInt(String key) {
        if (environment == null) {
            return Optional.empty();
        }
        Integer direct = environment.getProperty(key, Integer.class);
        if (direct != null) {
            return Optional.of(direct);
        }
        return legacyHttpIntAlias(key, environment);
    }

    @Override
    public Optional<Long> getLong(String key) {
        return environment == null ? Optional.empty()
            : Optional.ofNullable(environment.getProperty(key, Long.class));
    }

    @Override
    public Optional<Boolean> getBoolean(String key) {
        return environment == null ? Optional.empty()
            : Optional.ofNullable(environment.getProperty(key, Boolean.class));
    }

    @Override
    public <T> Optional<T> get(String key, Class<T> type) {
        if (environment == null) {
            return Optional.empty();
        }
        T direct = environment.getProperty(key, type);
        if (direct != null) {
            return Optional.of(direct);
        }
        if (type == String.class) {
            return legacyHttpStringAlias(key, environment).map(type::cast);
        }
        if (type == Integer.class) {
            return legacyHttpIntAlias(key, environment).map(type::cast);
        }
        return Optional.empty();
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
}
