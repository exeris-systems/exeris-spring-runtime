/*
 * Copyright (C) 2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
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
 * @param tenantId the tenant the request belongs to; {@code null} when none has been resolved
 * @param correlationId the request correlation identifier; {@code null} when none has been resolved
 * @param attributes additional request-scoped values; {@code null} is treated as empty, otherwise an
 *        unmodifiable insertion-ordered copy is kept
 * @since 0.6
 */
public record RequestScope(UUID tenantId, String correlationId, Map<String, Object> attributes) {

    /**
     * Normalizes {@code attributes} to an unmodifiable insertion-ordered copy, or the empty map when it
     * is {@code null}. {@code tenantId} and {@code correlationId} are kept as given, {@code null} included.
     */
    public RequestScope {
        // tenantId and correlationId are nullable — the require* helpers on ExerisRequestScope
        // throw IllegalStateException when callers need them present.
        attributes = attributes == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
    }

    /**
     * Construct an empty {@code RequestScope} with no tenant, no correlation ID, and no
     * attributes. Useful when the scope is bound but no request-level identity has been resolved
     * yet.
     *
     * @return a scope whose tenant and correlation ID are {@code null} and whose attributes are empty
     */
    public static RequestScope empty() {
        return new RequestScope(null, null, Map.of());
    }

    /**
     * Return a new {@code RequestScope} with {@code key} → {@code value} added (or replaced).
     * Original instance unchanged.
     *
     * @param key the attribute name; must not be {@code null}
     * @param value the attribute value; {@code null} is stored as given
     * @return a new scope with the same tenant and correlation ID and the updated attributes
     * @throws NullPointerException if {@code key} is {@code null}
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
     *
     * @param <T> the expected attribute type
     * @param key the attribute name; must not be {@code null}
     * @param type the expected type of the value; must not be {@code null}
     * @return the value as {@code type}, or empty when the key is absent, its value is {@code null},
     *         or the value is not an instance of {@code type}
     * @throws NullPointerException if {@code key} or {@code type} is {@code null}
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
