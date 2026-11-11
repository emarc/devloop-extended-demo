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

import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_DELETE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;
import static java.nio.file.StandardWatchEventKinds.OVERFLOW;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * Notices source edits as they land on disk - not when somebody runs
 * {@code apply} - and records who made them.
 * <p>
 * {@code apply}'s scan answers "what changed since the last apply?" and does
 * not care who changed it. An agent working alongside a developer cares about
 * exactly that: a file it did not write that changed under it was edited by the
 * developer, in an IDE or through Copilot, and it should know before it edits
 * the same file again. So every change is attributed through
 * {@link Attribution} and recorded as one {@code files.changed} event per
 * author per burst:
 * <ul>
 * <li>{@code source=copilot} - already recorded in detail as
 * {@code copilot.write} by {@link CopilotBridge}; repeated here at file level
 * so one event family answers "what changed on disk";</li>
 * <li>{@code source=agent} - claimed by the agent's own hook;</li>
 * <li>{@code source=external} - nobody claimed it: the developer's editor, a
 * {@code git checkout}, a formatter.</li>
 * </ul>
 * Also watches Copilot's UI-annotation file and reports each annotation that
 * changed, through {@link Annotations}.
 * <p>
 * Prototype extension, not part of upstream Flow.
 */
final class SourceWatch {

    /** Edits arrive as bursts - save-all, a refactoring, a checkout. */
    private static final long SETTLE_MILLIS = 400;

    private static final Set<String> SKIPPED_DIRS = Set.of("target",
            "node_modules", "generated", ".git", "bundles", "build", ".idea",
            ".vscode", ".vaadin");

    private final Path root;
    private final Path reactorRoot;
    private final Supplier<List<Path>> moduleDirs;
    private final Map<WatchKey, Path> keys = new ConcurrentHashMap<>();
    private final Set<Path> watchedModules = ConcurrentHashMap.newKeySet();
    private WatchService service;
    private final Annotations annotations;

    SourceWatch(Path root, Path reactorRoot, Supplier<List<Path>> moduleDirs) {
        this.root = root;
        this.reactorRoot = reactorRoot;
        this.moduleDirs = moduleDirs;
        this.annotations = new Annotations(
                root.resolve("vaadin-copilot-comments.json"));
    }

    void start() {
        try {
            service = FileSystems.getDefault().newWatchService();
        } catch (IOException e) {
            System.err.println("sourcewatch: unavailable: " + e);
            return;
        }
        registerModules();
        // The application directory itself, non-recursively, for
        // vaadin-copilot.json and pom.xml.
        register(root);
        Thread t = new Thread(this::run, "devloop-sourcewatch");
        t.setDaemon(true);
        t.start();
    }

    /**
     * The modules in the edit loop are known only once something resolved the
     * classpath, so this is called again after every start and apply and picks
     * up what is new.
     */
    void registerModules() {
        if (service == null) {
            return;
        }
        Set<Path> dirs = new LinkedHashSet<>();
        dirs.add(root);
        dirs.addAll(moduleDirs.get());
        for (Path module : dirs) {
            if (!watchedModules.add(module)) {
                continue;
            }
            Path main = module.resolve("src").resolve("main");
            if (Files.isDirectory(main)) {
                registerTree(main);
            }
        }
    }

