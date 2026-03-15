/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.boot.autoconfigure;

import eu.exeris.kernel.spi.config.ConfigProvider;
import eu.exeris.kernel.spi.config.KernelProfile;
import org.springframework.core.env.Environment;

import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

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
 * uses priority {@code 150}, which is higher than the community default of {@code 100}.
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

    private final Environment environment;

    public ExerisSpringConfigProvider(Environment environment) {
        this.environment = environment;
    }

    @Override
    public int priority() {
        return 150;
    }

    @Override
    public Supplier<KernelSettings> kernelSettings() {
        return () -> {
            KernelSettings defaults = KernelSettings.defaults();

            KernelProfile profile = environment
                    .getProperty("exeris.runtime.profile", KernelProfile.class, defaults.profile());

            long globalMemoryMb = environment
                    .getProperty("exeris.runtime.memory.global-mb", Long.class, defaults.globalMemoryMb());

            NetworkSettings defaultNetwork = defaults.network();
            NetworkSettings network = new NetworkSettings(
                    environment.getProperty("exeris.runtime.network.port", Integer.class, defaultNetwork.port()),
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
        return Optional.ofNullable(environment.getProperty(key));
    }

    @Override
    public Optional<Integer> getInt(String key) {
        return Optional.ofNullable(environment.getProperty(key, Integer.class));
    }

    @Override
    public Optional<Long> getLong(String key) {
        return Optional.ofNullable(environment.getProperty(key, Long.class));
    }

    @Override
    public Optional<Boolean> getBoolean(String key) {
        return Optional.ofNullable(environment.getProperty(key, Boolean.class));
    }

    @Override
    public <T> Optional<T> get(String key, Class<T> type) {
        return Optional.ofNullable(environment.getProperty(key, type));
    }

    @Override
    public void watch(String namespace, String key, Consumer<Object> listener) {
        // Spring Environment does not expose a standard cross-source watch API.
        // Phase 0 behavior: no-op callback registration.
    }
}
