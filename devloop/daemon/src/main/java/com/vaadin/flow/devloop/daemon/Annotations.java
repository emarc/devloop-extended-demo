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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Turns a change to Copilot's UI-annotation file into one event per
 * annotation that was added, edited, resolved or removed - with its text, view
 * and the components it points at - instead of "the file changed".
 * <p>
 * Copilot 25.3 keeps annotations in {@code vaadin-copilot-comments.json}: an
 * object keyed by view path, each a list of
 * {@code {id, content, status, viewPath, viewFqn, viewFilePath, createdAt,
 * updatedAt, selectedComponents:[{treePath, displayName}]}}. The file is
 * diffed against the last version seen, which is read once at daemon start so
 * that existing annotations are not reported as new.
 * <p>
 * Prototype extension, not part of upstream Flow.
 */
final class Annotations {

    record Note(String id, String view, String viewFile, String status,
            String content, List<String> components) {
    }

    private final Path file;
    private Map<String, Note> last;

    Annotations(Path file) {
        this.file = file;
        this.last = read();
    }

    Path file() {
        return file;
    }

    /** The annotations still to do, in file order; empty if unreadable. */
    List<Note> pending() {
        Map<String, Note> now = read();
        return now == null ? List.of()
                : now.values().stream()
                        .filter(note -> note.status().equals("pending"))
                        .toList();
    }

    /** Emits the differences since the last call; false if unreadable. */
    synchronized boolean report(String relativePath) {
        Map<String, Note> now = read();
        if (now == null) {
            return false;
        }
        Map<String, Note> before = last == null ? Map.of() : last;
        for (Note note : now.values()) {
            Note old = before.get(note.id());
            if (old == null) {
                emit("copilot.annotation.added", note, relativePath);
            } else if (!Objects.equals(old.status(), note.status())) {
                emit("copilot.annotation." + note.status(), note,
                        relativePath);
            } else if (!old.equals(note)) {
                emit("copilot.annotation.edited", note, relativePath);
            }
        }
        for (Note old : before.values()) {
            if (!now.containsKey(old.id())) {
                emit("copilot.annotation.removed", old, relativePath);
            }
        }
        last = now;
        return true;
    }

    private static void emit(String type, Note note, String relativePath) {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("id", note.id());
        data.put("view", note.view());
        if (note.viewFile() != null) {
            data.put("viewFile", note.viewFile());
        }
        data.put("status", note.status());
        data.put("content", note.content());
        data.put("components", String.join(" | ", note.components()));
        String on = note.components().isEmpty() ? note.view()
                : note.view() + " → " + String.join(", ", note.components());
        Events.emit(type, "copilot",
                "\"" + clip(note.content()) + "\" on " + on + " ["
                        + note.status() + "]",
                List.of(relativePath), data);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Note> read() {
        if (!Files.isRegularFile(file)) {
            return new LinkedHashMap<>();
        }
        try {
            Map<String, Object> root = MiniJson
                    .object(Files.readString(file, StandardCharsets.UTF_8));
            Map<String, Note> notes = new LinkedHashMap<>();
            for (Map.Entry<String, Object> view : root.entrySet()) {
                if (!(view.getValue() instanceof List<?> list)) {
                    continue;
                }
                for (Object o : list) {
                    if (!(o instanceof Map<?, ?> m)) {
                        continue;
                    }
                    Map<String, Object> a = (Map<String, Object>) m;
                    List<String> components = new ArrayList<>();
                    if (a.get("selectedComponents") instanceof List<?> sel) {
                        for (Object c : sel) {
                            if (c instanceof Map<?, ?> cm) {
                                Object name = cm.get("displayName");
                                components.add(String.valueOf(
                                        name != null ? name : cm.get("treePath")));
                            }
                        }
                    }
                    String id = String.valueOf(a.get("id"));
                    notes.put(id, new Note(id,
                            String.valueOf(a.getOrDefault("viewPath",
                                    view.getKey())),
                            a.get("viewFilePath") == null ? null
                                    : String.valueOf(a.get("viewFilePath")),
                            String.valueOf(a.getOrDefault("status", "pending")),
                            String.valueOf(a.getOrDefault("content", "")),
                            components));
                }
            }
            return notes;
        } catch (IOException | RuntimeException e) {
            // Mid-write, or a shape this version does not know.
            return null;
        }
    }

    private static String clip(String s) {
        String one = s.replaceAll("\\s+", " ").strip();
        return one.length() > 120 ? one.substring(0, 120) + "…" : one;
    }
}
