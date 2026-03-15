/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.boot.autoconfigure;

import org.springframework.context.SmartLifecycle;

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

    private volatile boolean running = false;
    // KernelHandle will be stored here once KernelBootstrap API is confirmed.
    // private volatile KernelHandle kernelHandle;

    public ExerisRuntimeLifecycle(ExerisRuntimeProperties properties,
                                   ExerisSpringConfigProvider configProvider) {
        this.properties = properties;
        this.configProvider = configProvider;
    }

    @Override
    public void start() {
        if (!properties.enabled()) {
            return;
        }
        /*
         * Phase 0 implementation will call KernelBootstrap.bootstrap(configProvider) here.
         * The exact bootstrap API will be confirmed against exeris-kernel 0.5.0-SNAPSHOT.
         * This is the point where Spring yields runtime control to Exeris.
         */
        running = true;
    }

    @Override
    public void stop(Runnable callback) {
        stop();
        callback.run();
    }

    @Override
    public void stop() {
        if (!running) {
            return;
        }
        /*
         * Phase 0 implementation will:
         * 1. kernelHandle.transport().closeIngress()
         * 2. drain in-flight requests with timeout from properties.shutdown().timeoutSeconds()
         * 3. kernelHandle.shutdown()
         */
        running = false;
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
        return properties.enabled();
    }
}
