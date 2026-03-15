/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.boot.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Exeris runtime integration properties.
 *
 * <p>Bound from the {@code exeris.runtime.*} namespace in the Spring {@code Environment}.
 * This record defines autoconfiguration and lifecycle toggles consumed by the Spring
 * integration layer. The kernel {@code ConfigProvider} bridge is provided by
 * {@link ExerisSpringConfigProvider}, which reads directly from the Spring
 * {@code Environment} during bootstrap.
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
 * <h2>Property Binding</h2>
 * <p>This record has two constructors: the canonical constructor annotated with
 * {@code @ConstructorBinding} (used by Spring Boot's binder) and a convenience
 * no-arg constructor for direct instantiation outside Spring. The {@code @DefaultValue}
 * annotations declare the authoritative defaults for each component.
 *
 * @since 0.1.0
 */
@ConfigurationProperties(prefix = "exeris.runtime")
public record ExerisRuntimeProperties(

        @DefaultValue("true") boolean enabled,
        @DefaultValue("true") boolean autoStart,
        @DefaultValue("8080") int port,
        WebProperties web,
        ShutdownProperties shutdown

) {

    /**
     * Canonical constructor anchor for Spring Boot's {@code @ConfigurationProperties} binder.
     * Required because a convenience no-arg constructor is also present; without this
     * annotation Spring Boot would prefer the no-arg constructor and ignore property overrides.
     */
    @ConstructorBinding
    public ExerisRuntimeProperties {
            if (web == null) {
                web = new WebProperties();
            }
            if (shutdown == null) {
                shutdown = new ShutdownProperties();
            }
        }

    /**
     * Convenience constructor for direct instantiation outside a Spring context.
     * {@code @ConfigurationProperties} binding always uses the annotated canonical constructor.
     *
     * <p>{@code autoStart=false} keeps the lifecycle bean present in the context while
     * preventing automatic kernel bootstrap during {@code ApplicationContext.refresh()}.
     * Correct for tests and environments requiring manual lifecycle control.
     */
    public ExerisRuntimeProperties() {
        this(true, true, 8080, new WebProperties(), new ShutdownProperties());
    }

    public record WebProperties(@DefaultValue("pure") Mode mode) {

        @ConstructorBinding
        public WebProperties {
        }

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

    public record ShutdownProperties(
            @DefaultValue("true") boolean graceful,
            @DefaultValue("30") int timeoutSeconds
    ) {

        @ConstructorBinding
        public ShutdownProperties {
        }

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
