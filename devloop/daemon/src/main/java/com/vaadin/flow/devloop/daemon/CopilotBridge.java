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
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicLong;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Stands in for Copilot's IDE plugin so every file Copilot writes passes
 * through the daemon, with the label Copilot gives the operation.
 * <p>
 * Copilot writes source files from inside the application JVM and logs
 * nothing about it, so neither {@code app.log} nor a file watcher can tell a
 * Copilot edit from a developer's. But when an IDE plugin is present, Copilot
 * does not write the file itself: it POSTs {@code {command, projectBasePath,
 * data:{file, undoLabel, content}}} to the endpoint named in the plugin's
 * {@code .copilot-plugin} properties file, so that the edit lands in the IDE's
 * undo history. Copilot finds that file in one of two ways:
 * <ul>
 * <li>{@code -Dvaadin.copilot.pluginDotFilePath} - but only when it detected
 * that an IDE launched the app, which a daemon-launched app never is;</li>
 * <li>otherwise the first of {@code .idea/}, {@code .vscode/},
 * {@code .eclipse/} {@code .copilot-plugin} in the application module.</li>
 * </ul>
 * So the daemon publishes its dot file in the {@code .eclipse/} slot - the one
 * a real plugin is least likely to occupy - and sets the property as well, for
 * the IDE-launched case.
 * <p>
 * So the daemon publishes its own dot file and endpoint, and for each request:
 * <ul>
 * <li>if a real IDE plugin is running for this project, forwards the request
 * to it unchanged and relays its answer - the developer keeps IDE undo;</li>
 * <li>otherwise performs it: writes the file, keeps a per-file undo/redo stack
 * Copilot's own undo button drives;</li>
 * </ul>
 * and in both cases records a {@code copilot.write} / {@code copilot.undo} /
 * {@code copilot.redo} event, keeps before/after snapshots of the file under
 * {@code target/devloop/copilot/}, and claims the new content in
 * {@link Attribution} so the file watcher names Copilot as its author.
 * <p>
 * Prototype extension, not part of upstream Flow - and it relies on Copilot
 * internals (the plugin protocol is undocumented), which is exactly the
 * dependency a supported hook in Copilot would remove.
 */
final class CopilotBridge {

    static final String DOT_FILE_PROPERTY = "vaadin.copilot.pluginDotFilePath";

    /** What the daemon can do itself when no IDE plugin is there to ask. */
    private static final String OWN_ACTIONS = "write,writeBase64,undo,redo,refresh";

    private record Entry(Path file, byte[] before, byte[] after, String label) {
    }

