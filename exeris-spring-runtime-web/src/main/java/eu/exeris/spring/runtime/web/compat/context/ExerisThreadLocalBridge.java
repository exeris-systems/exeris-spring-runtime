/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.web.compat.context;

import eu.exeris.spring.boot.autoconfigure.compat.CompatibilityMode;

import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.i18n.SimpleLocaleContext;
import org.springframework.http.server.ServerHttpRequest;

import java.util.List;
import java.util.Locale;

/**
 * Compatibility-scoped bridge that binds per-request locale to Spring's
 * {@link LocaleContextHolder}. Must be used in a virtual-thread and always
 * cleared in a {@code finally} block. Isolated in {@code *.compat.context.*}
 * per the ThreadLocal Rule.
 */
@CompatibilityMode
public final class ExerisThreadLocalBridge {

    /**
     * Creates a stateless bridge; every call acts on Spring's {@link LocaleContextHolder} for the
     * calling thread.
     */
    public ExerisThreadLocalBridge() {
    }

    /**
     * Binds the request's preferred locale to the calling thread's {@link LocaleContextHolder}.
     *
     * <p>The first {@code Accept-Language} locale is used, or {@link Locale#getDefault()} when the header is
     * absent. Every call must be paired with {@link #clear()} in a {@code finally} block.
     *
     * @param request the request whose {@code Accept-Language} header selects the locale
     */
    public void bind(ServerHttpRequest request) {
        List<Locale> locales = request.getHeaders().getAcceptLanguageAsLocales();
        Locale locale = locales.isEmpty() ? Locale.getDefault() : locales.get(0);
        LocaleContextHolder.setLocaleContext(new SimpleLocaleContext(locale), false);
    }

    /**
     * Removes the locale context from the calling thread, undoing {@link #bind(ServerHttpRequest)}.
     */
    public void clear() {
        LocaleContextHolder.resetLocaleContext();
    }
}
