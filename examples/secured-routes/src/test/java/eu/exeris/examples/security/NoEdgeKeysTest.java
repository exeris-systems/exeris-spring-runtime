/*
 * Copyright (C) 2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.examples.security;

import java.net.http.HttpClient;

import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;

import eu.exeris.kernel.community.testkit.security.TestJwt;

import static org.assertj.core.api.Assertions.assertThat;

/** With none of the three edge keys set, the edge holds no key and refuses every token. */
class NoEdgeKeysTest {

    @Test
    void everyTokenIsRefusedAndPublicRoutesStayOpen() throws Exception {
        int port = EdgeKeys.freePort();
        try (ConfigurableApplicationContext ignored = EdgeKeys.boot(port,
                "--security.jwks.uri=", "--security.jwt.issuer=", "--security.jwt.audience=")) {
            EdgeKeys.awaitAccepting(port);
            HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
            String token = TestJwt.builder().claim("scope", "orders:read").serialize();

            assertThat(EdgeKeys.status(client, port, "/orders", token)).isEqualTo(401);
            assertThat(EdgeKeys.status(client, port, "/catalog", null)).isEqualTo(200);
        }
    }
}
