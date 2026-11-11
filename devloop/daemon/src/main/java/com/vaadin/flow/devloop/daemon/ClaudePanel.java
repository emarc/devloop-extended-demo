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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.sun.net.httpserver.HttpExchange;

/**
 * The browser side of "send the annotations to Claude": serves a Copilot panel
 * plugin and records the developer's request in the journal, where Claude's
 * {@code vdx watch} on {@code copilot.annotations.fix-requested} - or the next
 * prompt's briefing - hands it to Claude.
 * <p>
 * Served under {@code /devloop/} on the Copilot bridge's loopback port:
 * <ul>
 * <li>{@code GET copilot-plugin.js} - the panel, which the app adds to its
 * page when it is given {@value #PLUGIN_PROPERTY};</li>
 * <li>{@code GET annotations} - the pending annotations, for the panel;</li>
 * <li>{@code POST fix-annotations} - {@code {message?, view?}}: emits
 * {@code copilot.annotations.fix-requested}.</li>
 * </ul>
 * Only pages from a loopback origin may call it: what is posted here ends up in
 * Claude's context, so another site must not be able to post on the developer's
 * behalf.
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
            case "GET annotations" -> send(exchange, 200, "application/json",
                    "{\"pending\":" + pendingJson(annotations.pending()) + "}");
            case "POST fix-annotations" -> fixRequested(exchange);
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

    private void fixRequested(HttpExchange exchange) throws IOException {
        Map<String, Object> request;
        try (InputStream in = exchange.getRequestBody()) {
            String body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            request = body.isBlank() ? Map.of() : MiniJson.object(body);
        } catch (RuntimeException e) {
            send(exchange, 400, "text/plain", "expected a JSON object");
            return;
        }
        List<Annotations.Note> pending = annotations.pending();
        String message = request.get("message") instanceof String s
                ? s.strip()
                : "";
        Map<String, String> data = new LinkedHashMap<>();
        data.put("count", String.valueOf(pending.size()));
        data.put("ids", String.join(",",
                pending.stream().map(Annotations.Note::id).toList()));
        if (!message.isEmpty()) {
            data.put("message", message);
        }
        if (request.get("view") instanceof String view) {
            data.put("view", view);
        }
        Events.emit("copilot.annotations.fix-requested", "developer",
                "Send to Claude: fix " + pending.size()
                        + " pending annotation(s)"
                        + (message.isEmpty() ? "" : " - \"" + message + "\""),
                List.of(), data);
        send(exchange, 200, "application/json",
                "{\"count\":" + pending.size() + "}");
    }

    private static String pendingJson(List<Annotations.Note> notes) {
        List<String> items = new ArrayList<>();
        for (Annotations.Note note : notes) {
            items.add("{\"id\":\"" + Json.escape(note.id())
                    + "\",\"view\":\"" + Json.escape(note.view())
                    + "\",\"content\":\"" + Json.escape(note.content())
                    + "\",\"components\":" + Json.strings(note.components())
                    + "}");
        }
        return "[" + String.join(",", items) + "]";
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
