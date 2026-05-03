/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.web.compat.context;

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
public final class ExerisThreadLocalBridge {

    public void bind(ServerHttpRequest request) {
        List<Locale> locales = request.getHeaders().getAcceptLanguageAsLocales();
        Locale locale = locales.isEmpty() ? Locale.getDefault() : locales.get(0);
        LocaleContextHolder.setLocaleContext(new SimpleLocaleContext(locale), false);
    }

    public void clear() {
        LocaleContextHolder.resetLocaleContext();
    }
}
