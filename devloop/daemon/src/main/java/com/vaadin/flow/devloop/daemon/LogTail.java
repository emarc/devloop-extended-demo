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
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Follows {@code app.log} continuously, independently of {@link AppLog.Watch}
 * - which belongs to {@code apply}/{@code status} and is drained by them - and
 * turns what the app says on its own into journal events:
 * <ul>
 * <li>{@code app.error}: an ERROR/SEVERE/FATAL report or an uncaught exception,
 * one event per report with its {@code Caused by} chain folded in. This is the
 * error a click in the browser provokes ten minutes after the last apply, which
 * no command was waiting for;</li>
 * <li>{@code trigger.NAME}: a line matching a pattern registered with
 * {@code vaadin-dev trigger add NAME REGEX}, persisted in
 * {@code .vaadin/triggers.properties} - beside the scripts, so it survives
 * {@code mvn clean} and a team can commit the triggers it cares about;</li>
 * </ul>
 * and hands every raw line to {@code watch --log} subscribers.
 * <p>
 * Polled rather than watched: the file is truncated on every launch and
 * appended in small writes, and a 250 ms poll of one file's length is cheaper
 * and simpler than reasoning about WatchService coalescing.
 * <p>
 * Prototype extension, not part of upstream Flow.
 */
final class LogTail {

    private static final Pattern ERROR_LINE = Pattern
            .compile("\\b(ERROR|SEVERE|FATAL)\\b|^Exception in thread ");
    /**
     * A line that starts a new log record. Anything else that follows an error
     * - a stack frame, a {@code Caused by}, Spring Boot's failure-analysis
     * banner and its description - belongs to that error's report.
     */
    private static final Pattern RECORD_START = Pattern.compile(
            "^(\\d{4}-\\d\\d-\\d\\d[T ]\\d|\\d\\d:\\d\\d:\\d\\d|\\[\\w|\\w+ \\d+, \\d{4})");
    private static final Pattern CAUSE_LINE = Pattern
            .compile("^Caused by: |^[\\w.$]+(Exception|Error)(: |$)");
    /** The Spring Boot/logback prefix, so a summary starts at the message. */
    private static final Pattern PREFIX = Pattern.compile(
            "^\\S+\\s+(?:ERROR|SEVERE|FATAL)\\s+\\d+\\s+---\\s+\\[[^\\]]*\\]\\s+\\S+\\s+:\\s");

    /**
     * Frames a developer did not write. A heuristic - the daemon could learn
     * the app's own packages from the reactor's source roots instead.
     */
    private static final Pattern LIBRARY_FRAME = Pattern.compile(
            "^\\s*at (?:[\\w.$]+/)?(java|javax|jdk|sun|jakarta|org\\.(springframework|apache|atmosphere|hibernate|eclipse|h2|jboss|slf4j)|com\\.(vaadin\\.(flow|base|copilot|hilla)|sun|fasterxml|zaxxer)|io\\.(netty|micrometer)|tools\\.jackson|ch\\.qos)\\.");

    private final Path log;
    private final Path triggersFile;
    private final List<Consumer<String>> lineListeners = new CopyOnWriteArrayList<>();
    private volatile Map<String, Pattern> triggers = Map.of();

    private long offset;
    private final StringBuilder partial = new StringBuilder();

    /** The error report being collected, flushed when the report ends. */
    private List<String> pending;
    private long pendingSince;

    LogTail(Path root) {
        this.log = Launch.workDir(root).resolve("app.log");
        this.triggersFile = root.resolve(".vaadin").resolve("triggers.properties");
        loadTriggers();
    }

    void start() {
        // What a previous daemon's app wrote is not news: start at the end.
        // The next launch truncates the file, which resets this to zero.
        try {
            offset = Files.size(log);
        } catch (IOException e) {
            offset = 0;
        }
        Thread t = new Thread(this::run, "devloop-logtail");
        t.setDaemon(true);
        t.start();
    }

    void addLineListener(Consumer<String> listener) {
        lineListeners.add(listener);
    }

    void removeLineListener(Consumer<String> listener) {
        lineListeners.remove(listener);
    }

    Map<String, Pattern> triggers() {
        return triggers;
    }

    synchronized String addTrigger(String name, String regex) {
        try {
            Map<String, Pattern> next = new LinkedHashMap<>(triggers);
            next.put(name, Pattern.compile(regex));
            triggers = next;
            saveTriggers();
            return "trigger " + name + " = " + regex;
        } catch (PatternSyntaxException e) {
            return "bad regex: " + e.getDescription();
        }
    }

    synchronized boolean removeTrigger(String name) {
        Map<String, Pattern> next = new LinkedHashMap<>(triggers);
        boolean removed = next.remove(name) != null;
        triggers = next;
        saveTriggers();
        return removed;
    }

