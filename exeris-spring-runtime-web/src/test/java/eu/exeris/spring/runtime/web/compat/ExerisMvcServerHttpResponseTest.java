/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.web.compat;

import eu.exeris.spring.runtime.web.ExerisServerResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class ExerisMvcServerHttpResponseTest {

    @Test
    void capturesStatusHeadersAndBodyDeterministically() throws IOException {
        try (ExerisMvcServerHttpResponse response = new ExerisMvcServerHttpResponse()) {
            response.setStatusCode(HttpStatus.CREATED);
            response.getHeaders().set(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
            response.getHeaders().set("X-Compat", "yes");
            response.getBody().write("{\"ok\":true}".getBytes(StandardCharsets.UTF_8));

            assertThat(response.capturedStatusCode().value()).isEqualTo(201);
            assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE)).isEqualTo(MediaType.APPLICATION_JSON_VALUE);
            assertThat(response.getHeaders().getFirst("X-Compat")).isEqualTo("yes");
            assertThat(new String(response.capturedBody(), StandardCharsets.UTF_8)).isEqualTo("{\"ok\":true}");
        }
    }

    @Test
    void convertsToExerisServerResponse() throws IOException {
        try (ExerisMvcServerHttpResponse response = new ExerisMvcServerHttpResponse()) {
            response.setStatusCode(HttpStatus.ACCEPTED);
            response.getHeaders().setContentType(MediaType.TEXT_PLAIN);
            response.getBody().write("compat".getBytes(StandardCharsets.UTF_8));

            ExerisServerResponse exerisResponse = response.toExerisServerResponse();

            assertThat(exerisResponse.status().code()).isEqualTo(202);
            assertThat(exerisResponse.status().reasonPhrase()).isEqualTo("Accepted");
            assertThat(exerisResponse.contentType()).isEqualTo(MediaType.TEXT_PLAIN_VALUE);
            assertThat(new String(exerisResponse.body(), StandardCharsets.UTF_8)).isEqualTo("compat");
        }
    }
}
