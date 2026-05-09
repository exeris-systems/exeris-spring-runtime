/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.actuator.compat;

import eu.exeris.spring.boot.autoconfigure.ExerisRuntimeProperties;
import eu.exeris.spring.runtime.actuator.ExerisRuntimeHealthIndicator;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.info.Info;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Compatibility-mode HTTP diagnostics endpoint for Exeris-hosted applications.
 *
 * <p>This controller exists only to make operational visibility endpoints reachable when
 * Exeris owns ingress and Spring runs with {@code web-application-type=none}. It does not
 * create or assume servlet/webmvc runtime ownership.
 *
 * <p>For container compatibility, health is exposed on both {@code /actuator/health}
 * and the safe alias {@code /health}. Runtime info remains under {@code /actuator/info}.
 */
@RestController
public final class ExerisCompatibilityActuatorController {

    private final ExerisRuntimeHealthIndicator healthIndicator;
    private final ObjectProvider<ExerisRuntimeProperties> runtimeProperties;
    private final ObjectProvider<InfoContributor> infoContributors;

    public ExerisCompatibilityActuatorController(
            ExerisRuntimeHealthIndicator healthIndicator,
            ObjectProvider<ExerisRuntimeProperties> runtimeProperties,
            ObjectProvider<InfoContributor> infoContributors) {
        this.healthIndicator = Objects.requireNonNull(healthIndicator, "healthIndicator must not be null");
        this.runtimeProperties = Objects.requireNonNull(runtimeProperties, "runtimeProperties must not be null");
        this.infoContributors = Objects.requireNonNull(infoContributors, "infoContributors must not be null");
    }

    @GetMapping({"/actuator/health", "/health"})
    public Map<String, Object> health() {
        Health health = healthIndicator.health();

        Map<String, Object> component = new LinkedHashMap<>();
        component.put("status", health.getStatus().getCode());
        if (!health.getDetails().isEmpty()) {
            component.put("details", health.getDetails());
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", health.getStatus().getCode());
        body.put("components", Map.of("exerisRuntime", component));
        return body;
    }

    @GetMapping("/actuator/info")
    public Map<String, Object> info() {
        Info.Builder builder = new Info.Builder();
        infoContributors.orderedStream().forEach(contributor -> contributor.contribute(builder));

        Map<String, Object> body = new LinkedHashMap<>(builder.build().getDetails());
        body.putIfAbsent("runtime", "exeris");
        body.putIfAbsent("mode", modeName());
        return body;
    }

    private String modeName() {
        ExerisRuntimeProperties properties = runtimeProperties.getIfAvailable();
        if (properties == null || properties.web() == null || properties.web().mode() == null) {
            return "compatibility";
        }
        return properties.web().mode().name().toLowerCase(Locale.ROOT);
    }
}
