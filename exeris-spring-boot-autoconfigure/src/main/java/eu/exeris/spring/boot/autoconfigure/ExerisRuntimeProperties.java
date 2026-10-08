/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.boot.autoconfigure;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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
 * <h2>Subsystem Selection</h2>
 * <p>{@link #subsystems()} maps to the kernel's
 * {@code BootstrapSelector} SPI. When empty (the default), the kernel boots its full
 * subsystem set ({@code BootstrapSelector.all()}); when non-empty, only the named
 * subsystems are started, via {@code BootstrapSelector.forNames(...)}. Typical names
 * exposed by the community kernel include {@code memory}, {@code crypto},
 * {@code persistence}, {@code events}, {@code graph}, {@code transport}, {@code http},
 * and {@code flow}. The Spring layer passes values through verbatim; the kernel
 * fails-fast on unknown names. Names are trimmed and blanks are dropped at
 * binding time; ordering is preserved for predictable startup logging. Example:
 * {@snippet lang="properties" :
 *   # Headless batch worker
 *   exeris.runtime.subsystems[0]=memory
 *   exeris.runtime.subsystems[1]=crypto
 *   exeris.runtime.subsystems[2]=persistence
 *   exeris.runtime.subsystems[3]=events
 *   exeris.runtime.subsystems[4]=flow
 * }
 *
 * @param enabled master switch for Exeris runtime integration
 * @param autoStart whether the runtime boots automatically during context refresh
 * @param web web-specific configuration properties
 * @param lifecycle lifecycle-specific configuration properties
 * @param shutdown shutdown-specific configuration properties
 * @param subsystems explicit list of kernel subsystems to boot, or empty for defaults
 * @since 0.1
 */
@ConfigurationProperties(prefix = "exeris.runtime")
public record ExerisRuntimeProperties(

        @DefaultValue("true") boolean enabled,
        @DefaultValue("true") boolean autoStart,
        WebProperties web,
        LifecycleProperties lifecycle,
        ShutdownProperties shutdown,
        List<String> subsystems

) {

    /**
     * Canonical constructor anchor for Spring Boot's {@code @ConfigurationProperties} binder.
     * Required because a convenience no-arg constructor is also present; without this
     * annotation Spring Boot would prefer the no-arg constructor and ignore property overrides.
     *
     * <p>{@code subsystems} is normalised to an immutable list of trimmed, non-blank
     * names — preserving caller ordering for predictable logging. An empty list
     * (default) selects the kernel's default subsystem set; a non-empty list selects
     * exactly the named subsystems via {@code BootstrapSelector.forNames(...)}.
     *
     * @param enabled master switch for Exeris runtime integration
     * @param autoStart whether the runtime boots automatically during context refresh
     * @param web web-specific configuration properties
     * @param lifecycle lifecycle-specific configuration properties
     * @param shutdown shutdown-specific configuration properties
     * @param subsystems explicit list of kernel subsystems to boot, or empty for defaults
     */
    @ConstructorBinding
    public ExerisRuntimeProperties {
            if (web == null) {
                web = new WebProperties();
            }
            if (lifecycle == null) {
                lifecycle = new LifecycleProperties();
            }
            if (shutdown == null) {
                shutdown = new ShutdownProperties();
            }
            subsystems = normaliseSubsystems(subsystems);
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
        this(true, false, new WebProperties(), new LifecycleProperties(), new ShutdownProperties(),
                Collections.emptyList());
    }

    /**
     * Backward-compatible positional constructor without the {@code subsystems} component.
     *
     * <p>Defaults to an empty subsystem list, which selects the kernel's full subsystem set.
     *
     * @param enabled master switch for Exeris runtime integration
     * @param autoStart whether the runtime boots automatically during context refresh
     * @param web web-specific configuration properties
     * @param lifecycle lifecycle-specific configuration properties
     * @param shutdown shutdown-specific configuration properties
     */
    public ExerisRuntimeProperties(boolean enabled,
                                    boolean autoStart,
                                    WebProperties web,
                                    LifecycleProperties lifecycle,
                                    ShutdownProperties shutdown) {
        this(enabled, autoStart, web, lifecycle, shutdown, Collections.emptyList());
    }

    private static List<String> normaliseSubsystems(List<String> input) {
        if (input == null || input.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> normalised = new ArrayList<>(input.size());
        for (String name : input) {
            if (name == null) {
                continue;
            }
            String trimmed = name.trim();
            if (!trimmed.isEmpty()) {
                normalised.add(trimmed);
            }
        }
        return Collections.unmodifiableList(normalised);
    }

    /**
     * Web configuration properties.
     *
     * @param mode web integration mode
     */
    public record WebProperties(@DefaultValue("pure") Mode mode) {

        /**
         * Creates web properties with the given mode.
         *
         * @param mode web integration mode
         */
        @ConstructorBinding
        public WebProperties {
        }

        /**
         * Creates web properties with default pure mode.
         */
        public WebProperties() {
            this(Mode.PURE);
        }

        /**
         * Checks whether pure mode is active.
         *
         * @return {@code true} if pure mode is configured
         */
        public boolean isPure() {
            return mode == Mode.PURE;
        }

        /**
         * Checks whether compatibility mode is active.
         *
         * @return {@code true} if compatibility mode is configured
         */
        public boolean isCompatibility() {
            return mode == Mode.COMPATIBILITY;
        }
    }

    /**
     * Lifecycle timeout configuration properties.
     *
     * @param startupTimeoutSeconds timeout in seconds for kernel startup
     */
    public record LifecycleProperties(@DefaultValue("30") int startupTimeoutSeconds) {

        /**
         * Creates lifecycle properties with the given timeout.
         *
         * @param startupTimeoutSeconds timeout in seconds for kernel startup
         */
        @ConstructorBinding
        public LifecycleProperties {
        }

        /**
         * Creates lifecycle properties with default timeout.
         */
        public LifecycleProperties() {
            this(30);
        }
    }

    /**
     * Shutdown configuration properties.
     *
     * @param graceful whether to perform graceful shutdown
     * @param timeoutSeconds timeout in seconds for graceful shutdown
     */
    public record ShutdownProperties(
            @DefaultValue("true") boolean graceful,
            @DefaultValue("30") int timeoutSeconds
    ) {

        /**
         * Creates shutdown properties with the given configuration.
         *
         * @param graceful whether to perform graceful shutdown
         * @param timeoutSeconds timeout in seconds for graceful shutdown
         */
        @ConstructorBinding
        public ShutdownProperties {
        }

        /**
         * Creates shutdown properties with default configuration.
         */
        public ShutdownProperties() {
            this(true, 30);
        }
    }

    /**
     * Web execution mode for the Exeris Spring integration.
     */
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