    private final Path root;
    private final Path reactorRoot;
    private final Path workDir;
    private final Path snapshots;
    private final AtomicLong opIds = new AtomicLong();
    private final Map<Path, Deque<Entry>> undo = new LinkedHashMap<>();
    private final Map<Path, Deque<Entry>> redo = new LinkedHashMap<>();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2)).build();
    private static final String MARKER = "vaadin-dev Copilot bridge";

    /**
     * Makes Copilot's writes live. An IDE plugin's write is followed by the
     * IDE's compile and hot-swap; under the dev loop nothing applies an edit
     * until somebody runs apply, so without this Copilot reports success and
     * the browser never changes. Debounced: one Copilot operation writes
     * several files in quick succession.
     */
    private volatile Runnable afterWrite;
    private final java.util.concurrent.ScheduledExecutorService applier = java.util.concurrent.Executors
            .newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "copilot-auto-apply");
                t.setDaemon(true);
                return t;
            });
    private java.util.concurrent.ScheduledFuture<?> pendingApply;

    private HttpServer server;
    private Path dotFile;
    private Path slotFile;

    CopilotBridge(Path root, Path reactorRoot) {
        this.root = root;
        this.reactorRoot = reactorRoot;
        this.workDir = Launch.workDir(root);
        this.snapshots = workDir.resolve("copilot");
    }

    /**
     * Binds the endpoint and publishes the dot file, then points the app at it
     * through the system property the launch forwards. Off with
     * {@code -Dvaadin.dev.copilotBridge=false}.
     */
    void start() {
        if (!Boolean.parseBoolean(
                System.getProperty("vaadin.dev.copilotBridge", "true"))) {
            return;
        }
        try {
            server = HttpServer.create(
                    new InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
                    16);
            server.createContext("/copilot", this::handle);
            server.createContext("/devloop/", new ClaudePanel(root)::handle);
            server.setExecutor(null);
            server.start();
            // Copilot derives the project from the dot file's location - its
            // grandparent - and sends that along as projectBasePath, so the file
            // has to sit one directory below the application.
            dotFile = root.resolve("target").resolve(".copilot-plugin");
            slotFile = root.resolve(".eclipse").resolve(".copilot-plugin");
            ensureDotFile();
            System.setProperty(DOT_FILE_PROPERTY, dotFile.toString());
            InetAddress bound = server.getAddress().getAddress();
            String host = bound instanceof java.net.Inet6Address
                    ? "[" + bound.getHostAddress() + "]"
                    : bound.getHostAddress();
            System.setProperty(ClaudePanel.PLUGIN_PROPERTY, "http://" + host
                    + ":" + server.getAddress().getPort()
                    + "/devloop/copilot-plugin.js");
            System.out.println("copilot bridge on port "
                    + server.getAddress().getPort() + ", dot file " + dotFile);
        } catch (IOException e) {
            System.err.println("copilot bridge unavailable: " + e);
        }
    }

    /**
     * What to run after Copilot changed files. Off with
     * {@code -Dvaadin.dev.copilotAutoApply=false}.
     */
    void afterWrite(Runnable action) {
        if (Boolean.parseBoolean(
                System.getProperty("vaadin.dev.copilotAutoApply", "true"))) {
            this.afterWrite = action;
        }
    }

    private synchronized void scheduleApply() {
        Runnable action = afterWrite;
        if (action == null) {
            return;
        }
        if (pendingApply != null) {
            pendingApply.cancel(false);
        }
        pendingApply = applier.schedule(() -> {
            try {
                action.run();
            } catch (RuntimeException e) {
                System.err.println("copilot auto-apply: " + e);
            }
        }, 500, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
        try {
            if (dotFile != null) {
                Files.deleteIfExists(dotFile);
            }
            if (slotFile != null && isOurs(slotFile)) {
                Files.deleteIfExists(slotFile);
            }
        } catch (IOException e) {
            // Left behind, it points at a closed port: Copilot then falls back
            // to writing files itself, which is the right thing anyway.
        }
    }

    /**
     * Rewrites the dot file. Called on every launch too: {@code mvn clean}
     * deletes it, and an app that finds no dot file quietly writes files
     * itself again, unattributed.
     */
    void ensureDotFile() {
        if (server == null) {
            return;
        }
        try {
            writeDotFile(realPlugin());
        } catch (IOException e) {
            System.err.println("copilot bridge: cannot write dot file: " + e);
        }
    }

    /**
     * The dot file advertises what can actually be done: the real plugin's
     * actions when one is there to forward to, the daemon's own otherwise.
     * Rewritten whenever a request finds the answer has changed - an IDE that
     * opened the project after the app started.
     */
    private void writeDotFile(Optional<Properties> real) throws IOException {
        Properties props = new Properties();
        props.setProperty("port",
                Integer.toString(server.getAddress().getPort()));
        props.setProperty("endpoint", "http://127.0.0.1:"
                + server.getAddress().getPort() + "/copilot");
        props.setProperty("ide", real.map(p -> p.getProperty("ide", "devloop"))
                .orElse("devloop"));
        props.setProperty("version",
                real.map(p -> p.getProperty("version", "1.0")).orElse("1.0"));
        props.setProperty("supportedActions",
                real.map(p -> p.getProperty("supportedActions", OWN_ACTIONS))
                        .orElse(OWN_ACTIONS));
        for (Path target : List.of(dotFile, slotFile)) {
            // Never over a real Eclipse plugin's own file.
            if (target == slotFile && Files.exists(slotFile)
                    && !isOurs(slotFile)) {
                continue;
            }
            Files.createDirectories(target.getParent());
            try (var out = Files.newBufferedWriter(target)) {
                props.store(out, MARKER
                        + " - written by the daemon, deleted on shutdown");
            }
        }
    }

    private static boolean isOurs(Path file) {
        try {
            return Files.readString(file).contains(MARKER);
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * A real IDE plugin's dot file for this project, if one is running, with
     * the project base it implies recorded as {@code projectBasePath}.
     */
    private Optional<Properties> realPlugin() {
        for (Path base : List.of(root, reactorRoot)) {
            for (String meta : List.of(".idea", ".vscode", ".eclipse")) {
                Path file = base.resolve(meta).resolve(".copilot-plugin");
                if (Files.isReadable(file) && !isOurs(file)) {
                    Properties props = new Properties();
                    try (var in = Files.newBufferedReader(file)) {
                        props.load(in);
                        if (props.getProperty("endpoint") != null) {
                            props.setProperty("projectBasePath",
                                    base.toString());
                            return Optional.of(props);
                        }
                    } catch (IOException e) {
                        // An unreadable dot file is no plugin.
                    }
                }
            }
        }
        return Optional.empty();
    }

    private void handle(HttpExchange exchange) throws IOException {
        byte[] body;
        try (InputStream in = exchange.getRequestBody()) {
            body = in.readAllBytes();
        }
        String reply;
        int status = 200;
        try {
            reply = dispatch(new String(body, StandardCharsets.UTF_8), body);
        } catch (Exception e) {
            status = 500;
            reply = "{\"error\":\"" + Json.escape(e.toString()) + "\"}";
            System.err.println("copilot bridge: " + e);
        }
        byte[] out = reply.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, out.length == 0 ? -1 : out.length);
        if (out.length > 0) {
            exchange.getResponseBody().write(out);
        }
        exchange.close();
    }

    @SuppressWarnings("unchecked")
    private String dispatch(String json, byte[] raw) throws Exception {
        Map<String, Object> request = MiniJson.object(json);
        String command = String.valueOf(request.get("command"));
        Map<String, Object> data = request.get("data") instanceof Map<?, ?> m
                ? (Map<String, Object>) m
                : Map.of();

        Optional<Properties> real = realPlugin();
        writeDotFile(real);

        // What the file held before, read before anyone writes it - the
        // forwarded plugin included - so the snapshot is the true "before".
        Path file = data.get("file") instanceof String f ? Path.of(f) : null;
        byte[] before = file != null && Files.isRegularFile(file)
                ? Files.readAllBytes(file)
                : null;
        List<Path> files = new ArrayList<>();
        if (data.get("files") instanceof List<?> list) {
            list.forEach(f -> files.add(Path.of(String.valueOf(f))));
        }

        String reply = null;
        boolean forwarded = false;
        if (real.isPresent()) {
            try {
                reply = forward(real.get(), raw);
                forwarded = true;
            } catch (IOException e) {
                // A dot file left behind by an IDE that has since closed:
                // do the work rather than fail Copilot's operation.
                System.err.println("copilot bridge: IDE plugin unreachable ("
                        + e + "), handling " + command + " in the daemon");
            }
        }
        if (!forwarded) {
            reply = perform(command, data, file, before, files);
        }
        record(command, data, file, before, files, forwarded);
        // A forwarded write is the IDE's to compile; the daemon's own is ours.
        if (!forwarded && List.of("write", "writeBase64", "undo", "redo")
                .contains(command)) {
            scheduleApply();
        }
        return reply;
    }

    private String forward(Properties plugin, byte[] raw)
            throws IOException, InterruptedException {
        // The request names the project by where the daemon's dot file is;
        // the plugin knows it by where its own is.
        String body = new String(raw, StandardCharsets.UTF_8);
        String ours = Json.escape(root.toString());
        String theirs = Json.escape(plugin.getProperty("projectBasePath"));
        raw = body.replace("\"projectBasePath\":\"" + ours + "\"",
                "\"projectBasePath\":\"" + theirs + "\"")
                .getBytes(StandardCharsets.UTF_8);
        HttpResponse<String> response = http.send(HttpRequest
                .newBuilder(URI.create(plugin.getProperty("endpoint")))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofByteArray(raw)).build(),
                HttpResponse.BodyHandlers.ofString());
        return response.body() == null ? "" : response.body();
    }

    private synchronized String perform(String command, Map<String, Object> data,
            Path file, byte[] before, List<Path> files) throws IOException {
        switch (command) {
        case "write", "writeBase64" -> {
            String content = String.valueOf(data.get("content"));
            byte[] after = command.equals("writeBase64")
                    ? Base64.getDecoder().decode(content)
                    : content.getBytes(StandardCharsets.UTF_8);
            Attribution.claim(file, after, "copilot", label(data));
            Files.createDirectories(file.getParent());
            Files.write(file, after);
            undo.computeIfAbsent(file, f -> new ArrayDeque<>())
                    .push(new Entry(file, before, after, label(data)));
            redo.remove(file);
            return "{}";
        }
        case "undo", "redo" -> {
            boolean undoing = command.equals("undo");
            boolean performed = false;
            for (Path f : files) {
                Deque<Entry> from = (undoing ? undo : redo).get(f);
                if (from == null || from.isEmpty()) {
                    continue;
                }
                Entry entry = from.pop();
                byte[] target = undoing ? entry.before : entry.after;
                Attribution.claim(f, target, "copilot",
                        command + " " + entry.label);
                if (target == null) {
                    Files.deleteIfExists(f);
                } else {
                    Files.write(f, target);
                }
                (undoing ? redo : undo)
                        .computeIfAbsent(f, k -> new ArrayDeque<>())
                        .push(entry);
                performed = true;
            }
            return "{\"performed\":" + performed + "}";
        }
        default -> {
            // refresh, heartbeat, showInIde: nothing to do without an IDE.
            return "{}";
        }
        }
    }

    private void record(String command, Map<String, Object> data, Path file,
            byte[] before, List<Path> files, boolean forwarded)
            throws IOException {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("command", command);
        String label = label(data);
        if (!label.isEmpty()) {
            fields.put("label", label);
        }
        fields.put("via", forwarded ? "ide-plugin" : "daemon");
        switch (command) {
        case "write", "writeBase64" -> {
            if (file == null) {
                return;
            }
            // Read back rather than taken from the request, which is what a
            // forwarded write actually left on disk.
            byte[] after = Files.isRegularFile(file) ? Files.readAllBytes(file)
                    : null;
            if (forwarded) {
                Attribution.claim(file, after, "copilot", label);
            }
            long op = opIds.incrementAndGet();
            Path snap = snapshots.resolve(op + "-" + file.getFileName());
            Files.createDirectories(snapshots);
            Files.write(Path.of(snap + ".before"),
                    before == null ? new byte[0] : before);
            Files.write(Path.of(snap + ".after"),
                    after == null ? new byte[0] : after);
            fields.put("before", snap + ".before");
            fields.put("after", snap + ".after");
            fields.put("lines", lineDelta(before, after));
            Events.emit("copilot.write", "copilot",
                    (label.isEmpty() ? "Copilot wrote a file" : label) + " ("
                            + fields.get("lines") + ")",
                    List.of(rel(file)), fields);
        }
        case "undo", "redo" -> Events.emit("copilot." + command, "copilot",
                "Copilot " + command + " of " + files.size() + " file(s)",
                files.stream().map(this::rel).toList(), fields);
        case "showInIde" -> Events.emit("copilot.show", "copilot",
                "Copilot asked to show a location in the IDE",
                file == null ? List.of() : List.of(rel(file)), fields);
        default -> {
            // heartbeat, refresh: not worth a journal line.
        }
        }
    }

    private static String label(Map<String, Object> data) {
        Object label = data.get("undoLabel");
        return label == null ? "" : String.valueOf(label);
    }

    /** "+3/-1 lines": a summary for a human, not a diff. */
    private static String lineDelta(byte[] before, byte[] after) {
        List<String> a = before == null ? List.of()
                : new String(before, StandardCharsets.UTF_8).lines().toList();
        List<String> b = after == null ? List.of()
                : new String(after, StandardCharsets.UTF_8).lines().toList();
        Map<String, Integer> counts = new LinkedHashMap<>();
        a.forEach(l -> counts.merge(l, 1, Integer::sum));
        int added = 0;
        for (String l : b) {
            Integer n = counts.get(l);
            if (n == null || n == 0) {
                added++;
            } else {
                counts.put(l, n - 1);
            }
        }
        int removed = counts.values().stream().mapToInt(Integer::intValue)
                .sum();
        return before == null ? "new file, " + b.size() + " lines"
                : "+" + added + "/-" + removed + " lines";
    }

    private String rel(Path path) {
        try {
            return reactorRoot.relativize(path.toAbsolutePath().normalize())
                    .toString();
        } catch (IllegalArgumentException e) {
            return path.toString();
        }
    }
}
