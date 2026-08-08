/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.web.compat;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import eu.exeris.kernel.spi.http.HttpHeader;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpRequest;
import eu.exeris.kernel.spi.http.HttpVersion;
import eu.exeris.spring.runtime.web.ExerisServerRequest;

/**
 * Header accessors on the compat {@code NativeWebRequest}.
 *
 * <p>These read through {@code HttpHeaders.forEach} rather than {@code get} / {@code keySet} so that
 * one compiled jar behaves identically on both Spring Boot matrix lines — see
 * {@code docs/architecture/spring-boot-4-matrix.md} §"Binary neutrality". The binary property is
 * enforced by {@code .github/scripts/spring-binary-neutrality.sh}; what is asserted here is that the
 * neutral form kept the behaviour Spring's argument resolvers depend on.
 */
class ExerisNativeWebRequestTest {

    @Test
    void headerValues_returnsEveryValueOfAMultiValuedHeader() {
        ExerisNativeWebRequest request = newRequest(List.of(
                new HttpHeader("X-Trace", "a"),
                new HttpHeader("X-Trace", "b")));

        assertThat(request.getHeaderValues("X-Trace")).containsExactly("a", "b");
    }

    @Test
    void headerValues_isCaseInsensitive() {
        ExerisNativeWebRequest request = newRequest(List.of(new HttpHeader("Content-Type", "application/json")));

        assertThat(request.getHeaderValues("content-type")).containsExactly("application/json");
        assertThat(request.getHeaderValues("CONTENT-TYPE")).containsExactly("application/json");
    }

    /**
     * Null rather than an empty array: {@code WebRequest} callers such as Spring's
     * {@code RequestHeaderMethodArgumentResolver} read null as "header missing" and turn a required
     * missing header into a 400. An empty array would present as "header present, no values".
     */
    @Test
    void headerValues_absentHeaderIsNullNotEmpty() {
        ExerisNativeWebRequest request = newRequest(List.of(new HttpHeader("X-Present", "1")));

        assertThat(request.getHeaderValues("X-Absent")).isNull();
        assertThat(request.getHeaderValues("X-Present")).isNotNull();
    }

    @Test
    void headerNames_listsEachHeaderOnce() {
        ExerisNativeWebRequest request = newRequest(List.of(
                new HttpHeader("X-Trace", "a"),
                new HttpHeader("X-Trace", "b"),
                new HttpHeader(HttpHeaders.ACCEPT, "application/json")));

        List<String> names = new ArrayList<>();
        Iterator<String> it = request.getHeaderNames();
        while (it.hasNext()) {
            names.add(it.next());
        }

        assertThat(names).containsExactlyInAnyOrder("X-Trace", HttpHeaders.ACCEPT);
    }

    @Test
    void header_returnsFirstValue() {
        ExerisNativeWebRequest request = newRequest(List.of(
                new HttpHeader("X-Trace", "a"),
                new HttpHeader("X-Trace", "b")));

        assertThat(request.getHeader("X-Trace")).isEqualTo("a");
        assertThat(request.getHeader("X-Absent")).isNull();
    }

    private static ExerisNativeWebRequest newRequest(List<HttpHeader> headers) {
        HttpRequest kernelRequest = HttpRequest.noBody(
                HttpMethod.GET, "/compat/headers", HttpVersion.HTTP_1_1, headers);
        return new ExerisNativeWebRequest(
                new ExerisMvcServerHttpRequest(ExerisServerRequest.wrap(kernelRequest)));
    }
}