    private void run() {
        while (true) {
            try {
                poll();
                Thread.sleep(250);
            } catch (InterruptedException e) {
                return;
            } catch (RuntimeException e) {
                System.err.println("logtail: " + e);
            }
        }
    }

    private void poll() {
        long size;
        try {
            size = Files.size(log);
        } catch (IOException e) {
            return;
        }
        if (size < offset) {
            // A new launch truncated the log: start over, and do not carry
            // half a line or half a report across processes.
            offset = 0;
            partial.setLength(0);
            flush();
        }
        if (size > offset) {
            try (RandomAccessFile file = new RandomAccessFile(log.toFile(),
                    "r")) {
                file.seek(offset);
                byte[] bytes = new byte[(int) Math.min(size - offset,
                        4 * 1024 * 1024)];
                file.readFully(bytes);
                offset += bytes.length;
                partial.append(new String(bytes, StandardCharsets.UTF_8));
            } catch (IOException e) {
                return;
            }
            int nl;
            while ((nl = partial.indexOf("\n")) >= 0) {
                String line = partial.substring(0, nl).replace("\r", "");
                partial.delete(0, nl + 1);
                onLine(line);
            }
        }
        // A report is over when the log goes quiet as well as when an
        // unrelated line follows it.
        if (pending != null
                && System.currentTimeMillis() - pendingSince > 750) {
            flush();
        }
    }

    private void onLine(String line) {
        for (Consumer<String> listener : lineListeners) {
            listener.accept(line);
        }
        triggers.forEach((name, pattern) -> {
            if (pattern.matcher(line).find()) {
                Events.emit("trigger." + name, "app", clip(line), List.of(),
                        Map.of("pattern", pattern.pattern()));
            }
        });
        if (pending != null && !RECORD_START.matcher(line).find()
                && pending.size() < 400) {
            pending.add(line);
            return;
        }
        flush();
        if (ERROR_LINE.matcher(line).find()) {
            pending = new ArrayList<>();
            pending.add(line);
            pendingSince = System.currentTimeMillis();
        }
    }

    private void flush() {
        List<String> report = pending;
        pending = null;
        if (report == null || report.isEmpty()) {
            return;
        }
        // The message, or when the record's own line carries none - Spring
        // Boot's failure analysis logs an empty ERROR and then a banner - the
        // first line of the report that says something.
        String head = PREFIX.matcher(report.get(0)).replaceFirst("").strip();
        if (head.isEmpty()) {
            head = report.stream().skip(1).map(String::strip)
                    .filter(l -> !l.isEmpty() && !l.matches("\\*+")
                            && !l.equals("APPLICATION FAILED TO START")
                            && !l.endsWith(":"))
                    .findFirst().orElse("(empty error record)");
        }
        Map<String, String> data = new LinkedHashMap<>();
        List<String> causes = report.stream().skip(1)
                .filter(l -> CAUSE_LINE.matcher(l).find()).map(LogTail::clip)
                .toList();
        if (!causes.isEmpty()) {
            data.put("rootCause", causes.get(causes.size() - 1));
            data.put("causes", String.join(" | ", causes));
        }
        report.stream().skip(1).filter(l -> l.trim().startsWith("at "))
                .filter(l -> LIBRARY_FRAME.matcher(l).find() == false)
                .findFirst()
                .ifPresent(frame -> data.put("firstAppFrame", frame.trim()));
        data.put("lines", Integer.toString(report.size()));
        Events.emit("app.error", "app", clip(head), List.of(), data);
    }

    private static String clip(String line) {
        String s = line.strip();
        return s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }

    private void loadTriggers() {
        if (!Files.isRegularFile(triggersFile)) {
            return;
        }
        Properties props = new Properties();
        try (var in = Files.newBufferedReader(triggersFile)) {
            props.load(in);
        } catch (IOException e) {
            return;
        }
        Map<String, Pattern> loaded = new LinkedHashMap<>();
        props.stringPropertyNames().stream().sorted().forEach(name -> {
            try {
                loaded.put(name, Pattern.compile(props.getProperty(name)));
            } catch (PatternSyntaxException e) {
                System.err.println("logtail: ignoring trigger " + name + ": "
                        + e.getDescription());
            }
        });
        triggers = loaded;
    }

    private void saveTriggers() {
        Properties props = new Properties();
        triggers.forEach((name, p) -> props.setProperty(name, p.pattern()));
        try {
            Files.createDirectories(triggersFile.getParent());
            try (var out = Files.newBufferedWriter(triggersFile)) {
                props.store(out, "vaadin-dev triggers: NAME=REGEX on app.log");
            }
        } catch (IOException e) {
            System.err.println("logtail: cannot save triggers: " + e);
        }
    }
}
