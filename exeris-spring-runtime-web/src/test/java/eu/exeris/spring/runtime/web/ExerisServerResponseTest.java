/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.web;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import eu.exeris.kernel.spi.http.HttpHeader;
import eu.exeris.kernel.spi.http.HttpResponse;
import eu.exeris.kernel.spi.http.HttpStatus;
import eu.exeris.kernel.spi.http.HttpVersion;
import eu.exeris.kernel.spi.memory.LoanedBuffer;

class ExerisServerResponseTest {

    @Test
    void toKernelResponse_whenMemoryAllocatorUnbound_usesFallbackBufferAndManagedHeaders() {
        byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);

        ExerisServerResponse response = ExerisServerResponse.status(HttpStatus.CREATED)
                .contentType(MediaType.APPLICATION_JSON)
                .withHeaders(Arrays.asList(
                        null,
                        new HttpHeader("content-type", "text/plain"),
                        new HttpHeader("Content-Length", "999"),
                        new HttpHeader("X-Compat", "yes")))
                .body(body);

        HttpResponse kernelResponse = response.toKernelResponse(anyHttpVersion());

        assertThat(kernelResponse.status().code()).isEqualTo(201);
        assertThat(headerValues(kernelResponse, "Content-Type")).containsExactly(MediaType.APPLICATION_JSON_VALUE);
        assertThat(headerValues(kernelResponse, "Content-Length")).containsExactly(Integer.toString(body.length));
        assertThat(headerValues(kernelResponse, "X-Compat")).containsExactly("yes");
        assertThat(readBytes(kernelResponse.body())).containsExactly(body);
    }

    @Test
    void builderNormalizesNullContentTypeAndByteBody() {
        ExerisServerResponse response = ExerisServerResponse.status(HttpStatus.OK)
                .contentType((MediaType) null)
                .body((byte[]) null);

        HttpResponse kernelResponse = response.toKernelResponse(anyHttpVersion());

        assertThat(response.contentType()).isEqualTo(MediaType.TEXT_PLAIN_VALUE);
        assertThat(response.body()).isEmpty();
        assertThat(headerValues(kernelResponse, "Content-Type")).containsExactly(MediaType.TEXT_PLAIN_VALUE);
        assertThat(headerValues(kernelResponse, "Content-Length")).containsExactly("0");
    }

    @Test
    void heapFallbackBuffer_setSizeTracksLogicalLengthSeparatelyFromCapacity() {
        HttpResponse kernelResponse = ExerisServerResponse.ok().body("abcdef").toKernelResponse(anyHttpVersion());
        LoanedBuffer body = kernelResponse.body();

        body.setSize(3);

        assertThat(body.size()).isEqualTo(3);
        assertThat(body.capacity()).isEqualTo(6);
        assertThat(new String(readBytes(body.slice(0, 3)), StandardCharsets.UTF_8)).isEqualTo("abc");
        assertThatThrownBy(() -> body.slice(0, 4)).isInstanceOf(IndexOutOfBoundsException.class);
    }

    @Test
    void heapFallbackBuffer_sliceAndPeekHonorOffsetsAndBounds() {
        HttpResponse kernelResponse = ExerisServerResponse.ok().body("abcdef").toKernelResponse(anyHttpVersion());
        LoanedBuffer body = kernelResponse.body();

        assertThat(new String(readBytes(body.slice(2, 3)), StandardCharsets.UTF_8)).isEqualTo("cde");
        assertThat(new String(readBytes(body.peek(1, 4)), StandardCharsets.UTF_8)).isEqualTo("bcde");
        assertThatThrownBy(() -> body.slice(-1, 1)).isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> body.slice(0, 7)).isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> body.peek(Long.MAX_VALUE, 1)).isInstanceOf(IndexOutOfBoundsException.class);
    }

    @Test
    void wrapAndConstructor_fastFailOnNullRequest() {
        assertThatThrownBy(() -> ExerisServerRequest.wrap(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ExerisServerRequest(null))
                .isInstanceOf(NullPointerException.class);
    }

    private static List<String> headerValues(HttpResponse response, String name) {
        return response.headers().stream()
                .filter(header -> name.equalsIgnoreCase(header.name()))
                .map(HttpHeader::value)
                .toList();
    }

    private static byte[] readBytes(LoanedBuffer buffer) {
        return buffer.segment().toArray(java.lang.foreign.ValueLayout.JAVA_BYTE);
    }

    private static HttpVersion anyHttpVersion() {
        Object[] enumConstants = HttpVersion.class.getEnumConstants();
        if (enumConstants != null && enumConstants.length > 0) {
            return (HttpVersion) enumConstants[0];
        }

        for (var field : HttpVersion.class.getDeclaredFields()) {
            if (field.getType() == HttpVersion.class && java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                try {
                    field.setAccessible(true);
                    Object value = field.get(null);
                    if (value instanceof HttpVersion version) {
                        return version;
                    }
                } catch (IllegalAccessException _) {
                    // Ignore inaccessible constants while probing a usable test HttpVersion.
                }
            }
        }
        throw new IllegalStateException("Unable to obtain any HttpVersion constant for test response");
    }
}
