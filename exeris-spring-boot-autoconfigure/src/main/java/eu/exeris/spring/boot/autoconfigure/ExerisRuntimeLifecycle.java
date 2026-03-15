/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.boot.autoconfigure;

import eu.exeris.kernel.core.bootstrap.KernelBootstrap;
import eu.exeris.kernel.spi.http.HttpHandler;
import eu.exeris.kernel.spi.http.HttpKernelProviders;
import eu.exeris.kernel.spi.http.HttpServerEngine;
import org.springframework.context.SmartLifecycle;
import org.springframework.lang.NonNull;

import java.util.Optional;

/**
 * Spring lifecycle coordinator for the Exeris runtime.
 *
 * <p>This bean bridges Spring's {@link SmartLifecycle} with the Exeris
 * {@code KernelBootstrap} start/shutdown sequence. It is the single point where
 * Spring yields runtime control to Exeris.
 *
 * <h2>Start Sequence</h2>
 * <pre>
 * Spring ApplicationContext.refresh() completes
 *   → SmartLifecycle.start() called (ordered by phase)
 *   → ExerisRuntimeLifecycle.start()
 *       → KernelBootstrap.bootstrap(configProvider)
 *           → ServiceLoader discovers SubsystemProviders
 *           → subsystems initialise in DAG order (Config → Memory → Transport ...)
 *           → KERNEL READY
 *       → stores KernelHandle for stop() and isRunning()
 * </pre>
 *
 * <h2>Stop Sequence</h2>
 * <pre>
 * Spring shutdown (SIGTERM / context.close())
 *   → SmartLifecycle.stop(callback) called
 *   → ExerisRuntimeLifecycle.stop()
 *       → transport.closeIngress()      (no new connections)
 *       → drain in-flight requests      (up to gracefulShutdownTimeoutSeconds)
 *       → KernelBootstrap.shutdown()
 *       → callback.run()
 * </pre>
 *
 * <h2>Phase Ordering</h2>
 * <p>Phase {@code Integer.MAX_VALUE - 100} ensures this lifecycle starts after
 * virtually all application beans (database connections, caches, etc.) and
 * stops before them — giving application code the chance to finish using Exeris
 * resources before the runtime tears down.
 *
 * <h2>Runtime Ownership</h2>
 * <p>After {@link #start()} returns successfully, Exeris owns the transport layer.
 * Spring does not directly manage sockets, connections, or the request execution path.
 *
 * @since 0.1.0
 */
public final class ExerisRuntimeLifecycle implements SmartLifecycle {

    private static final int PHASE = Integer.MAX_VALUE - 100;

    private final ExerisRuntimeProperties properties;
    private final ExerisSpringConfigProvider configProvider;
    private final Optional<HttpHandler> httpHandler;

    private volatile boolean running = false;
    private volatile HttpServerEngine serverEngine;
    private volatile KernelBootstrap bootstrap;

    public ExerisRuntimeLifecycle(ExerisRuntimeProperties properties,
                                   ExerisSpringConfigProvider configProvider,
                                   Optional<HttpHandler> httpHandler) {
        this.properties = properties;
        this.configProvider = configProvider;
        this.httpHandler = httpHandler;
    }

    @Override
    public void start() {
        if (!properties.enabled() || running) {
            return;
        }
        KernelBootstrap kernelBootstrap = KernelBootstrap.builder()
                .classLoader(Thread.currentThread().getContextClassLoader())
                .build();
        configProvider.prepareBootstrap();
        try {
            kernelBootstrap.boot(() -> httpHandler.ifPresent(handler -> {
                HttpServerEngine engine = HttpKernelProviders.httpServerEngine();
                this.serverEngine = engine;
                engine.setHandler(handler);
                engine.start();
            }));
            this.bootstrap = kernelBootstrap;
            running = true;
        } catch (KernelBootstrap.BootstrapException ex) {
            throw new IllegalStateException("Exeris kernel bootstrap failed", ex);
        } finally {
            configProvider.clearBootstrap();
        }
    }

    @Override
    public void stop(@NonNull Runnable callback) {
        stop();
        callback.run();
    }

    @Override
    public void stop() {
        HttpServerEngine engine = this.serverEngine;
        KernelBootstrap kernelBootstrap = this.bootstrap;
        if (!running && engine == null && kernelBootstrap == null) {
            return;
        }

        this.running = false;
        this.serverEngine = null;
        this.bootstrap = null;

        if (engine != null) {
            engine.stop();
        }
        shutdownKernel(kernelBootstrap);
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return PHASE;
    }

    @Override
    public boolean isAutoStartup() {
        return properties.autoStart();
    }

    private static void shutdownKernel(KernelBootstrap bootstrap) {
        if (bootstrap == null) {
            return;
        }
        if (invokeNoArg(bootstrap, "shutdown")) {
            return;
        }
        invokeNoArg(bootstrap, "close");
    }

    private static boolean invokeNoArg(Object target, String methodName) {
        try {
            target.getClass().getMethod(methodName).invoke(target);
            return true;
        } catch (NoSuchMethodException ex) {
            return false;
        } catch (ReflectiveOperationException | RuntimeException ex) {
            return true;
        }
    }
}
