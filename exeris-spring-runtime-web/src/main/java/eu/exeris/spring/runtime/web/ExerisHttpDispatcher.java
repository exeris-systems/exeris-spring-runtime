/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.web;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.exceptions.http.HttpException;
import eu.exeris.kernel.spi.http.HttpExchange;
import eu.exeris.kernel.spi.http.HttpHandler;
import eu.exeris.kernel.spi.http.HttpStatus;
import eu.exeris.kernel.spi.http.HttpVersion;
import eu.exeris.kernel.spi.telemetry.TelemetrySink;

/**
 * The primary bridge between the Exeris HTTP runtime and Spring application handlers.
 *
 * <p>This class implements the kernel {@link HttpHandler} SPI and is registered with
 * the kernel's {@code HttpServerEngine}. On each request, it:
 * <ol>
 *   <li>Wraps the kernel {@code HttpRequest} in an {@link ExerisServerRequest} (no copy).</li>
 *   <li>Resolves the matching Spring-managed handler bean via {@link ExerisRouteRegistry}.</li>
 *   <li>Invokes the handler.</li>
 *   <li>Converts the {@link ExerisServerResponse} to a kernel {@code HttpResponse}.</li>
 *   <li>Calls {@code exchange.respond(response)} — transferring body buffer ownership to engine.</li>
 * </ol>
 *
 * <h2>Threading Model</h2>
 * <p>Invocations run one-per-virtual-thread. No {@code synchronized} blocks, no
 * {@code ThreadLocal}, no carrier thread pinning.
 *
 * <h2>No DispatcherServlet</h2>
 * <p>This class does NOT delegate to Spring's {@code DispatcherServlet}. It uses
 * {@link ExerisRouteRegistry} for routing. This is Pure Mode. Compatibility mode
 * ({@code @RestController} / {@code @RequestMapping} dispatch) is implemented in
 * {@code eu.exeris.spring.runtime.web.compat.ExerisCompatDispatcher}.
 *
 * <h2>Telemetry Fallback</h2>
 * <p>In production, {@code KernelProviders.TELEMETRY_SINKS} is always bound by the kernel
 * bootstrap scope and inherited by every handler virtual thread. In non-kernel-scope contexts
 * (testkit, unit tests) where the acceptor platform thread does not propagate the bootstrap
 * ScopedValue, this dispatcher may receive a pre-built {@code fallbackSinks} list at construction
 * time. When {@code TELEMETRY_SINKS} is not already bound and {@code fallbackSinks} is non-empty,
 * the dispatcher binds {@code fallbackSinks} for the duration of the handler call via
 * {@link ScopedValue#where(ScopedValue, Object) ScopedValue.where(...).run(...)}. This restores
 * the expected telemetry availability for test and compatibility paths without touching the
 * production hot path.
 *
 * @since 0.1.0
 */
public final class ExerisHttpDispatcher implements HttpHandler {

    private static final System.Logger LOGGER = System.getLogger(ExerisHttpDispatcher.class.getName());
    private static final AtomicBoolean FALLBACK_WARNING_LOGGED = new AtomicBoolean(false);

    private final ExerisRouteRegistry routeRegistry;
    private final ExerisErrorMapper errorMapper;
    private final Supplier<List<TelemetrySink>> fallbackSinksSupplier;

    public ExerisHttpDispatcher(ExerisRouteRegistry routeRegistry,
                                 ExerisErrorMapper errorMapper) {
        this(routeRegistry, errorMapper, (Supplier<List<TelemetrySink>>) null);
    }

    public ExerisHttpDispatcher(ExerisRouteRegistry routeRegistry,
                                 ExerisErrorMapper errorMapper,
                                 List<TelemetrySink> fallbackSinks) {
        this(routeRegistry, errorMapper, () -> fallbackSinks);
    }