    private void registerTree(Path dir) {
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.filter(Files::isDirectory)
                    .filter(d -> !SKIPPED_DIRS
                            .contains(d.getFileName().toString()))
                    .filter(d -> !skipped(d)).forEach(this::register);
        } catch (IOException e) {
            System.err.println("sourcewatch: cannot walk " + dir + ": " + e);
        }
    }

    private void register(Path dir) {
        try {
            keys.put(dir.register(service, ENTRY_CREATE, ENTRY_MODIFY,
                    ENTRY_DELETE), dir);
        } catch (IOException e) {
            System.err.println("sourcewatch: cannot watch " + dir + ": " + e);
        }
    }

    private boolean skipped(Path path) {
        for (Path part : reactorRoot.relativize(path)) {
            if (SKIPPED_DIRS.contains(part.toString())) {
                return true;
            }
        }
        return false;
    }

    private void run() {
        while (true) {
            try {
                WatchKey key = service.take();
                Set<Path> changed = new LinkedHashSet<>();
                collect(key, changed);
                // Drain the burst before reporting it.
                WatchKey next;
                while ((next = service.poll(SETTLE_MILLIS,
                        TimeUnit.MILLISECONDS)) != null) {
                    collect(next, changed);
                }
                report(changed);
            } catch (InterruptedException e) {
                return;
            } catch (RuntimeException e) {
                System.err.println("sourcewatch: " + e);
            }
        }
    }

    private void collect(WatchKey key, Set<Path> changed) {
        Path dir = keys.get(key);
        for (WatchEvent<?> event : key.pollEvents()) {
            if (event.kind() == OVERFLOW || dir == null) {
                continue;
            }
            Path path = dir.resolve((Path) event.context());
            // The application directory is watched for two files only, and
            // nothing below it that src/main does not already cover - data/,
            // logs/ and the like are the app's own output, not edits.
            if (dir.equals(root)) {
                String name = path.getFileName().toString();
                if (name.equals("vaadin-copilot.json")
                        || name.equals("vaadin-copilot-comments.json")
                        || name.equals("pom.xml")) {
                    changed.add(path);
                }
                continue;
            }
            if (event.kind() == ENTRY_CREATE && Files.isDirectory(path)) {
                if (!skipped(path)) {
                    registerTree(path);
                }
                continue;
            }
            if (Files.isDirectory(path) || ignoredFile(path)) {
                continue;
            }
            changed.add(path);
        }
        if (!key.reset()) {
            keys.remove(key);
        }
    }

    private static boolean ignoredFile(Path path) {
        String name = path.getFileName().toString();
        return name.endsWith("~") || name.endsWith(".swp")
                || name.endsWith(".swx") || name.startsWith(".#")
                || name.endsWith(".tmp") || name.equals("4913")
                // Atomic-write temp files: an editor's or an agent's
                // (Claude Code writes X.tmp.<pid>.<hash>, then renames).
                || name.matches(".*\\.tmp\\.\\d+\\.[0-9a-f]+$")
                // GNU sed -i's temporary copy.
                || name.matches("sed[A-Za-z0-9]{6}")
                || name.startsWith(".goutputstream");
    }

    private void report(Set<Path> changed) {
        if (changed.isEmpty()) {
            return;
        }
        // Copilot 25.3 keeps UI annotations in vaadin-copilot-comments.json,
        // reported per annotation; vaadin-copilot.json is Copilot's project
        // state, reported as a file.
        if (changed.remove(annotations.file())
                && !annotations.report(rel(annotations.file()))) {
            Events.emit("copilot.annotations", "copilot",
                    "vaadin-copilot-comments.json changed (unreadable)",
                    List.of(rel(annotations.file())), Map.of());
        }
        Path state = root.resolve("vaadin-copilot.json");
        if (changed.remove(state)) {
            Events.emit("copilot.state", "copilot",
                    "vaadin-copilot.json changed", List.of(rel(state)),
                    Map.of());
        }
        // Grouped by author, and by label within an author: one Copilot
        // operation touching three files is one event, not three.
        Map<String, List<String>> byAuthor = new LinkedHashMap<>();
        for (Path path : changed) {
            String author = Attribution.explain(path)
                    .map(c -> c.source() + "\u0000"
                            + (c.label() == null ? "" : c.label()))
                    .orElse("external\u0000");
            byAuthor.computeIfAbsent(author, a -> new ArrayList<>())
                    .add(rel(path) + (Files.exists(path) ? "" : " (deleted)"));
        }
        byAuthor.forEach((author, files) -> {
            String[] parts = author.split("\u0000", 2);
            String source = parts[0];
            String label = parts.length > 1 ? parts[1] : "";
            String who = switch (source) {
            case "copilot" -> "Copilot";
            case "agent" -> "the agent";
            default -> "someone outside the daemon (IDE, editor, git)";
            };
            Map<String, String> data = new LinkedHashMap<>();
            if (!label.isEmpty()) {
                data.put("label", label);
            }
            Events.emit("files.changed", source,
                    files.size() + " file(s) changed by " + who
                            + (label.isEmpty() ? "" : ": " + label),
                    files, data);
        });
    }

    private String rel(Path path) {
        try {
            return reactorRoot.relativize(path).toString();
        } catch (IllegalArgumentException e) {
            return path.toString();
        }
    }
}
