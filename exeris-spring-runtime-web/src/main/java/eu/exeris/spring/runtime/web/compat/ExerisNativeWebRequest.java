/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.web.compat;

import org.springframework.web.context.request.NativeWebRequest;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Compatibility-mode implementation of {@link NativeWebRequest} backed by
 * {@link ExerisMvcServerHttpRequest}.
 *
 * <p>No servlet types. Session semantics are unsupported and throw
 * {@link UnsupportedOperationException}. ThreadLocal binding is not performed here;
 * use {@link eu.exeris.spring.runtime.web.compat.context.ExerisThreadLocalBridge} for locale.
 */
public final class ExerisNativeWebRequest implements NativeWebRequest {

    private final ExerisMvcServerHttpRequest springRequest;
    private final Map<String, Object> attributes = new HashMap<>();

    public ExerisNativeWebRequest(ExerisMvcServerHttpRequest springRequest) {
        this.springRequest = springRequest;
    }

    // -------------------------------------------------------------------------
    // NativeWebRequest
    // -------------------------------------------------------------------------

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getNativeRequest(Class<T> requiredType) {
        return requiredType.isInstance(springRequest) ? (T) springRequest : null;
    }

    @Override
    public Object getNativeRequest() {
        return springRequest;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getNativeResponse(Class<T> requiredType) {
        return null;
    }

    @Override
    public Object getNativeResponse() {
        return null;
    }

    // -------------------------------------------------------------------------
    // WebRequest — headers
    // -------------------------------------------------------------------------

    @Override
    public String getHeader(String headerName) {
        return springRequest.getHeaders().getFirst(headerName);
    }

    @Override
    public String[] getHeaderValues(String headerName) {
        List<String> values = springRequest.getHeaders().get(headerName);
        return values != null ? values.toArray(new String[0]) : null;
    }

    @Override
    public Iterator<String> getHeaderNames() {
        return springRequest.getHeaders().keySet().iterator();
    }

    // -------------------------------------------------------------------------
    // WebRequest — parameters
    // -------------------------------------------------------------------------

    @Override
    public String getParameter(String paramName) {
        String rawQuery = springRequest.getURI().getRawQuery();
        Map<String, List<String>> params = parseQueryParams(rawQuery);
        List<String> values = params.get(paramName);
        return values != null && !values.isEmpty() ? values.get(0) : null;
    }

    @Override
    public String[] getParameterValues(String paramName) {
        String rawQuery = springRequest.getURI().getRawQuery();
        Map<String, List<String>> params = parseQueryParams(rawQuery);
        List<String> values = params.get(paramName);
        return values != null ? values.toArray(new String[0]) : null;
    }

    @Override
    public Map<String, String[]> getParameterMap() {
        String rawQuery = springRequest.getURI().getRawQuery();
        Map<String, List<String>> parsed = parseQueryParams(rawQuery);
        Map<String, String[]> result = new LinkedHashMap<>(parsed.size());
        for (Map.Entry<String, List<String>> entry : parsed.entrySet()) {
            result.put(entry.getKey(), entry.getValue().toArray(new String[0]));
        }
        return Collections.unmodifiableMap(result);
    }

    @Override
    public Iterator<String> getParameterNames() {
        return getParameterMap().keySet().iterator();
    }

    // -------------------------------------------------------------------------
    // WebRequest — attributes
    // -------------------------------------------------------------------------

    @Override
    public Object getAttribute(String name, int scope) {
        return attributes.get(name);
    }

    @Override
    public void setAttribute(String name, Object value, int scope) {
        attributes.put(name, value);
    }

    @Override
    public void removeAttribute(String name, int scope) {
        attributes.remove(name);
    }

    @Override
    public String[] getAttributeNames(int scope) {
        return attributes.keySet().toArray(new String[0]);
    }

    @Override
    public void registerDestructionCallback(String name, Runnable callback, int scope) {
        // no-op: Exeris compat mode does not support attribute lifecycle callbacks
    }

    @Override
    public Object resolveReference(String key) {
        return null;
    }

    // -------------------------------------------------------------------------
    // WebRequest — session (unsupported in Exeris compat mode)
    // -------------------------------------------------------------------------

    @Override
    public String getSessionId() {
        throw new UnsupportedOperationException("No session in Exeris compat mode");
    }

    @Override
    public Object getSessionMutex() {
        throw new UnsupportedOperationException("No session in Exeris compat mode");
    }

    // -------------------------------------------------------------------------
    // WebRequest — misc
    // -------------------------------------------------------------------------

    @Override
    public boolean checkNotModified(long lastModifiedTimestamp) {
        return false;
    }

    @Override
    public boolean checkNotModified(String etag) {
        return false;
    }

    @Override
    public boolean checkNotModified(String etag, long lastModifiedTimestamp) {
        return false;
    }

    @Override
    public String getContextPath() {
        return "";
    }

    @Override
    public String getRemoteUser() {
        return null;
    }

    @Override
    public Principal getUserPrincipal() {
        return null;
    }

    @Override
    public boolean isUserInRole(String role) {
        return false;
    }

    @Override
    public boolean isSecure() {
        return false;
    }

    @Override
    public Locale getLocale() {
        List<Locale> locales = springRequest.getHeaders().getAcceptLanguageAsLocales();
        return locales.isEmpty() ? Locale.getDefault() : locales.get(0);
    }

    @Override
    public String getDescription(boolean includeClientInfo) {
        return springRequest.getURI().toString();
    }

    // -------------------------------------------------------------------------
    // private helpers
    // -------------------------------------------------------------------------

    private static Map<String, List<String>> parseQueryParams(String rawQuery) {
        if (rawQuery == null || rawQuery.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (String pair : rawQuery.split("&")) {
            if (pair.isEmpty()) continue;
            int eq = pair.indexOf('=');
            String key;
            String value;
            if (eq < 0) {
                key = URLDecoder.decode(pair, StandardCharsets.UTF_8);
                value = "";
            } else {
                key = URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8);
                value = URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            }
            result.computeIfAbsent(key, k -> new ArrayList<>()).add(value);
        }
        return result;
    }
}
