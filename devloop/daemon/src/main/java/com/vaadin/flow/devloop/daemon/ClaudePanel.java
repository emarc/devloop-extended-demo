/*
 * Copyright 2000-2026 Vaadin Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.vaadin.flow.devloop.daemon;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import com.sun.net.httpserver.HttpExchange;

/**
 * The browser side of "your turn, Claude": serves a Copilot panel plugin whose
 * one button records {@code developer.your-turn} in the journal. Claude's
 * {@code vdx watch} on that type - or the next prompt's briefing - hands it to
 * Claude, and its review-dev-changes skill says what the hand-over means:
 * review what changed, fix every pending annotation, report.
 * <p>
 * The event carries no text from the browser. A press can only say "now",
 * never what to do, so nothing a page posts here can steer Claude.
 * <p>
 * Served under {@code /devloop/} on the Copilot bridge's loopback port:
 * <ul>
 * <li>{@code GET copilot-plugin.js} - the panel, which the app adds to its
 * page when it is given {@value #PLUGIN_PROPERTY};</li>
 * <li>{@code GET status} - {@code {pendingAnnotations}}, across all views;</li>
 * <li>{@code POST your-turn} - emits {@code developer.your-turn}.</li>
 * </ul>
 * Only pages from a loopback origin may call it, so another site can't hand
 * over on the developer's behalf. In practice the caller is the app, which
 * forwards its own pages' requests (the browser may not reach this port).
 * <p>
 * Prototype extension, not part of upstream Flow.
 */
final class ClaudePanel {

    /** Forwarded to the app, like every {@code vaadin.*} property. */
    static final String PLUGIN_PROPERTY = "vaadin.devloop.copilotPlugin";

    private final Annotations annotations;

    ClaudePanel(Path root) {
        this.annotations = new Annotations(
                root.resolve("vaadin-copilot-comments.json"));
    }

    void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            String origin = exchange.getRequestHeaders().getFirst("Origin");
            if (origin != null && !isLoopback(origin)) {
                send(exchange, 403, "text/plain", "origin not allowed");
                return;
            }
            if (origin != null) {
                exchange.getResponseHeaders()
                        .add("Access-Control-Allow-Origin", origin);
                exchange.getResponseHeaders().add("Vary", "Origin");
            }
            String path = exchange.getRequestURI().getPath()
                    .substring("/devloop/".length());
            String method = exchange.getRequestMethod();
            if (method.equals("OPTIONS")) {
                exchange.getResponseHeaders().add(
                        "Access-Control-Allow-Methods", "GET, POST");
                exchange.getResponseHeaders()
                        .add("Access-Control-Allow-Headers", "Content-Type");
                exchange.sendResponseHeaders(204, -1);
                return;
            }
            switch (method + " " + path) {
            case "GET copilot-plugin.js" -> plugin(exchange);
            case "GET status" -> send(exchange, 200, "application/json",
                    "{\"pendingAnnotations\":" + annotations.pending().size()
                            + "}");
            case "POST your-turn" -> {
                exchange.getRequestBody().readAllBytes();
                Events.emit("developer.your-turn", "developer",
                        "Your turn: the developer handed over to Claude");
                send(exchange, 200, "application/json", "{}");
            }
            default -> send(exchange, 404, "text/plain", "not found");
            }
        }
    }

    private void plugin(HttpExchange exchange) throws IOException {
        try (InputStream in = ClaudePanel.class
                .getResourceAsStream("/devloop/copilot-plugin.js")) {
            if (in == null) {
                send(exchange, 404, "text/plain", "plugin not in the jar");
                return;
            }
            exchange.getResponseHeaders().add("Cache-Control", "no-store");
            send(exchange, 200, "text/javascript",
                    new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private static boolean isLoopback(String origin) {
        try {
            String host = URI.create(origin).getHost();
            return host != null && (host.equals("localhost")
                    || host.equals("127.0.0.1") || host.equals("[::1]"));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static void send(HttpExchange exchange, int status,
            String contentType, String body) throws IOException {
        byte[] out = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type",
                contentType + "; charset=utf-8");
        exchange.sendResponseHeaders(status, out.length == 0 ? -1 : out.length);
        if (out.length > 0) {
            exchange.getResponseBody().write(out);
        }
    }
}
