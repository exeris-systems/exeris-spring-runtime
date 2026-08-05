/*
 * Copyright (C) 2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * Commercial resale of this software as a competing product is prohibited.
 */
package eu.exeris.spring.runtime.web;

import eu.exeris.kernel.spi.http.HttpHeader;
import eu.exeris.kernel.spi.http.HttpResponse;
import eu.exeris.kernel.spi.http.HttpStatus;
import eu.exeris.kernel.spi.http.HttpVersion;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the {@link ExerisErrorStatusResolver} chain in {@link ExerisErrorMapper}.
 */
class ExerisErrorMapperResolverChainTest {

    private static final HttpVersion VERSION = HttpVersion.HTTP_1_1;

    @Test
    void noResolvers_stillMapsTo500() {
        HttpResponse response = new ExerisErrorMapper().mapUnhandled(new IllegalStateException(), VERSION);

        assertThat(response.status()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    void resolverStatusWins_overThe500Fallback() {
        ExerisErrorMapper mapper = new ExerisErrorMapper(List.of(
                _ -> Optional.of(ExerisErrorStatus.of(HttpStatus.FORBIDDEN))));

        assertThat(mapper.mapUnhandled(new IllegalStateException(), VERSION).status())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void resolverHeaders_areEmittedAlongsideContentLength() {
        ExerisErrorMapper mapper = new ExerisErrorMapper(List.of(
                _ -> Optional.of(new ExerisErrorStatus(
                        HttpStatus.UNAUTHORIZED,
                        List.of(new HttpHeader("WWW-Authenticate", "Bearer"))))));

        HttpResponse response = mapper.mapUnhandled(new IllegalStateException(), VERSION);

        assertThat(response.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.headers())
                .extracting(HttpHeader::name)
                .as("the status-mandated header must not displace Content-Length")
                .contains("WWW-Authenticate", "Content-Length");
    }

    @Test
    void firstNonEmptyResolverWins_andLaterOnesAreNotConsulted() {
        boolean[] secondConsulted = {false};
        ExerisErrorMapper mapper = new ExerisErrorMapper(List.of(
                _ -> Optional.empty(),
                _ -> Optional.of(ExerisErrorStatus.of(HttpStatus.FORBIDDEN)),
                _ -> {
                    secondConsulted[0] = true;
                    return Optional.of(ExerisErrorStatus.of(HttpStatus.NOT_FOUND));
                }));

        assertThat(mapper.mapUnhandled(new IllegalStateException(), VERSION).status())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(secondConsulted[0]).isFalse();
    }

    @Test
    void throwingResolver_isSkipped_andDoesNotReplaceTheOriginalError() {
        // A failure inside error mapping must not become the error. If it propagated, the original
        // cause would be lost and the caller would get an unrelated 500 — worse than the 500 the
        // resolver chain exists to avoid.
        ExerisErrorMapper mapper = new ExerisErrorMapper(List.of(
                _ -> {
                    throw new IllegalStateException("resolver is broken");
                },
                _ -> Optional.of(ExerisErrorStatus.of(HttpStatus.FORBIDDEN))));

        assertThat(mapper.mapUnhandled(new IllegalStateException("original"), VERSION).status())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void allResolversThrowing_fallsBackTo500RatherThanPropagating() {
        ExerisErrorMapper mapper = new ExerisErrorMapper(List.of(
                _ -> {
                    throw new IllegalStateException("broken");
                }));

        assertThat(mapper.mapUnhandled(new IllegalStateException("original"), VERSION).status())
                .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
