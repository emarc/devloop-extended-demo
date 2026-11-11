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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Predicate;

/**
 * The daemon's event journal: one append-only, numbered record of everything
 * that happened to the application while nobody was asking - an apply's
 * verdict, a crash, an error the app logged on a request, a source file that
 * changed on disk and who changed it, a Copilot write.
 * <p>
 * {@code status} answers "what is the state now?"; this answers "what happened
 * since I last looked?", which is the question an agent asks at the start of
 * every turn. Two read paths, one record:
 * <ul>
 * <li>{@code events --cursor NAME} reads what is new for one named consumer and
 * moves that consumer's cursor, so a hook sees each event once;</li>
 * <li>{@code watch} streams events as they are recorded, for a caller that
 * wants to block until something specific happens.</li>
 * </ul>
 * Persisted as JSON lines in {@code target/devloop/events.jsonl}, so a reader
 * that is not a {@code vaadin-dev} client - {@code jq}, {@code tail -f} - sees
 * the same thing, and numbering survives a daemon restart.
 * <p>
 * Prototype extension, not part of upstream Flow.
 */
final class Events {

    /** How many events are kept in memory for {@code events --since}. */
    private static final int KEPT = 2000;

    private static final DateTimeFormatter CLOCK = DateTimeFormatter
            .ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    /**
     * One thing that happened.
     *
     * @param seq
     *            monotonically increasing, never reused for one application
     * @param type
     *            dotted, most general first - {@code app.error},
     *            {@code copilot.write}, {@code files.changed} - so a prefix
     *            filter selects a family
     * @param source
     *            who caused it: {@code daemon}, {@code app}, {@code copilot},
     *            {@code agent}, {@code external}
     * @param summary
     *            one human line
     * @param files
     *            project-relative paths the event concerns, possibly empty
     * @param data
     *            anything else, flat string pairs
     */
    record Event(long seq, Instant at, String type, String source,
            String summary, List<String> files, Map<String, String> data) {

        String json() {
            StringBuilder sb = new StringBuilder();
            sb.append("{\"seq\":").append(seq).append(",\"at\":\"").append(at)
                    .append("\",\"type\":\"").append(Json.escape(type))
                    .append("\",\"source\":\"").append(Json.escape(source))
                    .append("\",\"summary\":\"").append(Json.escape(summary))
                    .append("\",\"files\":").append(Json.strings(files))
                    .append(",\"data\":{");
            boolean first = true;
            for (Map.Entry<String, String> e : data.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append('"').append(Json.escape(e.getKey())).append("\":\"")
                        .append(Json.escape(e.getValue())).append('"');
            }
            return sb.append("}}").toString();
        }

        String text() {
            String head = "#" + seq + " " + CLOCK.format(at) + " "
                    + String.format("%-15s", type) + " " + summary;
            if (files.isEmpty()) {
                return head;
            }
            int shown = Math.min(files.size(), 4);
            return head + "\n      " + String.join("\n      ",
                    files.subList(0, shown))
                    + (files.size() > shown
                            ? "\n      … " + (files.size() - shown) + " more"
                            : "");
        }

        boolean matches(String typePrefix) {
            return typePrefix == null || typePrefix.isEmpty()
                    || type.startsWith(typePrefix);
        }
    }

    private static volatile Events instance;

    private final Path journal;
    private final Path cursors;
    private final Deque<Event> recent = new ArrayDeque<>();
    private final List<BlockingQueue<Event>> subscribers = new CopyOnWriteArrayList<>();
    private long seq;

    private Events(Path workDir) {
        this.journal = workDir.resolve("events.jsonl");
        this.cursors = workDir.resolve("cursors");
        load();
    }

    /** Opens the journal for an application. Called once, by the daemon. */
    static synchronized Events open(Path workDir) {
        if (instance == null) {
            instance = new Events(workDir);
        }
        return instance;
    }

    /**
     * The journal, or {@code null} before the daemon opened one - which is a
     * unit test exercising a class on its own, never a running daemon.
     */
    static Events get() {
        return instance;
    }

