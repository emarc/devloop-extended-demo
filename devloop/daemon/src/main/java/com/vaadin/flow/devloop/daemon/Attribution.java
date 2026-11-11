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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Who wrote a file. A change on disk carries no author, so writers that pass
 * through the daemon - Copilot through {@link CopilotBridge}, a coding agent
 * through {@code vaadin-dev emit agent.write PATH} from its post-edit hook -
 * claim the content they wrote, and {@link SourceWatch} asks here before it
 * calls a change {@code external}.
 * <p>
 * Claimed by content hash rather than by path and time: an agent and a
 * developer can both touch a file within the same second, and only the bytes
 * say whose version is on disk now.
 * <p>
 * Prototype extension, not part of upstream Flow.
 */
final class Attribution {

    /** A claim older than this is not evidence about the current content. */
    private static final Duration WINDOW = Duration.ofMinutes(2);

    record Claim(String source, String label, String hash, Instant at) {
    }

    private static final Map<Path, Claim> CLAIMS = new ConcurrentHashMap<>();

    private Attribution() {
    }

    static void claim(Path file, String source, String label) {
        hash(file).ifPresent(h -> CLAIMS.put(file.toAbsolutePath().normalize(),
                new Claim(source, label, h, Instant.now())));
    }

    static void claim(Path file, byte[] content, String source, String label) {
        CLAIMS.put(file.toAbsolutePath().normalize(),
                new Claim(source, label, hash(content), Instant.now()));
    }

    /**
     * The claim that explains the file's current content, if any. A deleted
     * file is explained by a claim with an empty hash.
     */
    static Optional<Claim> explain(Path file) {
        Path key = file.toAbsolutePath().normalize();
        Claim claim = CLAIMS.get(key);
        if (claim == null || Duration.between(claim.at, Instant.now())
                .compareTo(WINDOW) > 0) {
            return Optional.empty();
        }
        String now = Files.exists(key) ? hash(key).orElse("?") : "";
        return now.equals(claim.hash) ? Optional.of(claim) : Optional.empty();
    }

    static Optional<String> hash(Path file) {
        try {
            return Optional.of(hash(Files.readAllBytes(file)));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    static String hash(byte[] content) {
        if (content == null) {
            return "";
        }
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
