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
 * <pre>{@code
 *   # Headless batch worker
 *   exeris.runtime.subsystems[0]=memory
 *   exeris.runtime.subsystems[1]=crypto
 *   exeris.runtime.subsystems[2]=persistence
 *   exeris.runtime.subsystems[3]=events
 *   exeris.runtime.subsystems[4]=flow
 * }</pre>
 *
 * @since 0.1.0
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
     * Backward-compatible positional constructor without the {@code subsystems} component
     * — defaults to an empty subsystem list, which selects the kernel's full subsystem set.
     * Kept to avoid churning test code in other modules that construct properties directly.
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

    public record LifecycleProperties(@DefaultValue("30") int startupTimeoutSeconds) {

        @ConstructorBinding
        public LifecycleProperties {
        }

        public LifecycleProperties() {
            this(30);
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
