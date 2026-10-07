/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.web.compat;

import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;

import java.util.Optional;
import eu.exeris.spring.boot.autoconfigure.compat.CompatibilityMode;

/**
 * Chooses the JSON {@link HttpMessageConverter} for the compatibility dispatch path by which Jackson
 * is actually on the classpath.
 *
 * <p><b>JSON stack by line:</b></p>
 * <table>
 *   <caption>JSON stack by line</caption>
 *   <tr><th>Line</th><th>Jackson</th><th>Converter</th></tr>
 *   <tr><td>SB3</td><td>{@code com.fasterxml.jackson.core:jackson-databind} (2.x)</td>
 *       <td>{@code MappingJackson2HttpMessageConverter}</td></tr>
 *   <tr><td>SB4</td><td>{@code tools.jackson.core:jackson-databind} (3.x), plus
 *       {@code jackson-annotations} 2.x for annotation compatibility only</td>
 *       <td>{@code JacksonJsonHttpMessageConverter}</td></tr>
 * </table>
 *
 * <p><b>Mode:</b> Compatibility Mode only &mdash; the pure-mode path does not use Spring message converters.
 *
 * @implSpec {@code MappingJackson2HttpMessageConverter} still exists in Spring Framework 7 and compiles
 *     on both lines &mdash; what is missing under SB4 is the Jackson 2 <em>databind</em> it delegates to, so
 *     constructing it there fails at runtime with
 *     {@code NoClassDefFoundError: com/fasterxml/jackson/core/util/DefaultPrettyPrinter$Indenter} rather
 *     than at compile time.
 * @implNote {@code MappingJackson2HttpMessageConverter} is nameable at compile time on both lines and is
 *     therefore constructed directly. {@code JacksonJsonHttpMessageConverter} exists only in Spring
 *     Framework 7, so naming it would break the SB3 compile &mdash; it is constructed reflectively.
 *     Jackson 2 is evaluated first, then Jackson 3.
 * @since 0.7
 */
@CompatibilityMode
public final class ExerisCompatJsonConverterFactory {

    static final String JACKSON2_DATABIND = "com.fasterxml.jackson.databind.ObjectMapper";
    static final String JACKSON3_DATABIND = "tools.jackson.databind.ObjectMapper";
    static final String JACKSON3_CONVERTER =
            "org.springframework.http.converter.json.JacksonJsonHttpMessageConverter";

    private ExerisCompatJsonConverterFactory() {
    }

    /**
     * Returns {@code true} when a JSON converter can be built for this classpath.
     *
     * <p>Used as the bean condition so the compat bridge stands down cleanly on a classpath with no
     * Jackson at all, rather than failing context refresh over a converter nothing may need.
     *
     * @param classLoader loader to resolve against; never {@code null}
     * @return {@code true} if a supported Jackson databind is present, {@code false} otherwise
     */
    public static boolean isAvailable(ClassLoader classLoader) {
        return present(JACKSON2_DATABIND, classLoader) || present(JACKSON3_DATABIND, classLoader);
    }

    /**
     * Builds the converter for whichever Jackson is present.
     *
     * @param classLoader loader to resolve against; never {@code null}
     * @return the converter, or empty when no supported Jackson is on the classpath
     */
    public static Optional<HttpMessageConverter<?>> create(ClassLoader classLoader) {
        if (present(JACKSON2_DATABIND, classLoader)) {
            return Optional.of(new MappingJackson2HttpMessageConverter());
        }
        if (present(JACKSON3_DATABIND, classLoader)) {
            return jackson3Converter(classLoader);
        }
        return Optional.empty();
    }

    private static Optional<HttpMessageConverter<?>> jackson3Converter(ClassLoader classLoader) {
        try {
            Class<?> type = Class.forName(JACKSON3_CONVERTER, false, classLoader);
            Object converter = type.getDeclaredConstructor().newInstance();
            return Optional.of((HttpMessageConverter<?>) converter);
        } catch (ReflectiveOperationException | ClassCastException _) {
            // Jackson 3 is present but the Spring converter for it is not the shape expected — a
            // Spring Framework version that pairs neither known converter with this Jackson. Stand
            // down rather than guess; the bean condition has already passed, so the caller logs it.
            return Optional.empty();
        }
    }

    private static boolean present(String className, ClassLoader classLoader) {
        try {
            Class.forName(className, false, classLoader);
            return true;
        } catch (ClassNotFoundException _) {
            return false;
        }
    }
}
