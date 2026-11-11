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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Makes {@code start} run the sources on disk rather than whatever
 * {@code target/classes} was last built from.
 * <p>
 * Upstream, {@code start} runs Maven's {@code compile} only when the classpath
 * cache stamp is out of date, and that stamp covers poms alone. Sources changed
 * while no daemon was running - a {@code git checkout}, {@code pull} or
 * revert - are never compiled: the app starts on the old classes. An
 * {@code apply} afterwards catches up modified sources, but never removes the
 * class of a deleted one, so a reverted {@code @Route} view stays live.
 * <p>
 * Before a start this checks every module in the reactor: a source newer than
 * its class, or a resource newer than its copy, makes the classes stale; a
 * top-level class whose source is gone is an orphan and is deleted, with its
 * nested classes. Either way the classpath stamp is invalidated, so the start
 * runs Maven's {@code compile} again. A class from generated sources counts as
 * having a source; one of several top-level classes in one file does not, and
 * only costs that compile on every start.
 * <p>
 * Prototype extension, not part of upstream Flow.
 */
final class StaleClasses {

    private StaleClasses() {
    }

    /**
     * Checks the reactor's modules and, when they are out of date, deletes the
     * orphans and invalidates the classpath stamp.
     *
     * @return what was found, one line per module that needed it; empty when
     *         everything is current
     */
    static List<String> reconcile(Reactor reactor) {
        List<String> found = new ArrayList<>();
        for (Reactor.Module module : reactor.candidates()) {
            if (!Files.isDirectory(module.classesDir())) {
                continue;
            }
            try {
                int stale = staleSources(module);
                List<Path> orphans = orphans(module);
                for (Path orphan : orphans) {
                    deleteWithNested(orphan);
                }
                if (stale > 0 || !orphans.isEmpty()) {
                    found.add(module.name() + ": " + stale
                            + " source(s) newer than their classes, "
                            + orphans.size() + " class(es) without a source"
                            + (orphans.isEmpty() ? ""
                                    : " (deleted: " + names(module, orphans)
                                            + ")"));
                }
            } catch (IOException e) {
                found.add(module.name() + ": could not check (" + e.getMessage()
                        + ")");
            }
        }
        if (!found.isEmpty()) {
            try {
                Files.deleteIfExists(Launch.workDir(reactor.app().dir())
                        .resolve("cp.stamp"));
            } catch (IOException e) {
                found.add("could not invalidate the classpath stamp: "
                        + e.getMessage());
            }
        }
        return found;
    }

    private static int staleSources(Reactor.Module module) throws IOException {
        int stale = 0;
        for (Path source : files(module.sourceDir(), ".java")) {
            String file = source.getFileName().toString();
            // Without annotations these compile to nothing at all.
            if (file.equals("package-info.java")
                    || file.equals("module-info.java")) {
                continue;
            }
            if (newer(source, module.artifactFor(source))) {
                stale++;
            }
        }
        for (Path resource : files(module.resourceDir(), "")) {
            if (newer(resource, module.targetFor(resource))) {
                stale++;
            }
        }
        return stale;
    }

    private static List<Path> orphans(Reactor.Module module) throws IOException {
        List<Path> orphans = new ArrayList<>();
        Path generated = module.dir().resolve("target")
                .resolve("generated-sources");
        for (Path classFile : files(module.classesDir(), ".class")) {
            String binaryName = module.binaryNameOf(classFile);
            if (binaryName.contains("$") || binaryName.endsWith("package-info")
                    || binaryName.endsWith("module-info")) {
                continue;
            }
            String relative = binaryName.replace('.', '/') + ".java";
            if (Files.exists(module.sourceDir().resolve(relative))
                    || generatedSourceExists(generated, relative)) {
                continue;
            }
            orphans.add(classFile);
        }
        return orphans;
    }

    private static boolean generatedSourceExists(Path generated,
            String relative) throws IOException {
        if (!Files.isDirectory(generated)) {
            return false;
        }
        try (Stream<Path> roots = Files.list(generated)) {
            return roots.anyMatch(root -> Files.exists(root.resolve(relative)));
        }
    }

    private static void deleteWithNested(Path classFile) throws IOException {
        String name = classFile.getFileName().toString();
        String prefix = name.substring(0, name.length() - ".class".length())
                + "$";
        try (Stream<Path> siblings = Files.list(classFile.getParent())) {
            for (Path sibling : siblings.toList()) {
                if (sibling.getFileName().toString().startsWith(prefix)) {
                    Files.deleteIfExists(sibling);
                }
            }
        }
        Files.deleteIfExists(classFile);
    }

    private static boolean newer(Path source, Path artifact) throws IOException {
        if (!Files.exists(artifact)) {
            return true;
        }
        FileTime sourceTime = Files.getLastModifiedTime(source);
        return sourceTime.compareTo(Files.getLastModifiedTime(artifact)) > 0;
    }

    private static List<Path> files(Path dir, String suffix) throws IOException {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(suffix)).toList();
        }
    }

    private static String names(Reactor.Module module, List<Path> orphans) {
        return String.join(", ",
                orphans.stream().map(module::binaryNameOf).toList());
    }
}