    public ExerisHttpDispatcher(ExerisRouteRegistry routeRegistry,
                                 ExerisErrorMapper errorMapper,
                                 Supplier<List<TelemetrySink>> fallbackSinksSupplier) {
        this.routeRegistry = Objects.requireNonNull(routeRegistry, "routeRegistry must not be null");
        this.errorMapper = Objects.requireNonNull(errorMapper, "errorMapper must not be null");
        this.fallbackSinksSupplier = memoizeFallbackSinks(fallbackSinksSupplier);
    }

    @Override
    public void handle(HttpExchange exchange) throws HttpException {
        Objects.requireNonNull(exchange, "exchange must not be null");

        if (KernelProviders.TELEMETRY_SINKS.isBound()) {
            dispatch(exchange);
            return;
        }

        List<TelemetrySink> fallbackSinks = resolveFallbackSinks();
        if (fallbackSinks.isEmpty()) {
            dispatch(exchange);
            return;
        }

        ScopedValue.where(KernelProviders.TELEMETRY_SINKS, fallbackSinks)
                .run(() -> dispatch(exchange));
    }

    private List<TelemetrySink> resolveFallbackSinks() {
        return fallbackSinksSupplier.get();
    }

    private void dispatch(HttpExchange exchange) {
        var kernelRequest = exchange.request();
        var request = new ExerisServerRequest(kernelRequest);
        HttpVersion version = kernelRequest.version();
        try {
            var handler = routeRegistry.resolve(request.method(), request.path());
            if (handler == null) {
                exchange.respond(errorMapper.mapStatus(HttpStatus.NOT_FOUND, version));
                return;
            }
            var response = handler.handle(request);
            exchange.respond(response.toKernelResponse(version));
        } catch (HttpException ex) {
            exchange.respond(errorMapper.map(ex, version));
        } catch (Exception ex) {
            exchange.respond(errorMapper.mapUnhandled(ex, version));
        }
    }

    private static Supplier<List<TelemetrySink>> memoizeFallbackSinks(Supplier<List<TelemetrySink>> source) {
        return new MemoizedTelemetrySinkSupplier(source);
    }

    private static final class MemoizedTelemetrySinkSupplier implements Supplier<List<TelemetrySink>> {

        private final Supplier<List<TelemetrySink>> source;
        private final Object lock = new Object();
        private final AtomicReference<List<TelemetrySink>> cached = new AtomicReference<>();

        private MemoizedTelemetrySinkSupplier(Supplier<List<TelemetrySink>> source) {
            this.source = source;
        }

        @Override
        public List<TelemetrySink> get() {
            List<TelemetrySink> resolved = cached.get();
            if (resolved != null) {
                return resolved;
            }

            synchronized (lock) {
                resolved = cached.get();
                if (resolved == null) {
                    resolved = resolveSafely(source);
                    cached.set(resolved);
                }
                return resolved;
            }
        }

        private static List<TelemetrySink> resolveSafely(Supplier<List<TelemetrySink>> source) {
            try {
                return sanitizeFallbackSinks(source == null ? null : source.get());
            } catch (RuntimeException ex) {
                if (FALLBACK_WARNING_LOGGED.compareAndSet(false, true)) {
                    LOGGER.log(System.Logger.Level.WARNING,
                            "ExerisHttpDispatcher telemetry fallback sink resolution failed; continuing without "
                                    + "fallback telemetry sinks. This should only occur in tests, compatibility "
                                    + "tooling, or when telemetry bootstrap is missing.",
                            ex);
                }
                return List.of();
            }
        }

        private static List<TelemetrySink> sanitizeFallbackSinks(List<TelemetrySink> sinks) {
            if (sinks == null || sinks.isEmpty()) {
                return List.of();
            }
            List<TelemetrySink> sanitized = sinks.stream()
                    .filter(Objects::nonNull)
                    .toList();
            return sanitized.isEmpty() ? List.of() : List.copyOf(sanitized);
        }
    }
}
