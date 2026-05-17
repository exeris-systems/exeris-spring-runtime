/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.web.scope;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Immutable carrier for request-scoped state bound around an Exeris HTTP handler invocation.
 *
 * <p>The record is the payload of the {@code ScopedValue<RequestScope>} bound by
 * {@link ExerisRequestScope}. It is constructed once at request entry by a
 * {@link RequestScopeResolver} (application-supplied) and read by application code via the
 * typed accessor methods on {@link ExerisRequestScope}.
 *
 * <p>{@code attributes} is an unmodifiable {@link LinkedHashMap}-backed view. Mutation
 * after construction is not supported; create a new {@code RequestScope} via
 * {@link #with(String, Object)} for additional state.
 *
 * @since 0.6.0
 */
public record RequestScope(UUID tenantId, String correlationId, Map<String, Object> attributes) {

    public RequestScope {
        // tenantId and correlationId are nullable — the require* helpers on ExerisRequestScope
        // throw IllegalStateException when callers need them present.
        attributes = attributes == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
    }

    /**
     * Construct an empty {@code RequestScope} with no tenant, no correlation ID, and no
     * attributes. Useful when the scope is opt-in for {@code StructuredTaskScope} propagation
     * but no request-level identity has been resolved yet.
     */
    public static RequestScope empty() {
        return new RequestScope(null, null, Map.of());
    }

    /**
     * Return a new {@code RequestScope} with {@code key} → {@code value} added (or replaced).
     * Original instance unchanged.
     */
    public RequestScope with(String key, Object value) {
        Objects.requireNonNull(key, "key");
        var next = new LinkedHashMap<>(attributes);
        next.put(key, value);
        return new RequestScope(tenantId, correlationId, next);
    }

    /**
     * Return the typed attribute value for {@code key}, or {@link Optional#empty()} if absent
     * or if the value cannot be cast to {@code type}.
     */
    public <T> Optional<T> attribute(String key, Class<T> type) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(type, "type");
        Object value = attributes.get(key);
        if (value == null || !type.isInstance(value)) {
            return Optional.empty();
        }
        return Optional.of(type.cast(value));
    }
}
