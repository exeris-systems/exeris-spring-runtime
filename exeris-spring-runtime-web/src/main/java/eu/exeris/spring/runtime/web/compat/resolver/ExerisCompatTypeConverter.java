/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.web.compat.resolver;

import java.util.Collection;
import java.util.Map;

import org.springframework.core.convert.ConversionException;
import org.springframework.core.convert.ConversionService;

/**
 * Compatibility-mode bridge between raw HTTP scalar values (query/path/header strings)
 * and Spring handler argument types, backed by a {@link ConversionService}.
 *
 * <p>The conversion service is the single seam that brings parity with Spring MVC's
 * binder behaviour — enum coercion, user-registered {@code Converter<String, T>} beans,
 * and {@code @DateTimeFormat}-style formatters all flow through it.
 *
 * <p>Multi-value types ({@link Collection}, {@link Map}, arrays) are deliberately
 * rejected here: the compat resolvers read at most one raw value per parameter, and
 * {@code ConversionService.canConvert(String.class, List.class)} reports {@code true}
 * (single-element split) — accepting that would silently change semantics versus
 * Spring MVC's multi-value handling. Callers needing multi-value support must add
 * a dedicated resolver.
 */
final class ExerisCompatTypeConverter {

    private final ConversionService conversionService;

    ExerisCompatTypeConverter(ConversionService conversionService) {
        this.conversionService = conversionService;
    }

    boolean isSupportedTargetType(Class<?> type) {
        if (Void.TYPE.equals(type) || Void.class.equals(type)) {
            return false;
        }
        if (type.isArray()) {
            return false;
        }
        if (Collection.class.isAssignableFrom(type) || Map.class.isAssignableFrom(type)) {
            return false;
        }
        if (String.class.equals(type)) {
            return true;
        }
        return conversionService.canConvert(String.class, type);
    }

    Object convert(String raw, Class<?> type) {
        if (String.class.equals(type)) {
            return raw;
        }
        try {
            Object result = conversionService.convert(raw, type);
            if (result == null && type.isPrimitive()) {
                throw new IllegalArgumentException(
                        "Cannot convert request value to primitive " + type.getSimpleName());
            }
            return result;
        } catch (ConversionException ex) {
            // Deliberately drop the cause: ConversionFailedException.getMessage() embeds
            // the raw user-controlled value (CWE-117 / CWE-532). Exposing it through the
            // cause chain would let the value flow into logs verbatim once the surrounding
            // error mapper logs the IllegalArgumentException. Keep the failing target type
            // and the original exception class name for diagnostics; the value itself stays
            // out of the message.
            throw new IllegalArgumentException(
                    "Cannot convert request value to " + type.getSimpleName()
                            + " (cause: " + ex.getClass().getSimpleName() + ")");
        }
    }
}
