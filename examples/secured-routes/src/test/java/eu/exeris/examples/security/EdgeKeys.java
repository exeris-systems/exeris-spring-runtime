/*
 * Copyright (C) 2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.examples.security;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Boots the example outside the Spring test context cache. One Exeris runtime runs per JVM, and the
 * cache keeps a test context's runtime running until the JVM exits, so the module's surefire
 * configuration gives every test class its own fork.
 */
final class EdgeKeys {

    private EdgeKeys() {
    }

    static ConfigurableApplicationContext boot(int port, String... args) {
        String[] all = new String[args.length + 1];
        all[0] = "--exeris.runtime.network.port=" + port;
        System.arraycopy(args, 0, all, 1, args.length);
        return new SpringApplicationBuilder(SecuredRoutesApplication.class).run(all);
    }

    static <T extends Throwable> T find(Throwable failure, Class<T> type) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (type.isInstance(t)) {
                return type.cast(t);
            }
        }
        throw new AssertionError("no " + type.getSimpleName() + " in the cause chain of " + failure);
    }

    static List<String> messages(Throwable failure) {
        List<String> messages = new ArrayList<>();
        for (Throwable t = failure; t != null; t = t.getCause()) {
            messages.add(String.valueOf(t.getMessage()));
        }
        return messages;
    }

    static int status(HttpClient client, int port, String path, String token) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(5))
                .GET();
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    static void awaitAccepting(int port) throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(15));
        while (Instant.now().isBefore(deadline)) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", port), 200);
                return;
            } catch (IOException ignored) {
                Thread.sleep(100);
            }
        }
        throw new AssertionError("nothing accepted on 127.0.0.1:" + port);
    }
}
