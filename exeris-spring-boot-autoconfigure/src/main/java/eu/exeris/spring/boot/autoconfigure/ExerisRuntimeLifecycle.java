/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.boot.autoconfigure;

import java.lang.reflect.InvocationTargetException;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.context.SmartLifecycle;
import org.springframework.lang.NonNull;

import eu.exeris.kernel.core.bootstrap.KernelBootstrap;
import eu.exeris.kernel.spi.http.HttpHandler;
import eu.exeris.kernel.spi.http.HttpKernelProviders;

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
 *       → creates KernelBootstrap and launches the lifecycle boot thread
 *       → optionally binds the HTTP handler seam via ScopedValue.where(...)
 *       → KernelBootstrap.boot(...)
 *           → keeps the boot scope open until stop() releases it
 *           → KERNEL READY
 *       → stores KernelBootstrap for stop() and isRunning()
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
    private final Object lifecycleMonitor = new Object();

    private volatile boolean running = false;
    private boolean starting = false;
    private boolean stopRequested = false;
    private final AtomicReference<KernelBootstrap> bootstrap = new AtomicReference<>();
    private final AtomicReference<CountDownLatch> heldBootScope = new AtomicReference<>();
    private final AtomicReference<Thread> bootThread = new AtomicReference<>();

    public ExerisRuntimeLifecycle(ExerisRuntimeProperties properties,
                                   ExerisSpringConfigProvider configProvider,
                                   Optional<HttpHandler> httpHandler) {
        this.properties = properties;
        this.configProvider = configProvider;
        this.httpHandler = httpHandler;
    }

    @Override
    public void start() {
        synchronized (lifecycleMonitor) {
            if (!properties.enabled() || running || starting || bootThread.get() != null) {
                return;
            }
            starting = true;
            stopRequested = false;
        }

        KernelBootstrap kernelBootstrap = KernelBootstrap.builder()
                .classLoader(Thread.currentThread().getContextClassLoader())
                .build();
        CountDownLatch bootReady = new CountDownLatch(1);
        CountDownLatch releaseSignal = new CountDownLatch(1);
        AtomicReference<Exception> bootFailure = new AtomicReference<>();

        Thread thread = Thread.ofPlatform()
                .name("exeris-runtime-lifecycle")
                .daemon(false)
                .unstarted(() -> runKernelLifetime(kernelBootstrap, bootReady, releaseSignal, bootFailure));

        this.bootstrap.set(kernelBootstrap);
        heldBootScope.set(releaseSignal);
        bootThread.set(thread);
        thread.start();

        try {
            awaitStartup(bootReady, releaseSignal, kernelBootstrap, thread);
        } catch (IllegalStateException ex) {
            synchronized (lifecycleMonitor) {
                starting = false;
                stopRequested = true;
                running = false;
                lifecycleMonitor.notifyAll();
            }
            throw ex;
        }

        Exception failure = bootFailure.get();
        boolean shutdownImmediately;
        synchronized (lifecycleMonitor) {
            starting = false;
            shutdownImmediately = stopRequested;
            if (failure == null && !shutdownImmediately) {
                this.bootstrap.set(kernelBootstrap);
                running = true;
            }
            lifecycleMonitor.notifyAll();
        }

        if (failure != null) {
            throw failedStart(kernelBootstrap, failure);
        }
        if (shutdownImmediately) {
            releaseBootScope(releaseSignal);
            shutdownKernel(kernelBootstrap);
        }
    }

    @Override
    public void stop(@NonNull Runnable callback) {
        stop();
        callback.run();
    }

    @Override
    public void stop() {
        KernelBootstrap kernelBootstrap;
        CountDownLatch releaseSignal;
        Thread thread;
        boolean startupInProgress;
        synchronized (lifecycleMonitor) {
            startupInProgress = starting;
            if (startupInProgress) {
                stopRequested = true;
            }
            kernelBootstrap = this.bootstrap.getAndSet(null);
            releaseSignal = heldBootScope.getAndSet(null);
            thread = bootThread.get();
            if (!running && !startupInProgress && kernelBootstrap == null && releaseSignal == null && thread == null) {
                return;
            }
            this.running = false;
        }
        releaseBootScope(releaseSignal);
        shutdownKernel(kernelBootstrap);
        awaitStopCompletion(thread, startupInProgress);
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

    private void runKernelLifetime(KernelBootstrap kernelBootstrap,
                                   CountDownLatch bootReady,
                                   CountDownLatch releaseSignal,
                                   AtomicReference<Exception> bootFailure) {
        configProvider.prepareBootstrap();
        try {
            bootKernel(kernelBootstrap, bootReady, releaseSignal);
        } catch (KernelBootstrap.BootstrapException | RuntimeException ex) {
            bootFailure.set(ex);
        } finally {
            bootReady.countDown();
            configProvider.clearBootstrap();
            heldBootScope.compareAndSet(releaseSignal, null);
            bootThread.compareAndSet(Thread.currentThread(), null);
            synchronized (lifecycleMonitor) {
                if (this.bootstrap.compareAndSet(kernelBootstrap, null)) {
                    running = false;
                }
                starting = false;
                lifecycleMonitor.notifyAll();
            }
        }
    }

    private void bootKernel(KernelBootstrap kernelBootstrap,
                            CountDownLatch bootReady,
                            CountDownLatch releaseSignal) throws KernelBootstrap.BootstrapException {
        Runnable holdKernelScopeOpen = () -> {
            configProvider.clearBootstrap();
            bootReady.countDown();
            awaitStopSignal(releaseSignal);
        };

        if (httpHandler.isEmpty()) {
            kernelBootstrap.boot(holdKernelScopeOpen);
            return;
        }

        try {
            ScopedValue.where(HttpKernelProviders.HTTP_SERVER_HANDLER, httpHandler.get())
                    .call(() -> {
                        kernelBootstrap.boot(holdKernelScopeOpen);
                        return null;
                    });
        } catch (Exception ex) {
            if (ex instanceof KernelBootstrap.BootstrapException bootstrapException) {
                throw bootstrapException;
            }
            if (ex instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("Exeris runtime startup failed while binding the kernel HTTP handler seam", ex);
        }
    }

    private void awaitStartup(CountDownLatch bootReady,
                              CountDownLatch releaseSignal,
                              KernelBootstrap kernelBootstrap,
                              Thread thread) {
        long timeoutMillis = startupTimeoutMillis();
        try {
            if (bootReady.await(timeoutMillis, TimeUnit.MILLISECONDS)) {
                return;
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            IllegalStateException failure =
                    new IllegalStateException("Exeris runtime startup was interrupted", ex);
            bestEffortStartupCleanup(releaseSignal, kernelBootstrap, thread, failure);
            throw failure;
        }

        IllegalStateException failure = new IllegalStateException(
                "Exeris runtime startup timed out after " + timeoutMillis
                        + " ms waiting for kernel bootstrap readiness"
        );
        bestEffortStartupCleanup(releaseSignal, kernelBootstrap, thread, failure);
        throw failure;
    }

    private long startupTimeoutMillis() {
        return Math.max(1L, properties.lifecycle().startupTimeoutSeconds()) * 1_000L;
    }

    private long shutdownTimeoutMillis() {
        return Math.max(1L, properties.shutdown().timeoutSeconds()) * 1_000L;
    }

    private void bestEffortStartupCleanup(CountDownLatch releaseSignal,
                                          KernelBootstrap kernelBootstrap,
                                          Thread thread,
                                          RuntimeException failure) {
        heldBootScope.compareAndSet(releaseSignal, null);
        releaseBootScope(releaseSignal);

        try {
            configProvider.clearBootstrap();
        } catch (RuntimeException ex) {
            failure.addSuppressed(ex);
        }

        try {
            shutdownKernel(kernelBootstrap);
        } catch (RuntimeException ex) {
            failure.addSuppressed(ex);
        }

        if (thread != null && thread.isAlive()) {
            thread.interrupt();
        }
    }

    private static void awaitStopSignal(CountDownLatch releaseSignal) {
        try {
            releaseSignal.await();
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        }
    }

    private static void releaseBootScope(CountDownLatch releaseSignal) {
        if (releaseSignal != null) {
            releaseSignal.countDown();
        }
    }

    private void awaitStopCompletion(Thread thread, boolean startupInProgress) {
        if (thread != null) {
            awaitShutdown(thread);
            return;
        }
        if (!startupInProgress || !properties.shutdown().graceful()) {
            return;
        }

        long timeoutMillis = shutdownTimeoutMillis();
        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        synchronized (lifecycleMonitor) {
            while (starting || bootThread.get() != null) {
                long remainingNanos = deadlineNanos - System.nanoTime();
                if (remainingNanos <= 0L) {
                    throw new IllegalStateException(
                            "Exeris runtime shutdown timed out after " + timeoutMillis
                                    + " ms waiting for startup cancellation"
                    );
                }
                try {
                    TimeUnit.NANOSECONDS.timedWait(lifecycleMonitor, remainingNanos);
                } catch (InterruptedException _) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private void awaitShutdown(Thread thread) {
        if (thread == null || thread == Thread.currentThread() || !properties.shutdown().graceful()) {
            return;
        }
        long timeoutMillis = shutdownTimeoutMillis();
        try {
            thread.join(timeoutMillis);
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            return;
        }
        if (thread.isAlive()) {
            thread.interrupt();
            throw new IllegalStateException(
                    "Exeris runtime shutdown timed out after " + timeoutMillis
                            + " ms waiting for boot thread '" + thread.getName() + "'"
            );
        }
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

    private IllegalStateException failedStart(KernelBootstrap kernelBootstrap, Exception cause) {
        this.running = false;
        this.bootstrap.set(null);

        try {
            shutdownKernel(kernelBootstrap);
        } catch (RuntimeException ex) {
            cause.addSuppressed(ex);
        }
        return new IllegalStateException("Exeris runtime startup failed", cause);
    }

    private static boolean invokeNoArg(Object target, String methodName) {
        try {
            target.getClass().getMethod(methodName).invoke(target);
            return true;
        } catch (NoSuchMethodException _) {
            return false;
        } catch (InvocationTargetException ex) {
            Throwable cause = ex.getCause();
            if (cause == null) {
                cause = ex;
            }
            throw new IllegalStateException(
                    "Failed invoking method '" + methodName + "' on " + target.getClass().getName(),
                    cause
            );
        } catch (ReflectiveOperationException | RuntimeException ex) {
            throw new IllegalStateException(
                    "Failed invoking method '" + methodName + "' on " + target.getClass().getName(),
                    ex
            );
        }
    }
}