    /**
     * Records an event from anywhere in the daemon without each call site
     * having to hold a reference. A no-op when no journal is open.
     */
    static void emit(String type, String source, String summary,
            List<String> files, Map<String, String> data) {
        Events events = instance;
        if (events != null) {
            events.record(type, source, summary, files, data);
        }
    }

    static void emit(String type, String source, String summary) {
        emit(type, source, summary, List.of(), Map.of());
    }

    synchronized Event record(String type, String source, String summary,
            List<String> files, Map<String, String> data) {
        Event event = new Event(++seq, Instant.now(), type, source,
                summary == null ? "" : summary,
                files == null ? List.of() : List.copyOf(files),
                data == null ? Map.of() : new LinkedHashMap<>(data));
        recent.addLast(event);
        while (recent.size() > KEPT) {
            recent.removeFirst();
        }
        try {
            Files.createDirectories(journal.getParent());
            Files.writeString(journal, event.json() + "\n",
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.err.println("events: cannot append to " + journal + ": " + e);
        }
        for (BlockingQueue<Event> subscriber : subscribers) {
            subscriber.offer(event);
        }
        return event;
    }

    /** Events after {@code since}, oldest first, filtered. */
    synchronized List<Event> since(long since, Predicate<Event> filter) {
        List<Event> out = new ArrayList<>();
        for (Event e : recent) {
            if (e.seq > since && filter.test(e)) {
                out.add(e);
            }
        }
        return out;
    }

    synchronized long lastSeq() {
        return seq;
    }

    /**
     * Where a named consumer last read up to; zero for one never seen before,
     * whose first read is bounded by the reader's {@code --limit} instead.
     */
    synchronized long cursor(String name) {
        Path file = cursors.resolve(safe(name));
        try {
            return Long.parseLong(Files.readString(file).trim());
        } catch (IOException | NumberFormatException e) {
            return 0;
        }
    }

    synchronized boolean hasCursor(String name) {
        return Files.exists(cursors.resolve(safe(name)));
    }

    synchronized void moveCursor(String name, long to) {
        try {
            Files.createDirectories(cursors);
            Files.writeString(cursors.resolve(safe(name)), Long.toString(to));
        } catch (IOException e) {
            System.err.println("events: cannot move cursor " + name + ": " + e);
        }
    }

    BlockingQueue<Event> subscribe() {
        BlockingQueue<Event> queue = new LinkedBlockingQueue<>();
        subscribers.add(queue);
        return queue;
    }

    void unsubscribe(BlockingQueue<Event> queue) {
        subscribers.remove(queue);
    }

    /**
     * Continues numbering from the journal on disk, and refills the in-memory
     * window from its tail, so a daemon restart neither reuses a number a
     * consumer's cursor already points past nor forgets what that consumer has
     * not read yet.
     */
    private void load() {
        if (!Files.isRegularFile(journal)) {
            return;
        }
        try {
            List<String> lines = Files.readAllLines(journal,
                    StandardCharsets.UTF_8);
            for (String line : lines.subList(Math.max(0, lines.size() - KEPT),
                    lines.size())) {
                Event e = parse(line);
                if (e != null) {
                    recent.addLast(e);
                    seq = Math.max(seq, e.seq);
                }
            }
        } catch (IOException e) {
            System.err.println("events: cannot read " + journal + ": " + e);
        }
    }

    private static Event parse(String line) {
        try {
            Object parsed = MiniJson.parse(line);
            if (!(parsed instanceof Map<?, ?> map)) {
                return null;
            }
            List<String> files = new ArrayList<>();
            if (map.get("files") instanceof List<?> list) {
                list.forEach(f -> files.add(String.valueOf(f)));
            }
            Map<String, String> data = new LinkedHashMap<>();
            if (map.get("data") instanceof Map<?, ?> d) {
                d.forEach((k, v) -> data.put(String.valueOf(k),
                        String.valueOf(v)));
            }
            return new Event(((Number) map.get("seq")).longValue(),
                    Instant.parse((String) map.get("at")),
                    (String) map.get("type"), (String) map.get("source"),
                    (String) map.get("summary"), files, data);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String safe(String name) {
        return name.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
