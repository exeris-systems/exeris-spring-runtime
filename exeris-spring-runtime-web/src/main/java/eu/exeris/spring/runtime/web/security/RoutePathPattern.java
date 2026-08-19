/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.web.security;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A path pattern compiled once at startup and matched without allocating (ADR-063 obligation 2).
 *
 * <p>{@code HttpRoutePolicy.requirementFor} runs on the kernel's admission path for every request,
 * and ADR-061 requires it to be allocation-free. Splitting the incoming path, lower-casing it, or
 * taking a substring would all breach that, so matching walks the path by index and compares with
 * {@link String#regionMatches(int, String, int, int)}. Nothing here allocates after construction.
 *
 * <h2>Syntax</h2>
 *
 * <ul>
 *   <li>literal segments — {@code /api/orders}</li>
 *   <li>{@code *} — exactly one non-empty segment: {@code /api/&#42;/orders}</li>
 *   <li>{@code **} — the rest of the path, including nothing at all, and only as the last
 *       segment: {@code /api/&#42;&#42;} matches {@code /api}, {@code /api/x} and {@code /api/x/y}</li>
 * </ul>
 *
 * <p>Deliberately not Spring's {@code PathPattern}: its parser is a {@code spring-web} type whose
 * matching allocates, and the point of compiling here is to own that cost at startup.
 *
 * <h2>The query string is part of what the kernel hands us</h2>
 *
 * <p>{@code HttpRequest.path()} is the raw <em>request target</em>, so it carries {@code ?…} when
 * present — the same fact that made pure-mode route lookup miss on {@code /status?x=1} until it was
 * fixed. Here the consequence is worse than a 404: a rule written {@code /api/orders} that failed to
 * match {@code /api/orders?page=2} would hand the request to the unmatched answer instead. With a
 * fail-closed unmatched that refuses a legitimate request; with a permissive one it is an
 * authorization bypass reachable by appending a query string.
 *
 * <p>So matching is bounded at the first {@code ?} rather than run over the raw target — and it is
 * bounded by index, because taking the substring would allocate on exactly the path that must not.
 *
 * @since 0.8.0
 */
final class RoutePathPattern {

    /** Segment kinds, held out of band so matching never compares strings by identity. */
    private static final byte LITERAL = 0;
    private static final byte SINGLE = 1;
    private static final byte MULTI = 2;

    private static final String SINGLE_TOKEN = "*";
    private static final String MULTI_TOKEN = "**";

    private final String pattern;

    /**
     * Parallel arrays rather than a segment object per element, and a {@code byte} kind rather than a
     * sentinel string.
     *
     * <p>An earlier version stored the wildcards as interned string constants and compared them with
     * {@code ==} on the hot path. That was correct — and its correctness rested on an invariant nothing
     * enforced: that every wildcard entry is the exact constant instance. One future code path putting
     * an equal-but-distinct string into the array would have made a wildcard silently stop matching,
     * which for a rule that grants access is a lockout and for one that demands a scope is a hole. The
     * kind is now data, so there is no invariant left to break.
     */
    private final byte[] kinds;

    /** Literal text per segment; {@code null} where the kind is a wildcard. */
    private final String[] literals;

    private RoutePathPattern(String pattern, byte[] kinds, String[] literals) {
        this.pattern = pattern;
        this.kinds = kinds;
        this.literals = literals;
    }

    /**
     * Compiles a pattern.
     *
     * @param pattern must start with {@code /}; {@code **} may appear only as the last segment
     * @throws IllegalArgumentException if the pattern is malformed
     */
    static RoutePathPattern compile(String pattern) {
        Objects.requireNonNull(pattern, "pattern must not be null");
        if (pattern.isEmpty() || pattern.charAt(0) != '/') {
            throw new IllegalArgumentException(
                    "Route pattern must start with '/': " + pattern);
        }
        if (pattern.indexOf('?') >= 0) {
            // A pattern carrying a query string would never match, because matching is bounded at
            // the '?' of the incoming target. Failing at startup beats a rule that silently never
            // fires — which, for a rule that grants access, is a lockout, and for one that demands
            // a scope is a hole.
            throw new IllegalArgumentException(
                    "Route pattern must not contain a query string; rules match on path only: " + pattern);
        }

        if ("/".equals(pattern)) {
            // The root has no segments. Handled before the loop because the loop reads "/x" pairs and
            // would see a trailing empty segment here — which is the error a trailing slash raises.
            return new RoutePathPattern(pattern, new byte[0], new String[0]);
        }

        List<String> parsed = new ArrayList<>();
        int position = 0;
        while (position < pattern.length()) {
            // position is on a '/'
            int start = position + 1;
            int slash = pattern.indexOf('/', start);
            int end = slash < 0 ? pattern.length() : slash;
            String segment = pattern.substring(start, end);
            if (segment.isEmpty()) {
                throw new IllegalArgumentException(
                        "Route pattern must not contain an empty segment: " + pattern);
            }
            parsed.add(segment);
            position = end;
        }

        byte[] kinds = new byte[parsed.size()];
        String[] literals = new String[parsed.size()];
        for (int i = 0; i < parsed.size(); i++) {
            String segment = parsed.get(i);
            if (MULTI_TOKEN.equals(segment)) {
                if (i != parsed.size() - 1) {
                    throw new IllegalArgumentException(
                            "'**' is only allowed as the final segment of a route pattern: " + pattern);
                }
                kinds[i] = MULTI;
            } else if (SINGLE_TOKEN.equals(segment)) {
                kinds[i] = SINGLE;
            } else {
                kinds[i] = LITERAL;
                literals[i] = segment;
            }
        }
        return new RoutePathPattern(pattern, kinds, literals);
    }

    /**
     * Matches {@code target} against this pattern, ignoring any query string.
     *
     * <p>Allocation-free by construction: no split, no substring, no regex.
     *
     * @param target the raw request target as the kernel supplies it, query string and all
     * @return whether the path portion of {@code target} matches
     */
    boolean matches(String target) {
        if (target == null || target.isEmpty() || target.charAt(0) != '/') {
            return false;
        }
        int end = pathEnd(target);
        if (kinds.length == 0) {
            // The root pattern. The leading '/' is already verified, so the whole path must be it.
            return end == 1;
        }

        int position = 0;
        for (int i = 0; i < kinds.length; i++) {
            if (kinds[i] == MULTI) {
                return true;
            }
            if (position >= end || target.charAt(position) != '/') {
                return false;
            }
            int start = position + 1;
            int segmentEnd = nextSlash(target, start, end);
            if (kinds[i] == SINGLE) {
                if (segmentEnd == start) {
                    return false;
                }
            } else {
                String literal = literals[i];
                int length = segmentEnd - start;
                if (length != literal.length() || !target.regionMatches(start, literal, 0, length)) {
                    return false;
                }
            }
            position = segmentEnd;
        }
        return position == end;
    }

    /** Index of the first {@code ?}, or the length when there is none. No allocation. */
    private static int pathEnd(String target) {
        int query = target.indexOf('?');
        return query < 0 ? target.length() : query;
    }

    /** {@code String.indexOf} has no end bound, and adding one by substring would allocate. */
    private static int nextSlash(String target, int from, int end) {
        for (int i = from; i < end; i++) {
            if (target.charAt(i) == '/') {
                return i;
            }
        }
        return end;
    }

    /** The pattern as declared — used in startup diagnostics, never on the request path. */
    String pattern() {
        return pattern;
    }

    @Override
    public String toString() {
        return pattern;
    }
}
