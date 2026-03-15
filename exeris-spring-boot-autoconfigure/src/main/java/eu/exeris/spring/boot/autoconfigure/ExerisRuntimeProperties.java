/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.boot.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Exeris runtime integration properties.
 *
 * <p>Bound from the {@code exeris.runtime.*} namespace in the Spring {@code Environment}.
 * These properties are also the source of truth for the kernel {@code ConfigProvider}
 * bridge: {@link ExerisSpringConfigProvider} reads this record to construct the kernel
 * {@code KernelSettings}.
 *
 * <h2>Mode Semantics</h2>
 * <ul>
 *   <li>{@link Mode#PURE} — Exeris-native request path only. Handlers implement
 *       {@code ExerisRequestHandler}. No {@code @RestController} dispatch. Maximum
 *       performance headroom.</li>
 *   <li>{@link Mode#COMPATIBILITY} — Overlays a Spring MVC dispatch bridge on top of
 *       the Exeris ingress path. Opt-in. Documented heap-allocation overhead. Activated
 *       via {@code exeris.runtime.web.mode=compatibility}.</li>
 * </ul>
 *
 * @since 0.1.0
 */
@ConfigurationProperties(prefix = "exeris.runtime")
public record ExerisRuntimeProperties(

        boolean enabled,
        int port,
        WebProperties web,
        ShutdownProperties shutdown

) {

    public ExerisRuntimeProperties() {
        this(true, 8080, new WebProperties(), new ShutdownProperties());
    }

    public record WebProperties(Mode mode) {
        public WebProperties() {
            this(Mode.PURE);
        }

        public boolean isPure() {
            return mode == Mode.PURE;
        }

        public boolean isCompatibility() {
            return mode == Mode.COMPATIBILITY;
        }
    }

    public record ShutdownProperties(boolean graceful, int timeoutSeconds) {
        public ShutdownProperties() {
            this(true, 30);
        }
    }

    public enum Mode {
        /**
         * Exeris-native request path. No servlet API. No Spring MVC DispatcherServlet.
         * Handlers register via {@code @ExerisRoute}. Maximum performance baseline.
         */
        PURE,

        /**
         * Opt-in Spring MVC bridge. Activates {@code @RestController} / {@code @RequestMapping}
         * dispatch on top of the Exeris ingress path. Adds documented heap-allocation overhead.
         * Never activates as a default.
         */
        COMPATIBILITY
    }
}
