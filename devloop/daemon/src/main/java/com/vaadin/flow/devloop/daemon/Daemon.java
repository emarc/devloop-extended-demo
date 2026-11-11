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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The dev-loop daemon: one per application module, discovered through
 * {@code .vaadin/daemon.properties}, never started by hand.
 * <p>
 * One application, which is not the same as one module: when the application
 * sits in a Maven reactor, every reactor module it depends on is in the same
 * edit loop. The application module is still the only tree the daemon owns —
 * the handshake, the logs and the caches all live under it.
 * <p>
 * P1 scope is lifecycle only - {@code status}, {@code start}, {@code stop},
 * {@code restart}, {@code shutdown}. The transaction verbs come in P2/P3, which
 * is why the wire protocol already streams progress lines before a final exit
 * code: {@code apply} will need exactly that shape.
 * <p>
 * For internal use only. May be renamed or removed in a future release.
 */
public final class Daemon {

    private static final String VERSION = "1";

    private static volatile int currentPort;
    private static volatile String currentToken;

    static int currentPort() {
        return currentPort;
    }

    static String currentToken() {
        return currentToken;
    }

    private final Path root;
    private final Launch launch;
    private final AppProcess app;
    private final Instant startedAt = Instant.now();
    private final AtomicInteger liveClients = new AtomicInteger();
    private volatile Instant lastActivity = Instant.now();
    private volatile boolean shuttingDown;

    /** Kept so the shutdown hook can stop it rather than leaving it running. */
    private volatile ScheduledExecutorService idleWatchdog;

    /** Held for as long as this daemon runs; see {@link Handshake.Claim}. */
    private volatile Handshake.Claim claim;
    private ServerSocket serverSocket;

    private final Duration idleTimeout = Duration
            .ofSeconds(Long.getLong("vaadin.dev.idleSeconds", 1800L));

    private final TransactionEngine transactions;

    /*
     * Prototype extension: the event journal and what feeds it. Started only by
     * the daemon that won the claim, in run().
     */
    private Events events;
    private LogTail logTail;
    private SourceWatch sourceWatch;
    private CopilotBridge copilot;

    /**
     * Verbs the running application contributes, each served by the
     * connection the app's extension holds open; see
     * {@link #handleExtension}.
     */
    private final java.util.Map<String, Connector> extensionVerbs = new java.util.concurrent.ConcurrentHashMap<>();

    private Daemon(Path root) {
        this.root = root;
        this.launch = new Launch(Reactor.discover(root, System.out::println),
                System.out::println);
        this.app = new AppProcess(root, launch);
        this.transactions = new TransactionEngine(launch, app);
    }

    /**
     * Starts the daemon for a project.
     *
     * @param args
     *            the project root directory as the first argument; the current
     *            working directory is used when no argument is given
     * @throws Exception
     *             if the daemon cannot be started
     */
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args.length > 0 ? args[0] : ".").toAbsolutePath()
                .normalize();
        new Daemon(root).run();
    }

    private void run() throws IOException {
        // One daemon per project, decided before anything is read or written:
        // checking the record, binding a port and writing the record back is
        // not one step, and two first invocations at once would otherwise both
        // get through it and both start an application.
        Optional<Handshake.Claim> claimed = Handshake.claim(root);
        if (claimed.isEmpty()) {
            System.out.println(Handshake.read(root)
                    .map(other -> "daemon already running on port " + other.port
                            + " (pid " + other.pid + ")")
                    .orElse("another daemon is starting for " + root));
            return;
        }
        claim = claimed.get();

        // Stale-instance takeover, decided by process liveness rather than by
        // probing a socket. A live predecessor wins and we exit quietly.
        Optional<Handshake> existing = Handshake.read(root);
        if (existing.isPresent()) {
            if (existing.get().isProcessAlive()) {
                System.out.println(
                        "daemon already running on port " + existing.get().port
                                + " (pid " + existing.get().pid + ")");
                return;
            }
            System.out.println("reaping stale daemon record (pid "
                    + existing.get().pid + " is gone)");
            Handshake.delete(root);
        }

        serverSocket = new ServerSocket(0, 32,
                InetAddress.getLoopbackAddress());
        currentPort = serverSocket.getLocalPort();
        byte[] secret = new byte[24];
        new SecureRandom().nextBytes(secret);
        currentToken = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(secret);

        long pid = ProcessHandle.current().pid();
        long start = ProcessHandle.current().info().startInstant()
                .map(Instant::toEpochMilli).orElse(System.currentTimeMillis());
        new Handshake(pid, start, currentPort, currentToken, root.toString())
                .write(root);

        Runtime.getRuntime().addShutdownHook(new Thread(this::cleanup));
        startIdleWatchdog();
        startEventSources();

        System.out.println("vaadin-dev daemon " + VERSION + " listening on "
                + currentPort + " for " + root);
        // The loop's shape, said out loud. Which modules an apply watches is
        // the
        // one thing a developer cannot infer from behaviour until an edit fails
        // to be noticed, and by then they are debugging the wrong thing.
        if (launch.reactor().isMultiModule()) {
            System.out.println("reactor " + launch.reactor().root() + " - "
                    + launch.reactor().describe());
        }

        while (!shuttingDown) {
            try {
                Socket client = serverSocket.accept();
                Thread.ofVirtual().start(() -> handle(client));
            } catch (IOException e) {
                if (!shuttingDown) {
                    System.err.println("accept failed: " + e);
                }
            }
        }        // The bridge's HTTP server runs on a non-daemon thread, which would
        // otherwise keep this JVM alive after the accept loop is done.
        if (copilot != null) {
            copilot.stop();
        }
    }

    /**
     * The journal and its feeds. Before the first launch, because the Copilot
     * bridge works by a system property every launch forwards to the app.
     */
    private void startEventSources() {
        events = Events.open(Launch.workDir(root));
        Path reactorRoot = launch.reactor().root();
        copilot = new CopilotBridge(root, reactorRoot);
        copilot.afterWrite(() -> {
            Events.emit("copilot.apply", "daemon",
                    "applying Copilot's change");
            recordApply(transactions.apply(text -> {
            }, true));
        });
        copilot.start();
        logTail = new LogTail(root);
        logTail.start();
        sourceWatch = new SourceWatch(root, reactorRoot,
                () -> launch.resolved()
                        .map(project -> project.modules().stream()
                                .map(Reactor.Module::dir).toList())
                        .orElse(List.of()));
        sourceWatch.start();
        Events.emit("daemon.started", "daemon",
                "daemon " + VERSION + " up on port " + currentPort);
    }

    /** After anything that may have resolved the loop or relaunched the app. */
    private void refreshEventSources() {
        if (sourceWatch != null) {
            sourceWatch.registerModules();
        }
        if (copilot != null) {
            copilot.ensureDotFile();
        }
    }

    private void startIdleWatchdog() {
        ScheduledExecutorService scheduler = Executors
                .newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "idle-watchdog");
                    t.setDaemon(true);
                    return t;
                });
        scheduler.scheduleAtFixedRate(() -> {
            if (shuttingDown || liveClients.get() > 0 || !app.isIdle()) {
                return;
            }
            if (Duration.between(lastActivity, Instant.now())
                    .compareTo(idleTimeout) > 0) {
                System.out.println("idle for " + idleTimeout.toSeconds()
                        + "s with no app running - shutting down");
                requestShutdown();
            }
        }, 5, 5, TimeUnit.SECONDS);
        idleWatchdog = scheduler;
    }

    private void handle(Socket client) {
        liveClients.incrementAndGet();
        try (client;
                BufferedReader in = new BufferedReader(new InputStreamReader(
                        client.getInputStream(), StandardCharsets.UTF_8));
                PrintWriter out = new PrintWriter(client.getOutputStream(),
                        true, StandardCharsets.UTF_8)) {
            String request = in.readLine();
            if (request == null) {
                return;
            }
            String[] parts = request.trim().split("\\s+");
            if (parts.length < 2 || !tokenMatches(parts[0])) {
                out.println("> unauthorized");
                out.println("EXIT 77");
                return;
            }
            lastActivity = Instant.now();
            String verb = parts[1].toLowerCase();
            List<String> rest = List.of(parts)
                    .subList(Math.min(2, parts.length), parts.length);

            if (verb.equals("register")) {
                handleRegistration(rest, in, out);
                return;
            }
            if (verb.equals("extend")) {
                handleExtension(rest, in, out);
                return;
            }
            dispatch(verb, rest, out);
        } catch (IOException e) {
            // Client vanished mid-command; nothing to report to.
        } finally {
            liveClients.decrementAndGet();
            lastActivity = Instant.now();
        }
    }

    /**
     * The in-app connector's connection. It is held open for the app's
     * lifetime, so its close is the signal that the app is gone - no polling,
     * no probing.
     */
    private void handleRegistration(List<String> args, BufferedReader in,
            PrintWriter out) throws IOException {
        String mode = args.isEmpty() ? "unknown" : args.get(0);
        OptionalLong pid = reportedPid(args);
        AppProcess.Run registration = app.onRegistered(mode, pid).orElse(null);
        if (registration == null) {
            // A connector for a process the daemon is no longer reporting on -
            // a predecessor still winding down while its replacement is up.
            // Letting it in would hand the running app's state to a corpse.
            out.println("> ignored: not the current app");
            System.out.println("ignored a registration from pid "
                    + (pid.isPresent() ? pid.getAsLong() : "unknown")
                    + ": not the current app");
            return;
        }
        Connector connector = new Connector(out);
        transactions.onConnector(connector, registration.snapshot);
        out.println("> registered");
        // Issued off-thread: the reply is only readable once the pump below is
        // running, so asking inline would wait for a line nobody is reading.
        Thread.ofVirtual()
                .start(() -> connector.command("INFO", 10)
                        .ifPresent(info -> System.out.println(
                                "app registered (mode=" + mode + ") " + info)));
        Events.emit("app.registered", "daemon",
                "app registered (mode=" + mode + ")");
        try {
            // Block until the app closes the connection or dies, feeding
            // replies
            // to whoever issued a command.
            Connector.pump(in, connector, () -> lastActivity = Instant.now());
        } finally {
            connector.close();
            transactions.onConnectorClosed(connector);
            app.onUnregistered(registration);
            System.out.println("app registration closed");
        }
    }

    /**
     * Prototype extension: an application-contributed command set.
     * <p>
     * {@code <token> extend NAME VERB[,VERB...]} from inside the app - which
     * has the token because the daemon launched it with
     * {@code -Dvaadin.devloop.token} - opens a connection held for the app's
     * lifetime, exactly like the connector's registration. From then on
     * {@code vaadin-dev VERB args...} is forwarded down it as
     * {@code CALL <base64 args, unit-separated>} and answered with one line,
     * {@code RESULT <exit> <base64 output>}.
     * <p>
     * This is the seam that lets a library in the app - a UI driver, say -
     * answer questions only the running app can answer ("what is on screen?",
     * "click that") through the same CLI and the same token as the dev loop,
     * without the daemon knowing anything about Vaadin components.
     */
    private void handleExtension(List<String> args, BufferedReader in,
            PrintWriter out) throws IOException {
        if (args.size() < 2) {
            out.println("> usage: extend NAME VERB[,VERB...]");
            out.println("EXIT 64");
            return;
        }
        String name = args.get(0);
        List<String> verbs = List.of(args.get(1).toLowerCase().split(","));
        Connector connector = new Connector(out);
        verbs.forEach(v -> extensionVerbs.put(v, connector));
        out.println("> extended " + name + ": " + String.join(",", verbs));
        System.out.println("extension " + name + " registered verbs " + verbs);
        Events.emit("ext.registered", "app",
                "extension " + name + " serves: " + String.join(", ", verbs));
        try {
            Connector.pump(in, connector, () -> lastActivity = Instant.now());
        } finally {
            connector.close();
            verbs.forEach(v -> extensionVerbs.remove(v, connector));
            System.out.println("extension " + name + " closed");
        }
    }

    private boolean forwardToExtension(String verb, List<String> args,
            Launch.Log log, PrintWriter out) {
        Connector ext = extensionVerbs.get(verb);
        if (ext == null || !ext.isOpen()) {
            return false;
        }
        // The client joins words with spaces and the daemon splits on them,
        // so quoting is lost on the way in. vdx sends "--b64 <payload>"
        // instead, which this passes through untouched.
        String payload = args.size() == 2 && args.get(0).equals("--b64")
                ? args.get(1)
                : Base64.getEncoder().encodeToString(String.join("\u001f",
                        args).getBytes(StandardCharsets.UTF_8));
        String reply = ext.command("CALL " + verb + " " + payload, 120)
                .orElse("RESULT 70 " + Base64.getEncoder().encodeToString(
                        "the app did not answer".getBytes(StandardCharsets.UTF_8)));
        String[] parts = reply.split(" ", 3);
        int exit = 70;
        String output = reply;
        if (parts.length >= 2 && parts[0].equals("RESULT")) {
            exit = Integer.parseInt(parts[1]);
            output = parts.length == 3 ? new String(
                    Base64.getDecoder().decode(parts[2]),
                    StandardCharsets.UTF_8) : "";
        }
        output.lines().forEach(log::line);
        out.println("EXIT " + exit);
        return true;
    }

    /**
     * The pid the connector reports for its own JVM, empty when it reports none
     * that can be read. Empty is a registration taken at face value, so an
     * unparseable pid can never lock the app out of its own daemon.
     */
    private static OptionalLong reportedPid(List<String> args) {
        if (args.size() < 2) {
            return OptionalLong.empty();
        }
        try {
            return OptionalLong.of(Long.parseLong(args.get(1)));
        } catch (NumberFormatException e) {
            return OptionalLong.empty();
        }
    }

    private void dispatch(String verb, List<String> args, PrintWriter out) {
        Launch.Log log = text -> {
            out.println("> " + text);
            out.flush();
        };
        try {
            switch (verb) {
            case "ping" -> {
                log.line("pong " + VERSION + " pid "
                        + ProcessHandle.current().pid());
                out.println("EXIT 0");
            }
            case "status" -> {
                boolean json = args.contains("--json");
                (json ? statusJson() : statusText()).forEach(log::line);
                out.println("EXIT 0");
            }
            case "redefine" -> {
                // Diagnostic: pushes named classes at the running app and
                // returns
                // the connector's reply verbatim, with none of apply's
                // escalation
                // policy applied. This is what the measurement harnesses need -
                // raw JVM behaviour - and it keeps them on the one transport
                // instead of a second socket of their own.
                if (args.isEmpty()) {
                    log.line("usage: redefine <binary.Class,...>");
                    out.println("EXIT 64");
                } else {
                    Connector connector = transactions.connector();
                    if (connector == null || !connector.isOpen()) {
                        log.line("no app registered");
                        out.println("EXIT 1");
                    } else {
                        String reply = connector
                                .command("REDEFINE " + args.get(0), 60)
                                .orElse("ERR kind=no-reply");
                        log.line(reply);
                        out.println("EXIT " + (reply.startsWith("OK") ? 0 : 1));
                    }
                }
            }
            case "apply" -> {
                boolean json = args.contains("--json");
                // With --json the response must be parseable, so progress lines
                // are dropped rather than interleaved with the object.
                Launch.Log progress = json ? text -> {
                } : log;
                TransactionEngine.Transaction tx = transactions.apply(progress,
                        !args.contains("--no-restart"));
                if (json) {
                    log.line(tx.json());
                } else {
                    transactions.render(tx).forEach(log::line);
                }
                recordApply(tx);
                out.println("EXIT " + tx.outcome.exitCode);
            }
            case "start" -> {
                reconcileStaleClasses(log);
                refreshEventSources();
                AppProcess.Startup startup = app.start(log, "start",
                        parseJvmArgsOption(args).orElse(null));
                startup.lines().forEach(log::line);
                recordStartup("start", startup);
                out.println("EXIT " + (startup.ok() ? 0 : 1));
            }
            case "stop" -> {
                String stopped = app.stop();
                log.line(stopped);
                Events.emit("app.stopped", "daemon", stopped);
                out.println("EXIT 0");
            }
            case "restart" -> {
                app.stop();
                reconcileStaleClasses(log);
                refreshEventSources();
                AppProcess.Startup startup = app.start(log, "restart",
                        parseJvmArgsOption(args).orElse(null));
                startup.lines().forEach(log::line);
                recordStartup("restart", startup);
                out.println("EXIT " + (startup.ok() ? 0 : 1));
            }
            case "events" -> eventsVerb(args, log, out);
            case "watch" -> watchVerb(args, log, out);
            case "emit" -> emitVerb(args, log, out);
            case "trigger" -> triggerVerb(args, log, out);
            case "shutdown" -> {
                log.line("daemon shutting down");
                if (!app.isIdle()) {
                    log.line("stopping app it owns");
                    app.stop();
                }
                out.println("EXIT 0");
                out.flush();
                requestShutdown();
            }
            default -> {
                if (!forwardToExtension(verb, args, log, out)) {
                    log.line("unknown command: " + verb
                            + (extensionVerbs.isEmpty() ? ""
                                    : " (the app serves: " + String.join(", ",
                                            new java.util.TreeSet<>(
                                                    extensionVerbs.keySet()))
                                            + ")"));
                    out.println("EXIT 64");
                }
            }
            }
        } catch (Exception e) {
            log.line("error: " + e);
            out.println("EXIT 70");
        }
    }

    /**
     * The JVM flags a {@code start} or {@code restart} request carries.
     * <p>
     * The request line is split on whitespace, so the CLI splits
     * {@code --jvm-args} on whitespace itself and sends a bare
     * {@code --jvm-args} followed by one {@code --jvm-arg=<flag>} word per
     * flag, in order. A flag arrives exactly as given, {@code |} and all,
     * because nothing is joined that would have to be split again. The bare
     * word is what says the flags were given: with no {@code --jvm-arg=} after
     * it, it clears them.
     *
     * @param args
     *            the request's words after the verb
     * @return the flags, empty when the request has no {@code --jvm-args} -
     *         which is not the same as an empty list, the answer that clears
     *         them
     */
    static Optional<List<String>> parseJvmArgsOption(List<String> args) {
        if (!args.contains("--jvm-args")) {
            return Optional.empty();
        }
        String prefix = "--jvm-arg=";
        return Optional.of(args.stream().filter(arg -> arg.startsWith(prefix))
                .map(arg -> arg.substring(prefix.length())).toList());
    }

    // ---------------------------------------------------------------------
    // Prototype extension: event verbs.
    // ---------------------------------------------------------------------

    /**
     * Sources changed while no daemon was running would otherwise not reach a
     * start at all; see {@link StaleClasses}.
     */
    private void reconcileStaleClasses(Launch.Log log) {
        if (!app.isIdle()) {
            return;
        }
        List<String> found = StaleClasses.reconcile(launch.reactor());
        if (found.isEmpty()) {
            return;
        }
        found.forEach(line -> log.line("classes out of date - " + line));
        Events.emit("classes.stale", "daemon",
                "sources changed since the last build; recompiling: "
                        + String.join("; ", found));
    }

    private void recordStartup(String kind, AppProcess.Startup startup) {
        Events.emit(startup.ok() ? "app.started" : "app.start-failed",
                "daemon", kind + ": " + startup.message(), List.of(),
                startup.ok() ? java.util.Map.of()
                        : java.util.Map.of("detail",
                                String.join(" | ", startup.detail())));
        refreshEventSources();
    }

    private void recordApply(TransactionEngine.Transaction tx) {
        java.util.Map<String, String> data = new java.util.LinkedHashMap<>();
        data.put("tx", "tx#" + tx.id);
        data.put("classification", tx.classification);
        if (!tx.reason.isEmpty()) {
            data.put("reason", tx.reason);
        }
        if (!tx.nextAction.isEmpty()) {
            data.put("nextAction", tx.nextAction);
        }
        if (!tx.diagnostics.isEmpty()) {
            data.put("diagnostics", tx.diagnostics.stream()
                    .map(Compile.Message::terse).limit(5)
                    .collect(java.util.stream.Collectors.joining(" | ")));
        }
        if (!tx.logErrors.isEmpty()) {
            data.put("logError", tx.logErrors.get(0)
                    .replace(AppLog.SEGMENT, " | "));
        }
        Events.emit("apply." + tx.outcome.name().toLowerCase(), "daemon",
                tx.summary(), tx.changeSet, data);
        refreshEventSources();
    }

    /** {@code --name value} out of a verb's arguments. */
    private static Optional<String> opt(List<String> args, String name) {
        int at = args.indexOf(name);
        return at >= 0 && at + 1 < args.size() ? Optional.of(args.get(at + 1))
                : Optional.empty();
    }

    /**
     * The filter every reader shares: {@code --type} prefix, {@code --source},
     * {@code --not-source} (repeatable as a comma list) and {@code --match}, a
     * regex over the summary and the file names.
     */
    private static java.util.function.Predicate<Events.Event> filter(
            List<String> args) {
        String type = opt(args, "--type").orElse(null);
        List<String> sources = opt(args, "--source")
                .map(v -> List.of(v.split(","))).orElse(List.of());
        List<String> notSources = opt(args, "--not-source")
                .map(v -> List.of(v.split(","))).orElse(List.of());
        java.util.regex.Pattern match = opt(args, "--match")
                .map(java.util.regex.Pattern::compile).orElse(null);
        return e -> (type == null || List.of(type.split(",")).stream()
                .anyMatch(e::matches))
                && (sources.isEmpty() || sources.contains(e.source()))
                && !notSources.contains(e.source())
                && (match == null || match.matcher(e.summary()).find()
                        || e.files().stream()
                                .anyMatch(f -> match.matcher(f).find()));
    }

    /**
     * {@code events [--since N | --cursor NAME [--peek]] [--limit N] [--json]}
     * plus the shared filter. With a cursor, prints what that consumer has not
     * seen and moves its cursor past it - unless {@code --peek}.
     */
    private void eventsVerb(List<String> args, Launch.Log log, PrintWriter out) {
        Optional<String> cursor = opt(args, "--cursor");
        long since = cursor.map(events::cursor).orElseGet(() -> opt(args,
                "--since").map(Long::parseLong).orElse(0L));
        int limit = opt(args, "--limit").map(Integer::parseInt).orElse(50);
        List<Events.Event> found = events.since(since, filter(args));
        boolean truncated = found.size() > limit;
        if (truncated) {
            found = found.subList(found.size() - limit, found.size());
        }
        long upTo = events.lastSeq();
        // --set N: move the cursor to N and print nothing - "reviewed up to
        // here", which must not swallow what arrived during the review.
        Optional<String> set = opt(args, "--set");
        if (cursor.isPresent() && set.isPresent()) {
            long to = set.get().equals("end") ? upTo : Long.parseLong(set.get());
            events.moveCursor(cursor.get(), Math.min(to, upTo));
            log.line("cursor " + cursor.get() + " at #" + Math.min(to, upTo));
            out.println("EXIT 0");
            return;
        }
        if (cursor.isPresent() && !args.contains("--peek")) {
            events.moveCursor(cursor.get(), upTo);
        }
        if (args.contains("--json")) {
            log.line("{\"since\":" + since + ",\"upTo\":" + upTo
                    + ",\"truncated\":" + truncated + ",\"events\":"
                    + Json.array(found.stream().map(Events.Event::json)
                            .toList())
                    + "}");
        } else if (found.isEmpty()) {
            log.line("no events after #" + since + " (last #" + upTo + ")");
        } else {
            if (truncated) {
                log.line("… older events omitted, showing the last " + limit);
            }
            found.forEach(e -> e.text().lines().forEach(log::line));
        }
        out.println("EXIT 0");
    }

    /**
     * {@code watch [filter] [--log] [--once | --settle S] [--timeout S] [--since N]
     * [--json]}: streams events - or with {@code --log}, raw {@code app.log}
     * lines matching {@code --match} - until the client goes away, the first
     * match with {@code --once} (exit 0), or the timeout (exit 1).
     * {@code --since} replays what was recorded after N first, so a caller can
     * start an action, then watch for its outcome without a race.
     */
    private void watchVerb(List<String> args, Launch.Log log, PrintWriter out) {
        boolean once = args.contains("--once");
        // --settle S: exit once matches have stopped for S seconds - "the
        // developer has finished this burst of Copilot edits", which is when
        // reviewing them is worth an interruption and not before.
        long settleNanos = opt(args, "--settle")
                .map(v -> Long.parseLong(v) * 1_000_000_000L).orElse(0L);
        long settleBy = Long.MAX_VALUE;
        boolean json = args.contains("--json");
        long deadline = opt(args, "--timeout")
                .map(t -> System.nanoTime() + Long.parseLong(t) * 1_000_000_000L)
                .orElse(Long.MAX_VALUE);
        java.util.concurrent.BlockingQueue<String> lines = new java.util.concurrent.LinkedBlockingQueue<>();
        java.util.function.Consumer<String> lineListener = null;
        java.util.concurrent.BlockingQueue<Events.Event> queue = null;
        java.util.function.Predicate<Events.Event> filter = filter(args);
        if (args.contains("--log")) {
            java.util.regex.Pattern match = opt(args, "--match")
                    .map(java.util.regex.Pattern::compile).orElse(null);
            lineListener = line -> {
                if (match == null || match.matcher(line).find()) {
                    lines.offer(line);
                }
            };
            logTail.addLineListener(lineListener);
        } else {
            queue = events.subscribe();
            long since = opt(args, "--since").map(Long::parseLong).orElse(-1L);
            if (since >= 0) {
                for (Events.Event e : events.since(since, filter)) {
                    queue.offer(e);
                }
            }
        }
        int exit = 0;
        try {
            long lastBeat = System.nanoTime();
            while (true) {
                long now = System.nanoTime();
                if (now >= settleBy) {
                    break;
                }
                if (now >= deadline) {
                    log.line("watch: timed out");
                    exit = 1;
                    break;
                }
                long wait = Math.max(1, Math.min(1000,
                        (Math.min(deadline, settleBy) - now) / 1_000_000));
                String emitted = null;
                if (queue != null) {
                    Events.Event e = queue.poll(wait,
                            java.util.concurrent.TimeUnit.MILLISECONDS);
                    if (e != null && filter.test(e)) {
                        emitted = json ? e.json() : e.text();
                    }
                } else {
                    emitted = lines.poll(wait,
                            java.util.concurrent.TimeUnit.MILLISECONDS);
                }
                if (emitted != null) {
                    emitted.lines().forEach(log::line);
                    if (settleNanos > 0) {
                        settleBy = System.nanoTime() + settleNanos;
                    } else if (once) {
                        break;
                    }
                }
                // An empty line is dropped by the client; writing it is how a
                // vanished client is noticed.
                if (System.nanoTime() - lastBeat > 2_000_000_000L) {
                    out.println();
                    lastBeat = System.nanoTime();
                }
                if (out.checkError()) {
                    return;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            if (queue != null) {
                events.unsubscribe(queue);
            }
            if (lineListener != null) {
                logTail.removeLineListener(lineListener);
            }
        }
        out.println("EXIT " + exit);
    }

    /**
     * {@code emit TYPE [key=value...] [-- summary...]} records an event from
     * outside - an agent's note, a CI step. {@code emit claim SOURCE PATH...
     * [-- label...]} instead claims files for {@link Attribution}, which is how
     * an agent's post-edit hook says "that write was mine" so the file watcher
     * does not report it as somebody else's.
     */
    private void emitVerb(List<String> args, Launch.Log log, PrintWriter out) {
        if (args.isEmpty()) {
            log.line("usage: emit TYPE [key=value...] [-- summary]"
                    + " | emit claim SOURCE PATH... [-- label]");
            out.println("EXIT 64");
            return;
        }
        int dash = args.indexOf("--");
        List<String> head = dash >= 0 ? args.subList(0, dash) : args;
        String tail = dash >= 0
                ? String.join(" ", args.subList(dash + 1, args.size()))
                : "";
        if (head.get(0).equals("claim")) {
            if (head.size() < 3) {
                log.line("usage: emit claim SOURCE PATH... [-- label]");
                out.println("EXIT 64");
                return;
            }
            Path base = launch.reactor().root();
            for (String file : head.subList(2, head.size())) {
                Attribution.claim(base.resolve(file).normalize(), head.get(1),
                        tail);
            }
            log.line("claimed " + (head.size() - 2) + " file(s) for "
                    + head.get(1));
            out.println("EXIT 0");
            return;
        }
        java.util.Map<String, String> data = new java.util.LinkedHashMap<>();
        for (String kv : head.subList(1, head.size())) {
            int eq = kv.indexOf('=');
            if (eq > 0) {
                data.put(kv.substring(0, eq), kv.substring(eq + 1));
            }
        }
        String source = data.getOrDefault("source", "external");
        data.remove("source");
        Events.Event e = events.record(head.get(0), source, tail, List.of(),
                data);
        log.line("recorded #" + e.seq());
        out.println("EXIT 0");
    }

    /** {@code trigger [list] | add NAME REGEX | rm NAME}. */
    private void triggerVerb(List<String> args, Launch.Log log, PrintWriter out) {
        String sub = args.isEmpty() ? "list" : args.get(0);
        switch (sub) {
        case "add" -> {
            if (args.size() < 3) {
                log.line("usage: trigger add NAME REGEX");
                out.println("EXIT 64");
                return;
            }
            log.line(logTail.addTrigger(args.get(1),
                    String.join(" ", args.subList(2, args.size()))));
        }
        case "rm" -> log.line(args.size() > 1 && logTail.removeTrigger(args.get(1))
                ? "removed " + args.get(1)
                : "no such trigger");
        default -> {
            if (logTail.triggers().isEmpty()) {
                log.line("no triggers (add one: trigger add NAME REGEX)");
            }
            logTail.triggers().forEach((name, p) -> log
                    .line(name + " = " + p.pattern()));
        }
        }
        out.println("EXIT 0");
    }

    private List<String> statusText() {
        StringBuilder sb = new StringBuilder();
        sb.append(app.state().name().toLowerCase());
        app.pid().ifPresent(pid -> sb.append("  pid=").append(pid));
        // Only a crash reports an exit code. After a deliberate stop the code
        // is
        // an artifact of how we terminated it, and "exit=1" reads as a failure.
        if (app.state() == AppProcess.State.CRASHED) {
            app.exitCode().ifPresent(code -> sb.append("  exit=").append(code));
        }
        if (app.state() == AppProcess.State.RUNNING) {
            sb.append("  owner=daemon  registered=").append(app.isRegistered());
        }
        runtimeName().ifPresent(name -> sb.append("  runtime=").append(name));
        sb.append("  compiler=").append(compilerName());
        List<String> lines = new java.util.ArrayList<>();
        lines.add(sb.toString());
        modulesLine().ifPresent(lines::add);
        launch.resolutionError()
                .ifPresent(reason -> lines.add("classpath: " + reason));
        app.failureReason().ifPresent(reason -> lines.add(reason));
        frontendStatus().ifPresent(f -> lines.add("frontend " + f));
        // Errors the app has logged since the last apply. This is where a
        // failure
        // that only shows up when someone uses the app surfaces: an apply
        // cannot
        // wait for the next page render, but the question "is my change
        // alright?"
        // is asked again here, and by then the app has answered it.
        List<String> logErrors = appLogErrors();
        if (!logErrors.isEmpty()) {
            lines.add("app log: " + logErrors.size() + " error(s)"
                    + transactions.lastTransaction()
                            .map(tx -> " since tx#" + tx.id)
                            .orElse(" since the app started"));
            lines.add("  " + logErrors.get(0).strip());
        }
        if (!extensionVerbs.isEmpty()) {
            lines.add("app commands: " + String.join(", ",
                    new java.util.TreeSet<>(extensionVerbs.keySet())));
        }
        if (events != null) {
            lines.add("events: last #" + events.lastSeq()
                    + " (vaadin-dev events --since N)");
        }
        transactions.current().ifPresent(
                tx -> lines.add("tx#" + tx.id + " in flight: " + tx.state));
        transactions.lastTransaction()
                .ifPresent(tx -> lines.add("last " + tx.summary()));
        lines.add("daemon pid=" + ProcessHandle.current().pid() + " port="
                + currentPort + " up="
                + Duration.between(startedAt, Instant.now()).toSeconds() + "s");
        return lines;
    }

    /**
     * The edit loop as far as it is known, and what is deliberately outside it.
     * <p>
     * Read from what has already been resolved, never by resolving:
     * {@code status} answers in milliseconds and must not start a Maven build
     * to do it. A single-module project says nothing, because there is nothing
     * to say.
     */
    private Optional<String> modulesLine() {
        if (!launch.reactor().isMultiModule()) {
            return Optional.empty();
        }
        Optional<Launch.Project> resolved = launch.resolved();
        if (resolved.isEmpty()) {
            return Optional.of("reactor " + launch.reactor().describe()
                    + "; the edit loop is resolved on the first apply or start");
        }
        List<String> loop = moduleNames(resolved.get().modules());
        List<String> excluded = excludedModules(loop);
        return Optional.of("modules " + String.join(", ", loop)
                + (excluded.isEmpty() ? ""
                        : "  (outside the loop: " + String.join(", ", excluded)
                                + " - the app does not depend on "
                                + (excluded.size() == 1 ? "it" : "them")
                                + ")"));
    }

    private List<String> moduleNames(List<Reactor.Module> modules) {
        return modules.stream().map(Reactor.Module::name).toList();
    }

    private List<String> excludedModules(List<String> loop) {
        return launch.reactor().candidates().stream().map(Reactor.Module::name)
                .filter(name -> !loop.contains(name)).distinct().toList();
    }

    /**
     * What the app has logged as an error since the last apply marked the log.
     * Empty before the first launch, and after an app that has been quiet.
     */
    private List<String> appLogErrors() {
        return app.watch().map(AppLog.Watch::errors).orElse(List.of());
    }

    /**
     * Asks the app for dev-server liveness; empty when no app is registered.
     */
    private Optional<String> frontendStatus() {
        Connector active = transactions.connector();
        if (active == null || !active.isOpen()) {
            return Optional.empty();
        }
        return active.commandIfIdle("FRONTEND", 5).map(reply -> Connector
                .fields(reply).getOrDefault("frontend", "unknown"));
    }

    private List<String> statusJson() {
        String json = "{\"app\":{\"state\":\""
                + app.state().name().toLowerCase() + "\",\"pid\":"
                + app.pid().map(String::valueOf).orElse("null")
                + ",\"exitCode\":"
                + app.exitCode().map(String::valueOf).orElse("null")
                // The reason belongs in the machine-readable answer too: an
                // agent
                // reading only JSON should not have to open app.log to learn
                // that
                // the port was taken.
                + ",\"failureReason\":"
                + app.failureReason()
                        .map(reason -> "\"" + Json.escape(reason) + "\"")
                        .orElse("null")
                + ",\"registered\":" + app.isRegistered()
                + ",\"owner\":\"daemon\"" + ",\"mode\":\"" + app.mode()
                + "\",\"frontend\":\""
                + Json.escape(frontendStatus().orElse("unknown"))
                + "\",\"logErrors\":" + Json.strings(appLogErrors())
                + "},\"modules\":" + modulesJson() + ",\"daemon\":{\"pid\":"
                + ProcessHandle.current().pid() + ",\"port\":" + currentPort
                + ",\"version\":\"" + VERSION + "\",\"compiler\":\""
                + compilerName() + "\",\"uptimeSeconds\":"
                + Duration.between(startedAt, Instant.now()).toSeconds()
                + "},\"transaction\":{\"inFlight\":"
                + transactions.current()
                        .map(tx -> "\"tx#" + tx.id + ":" + tx.state + "\"")
                        .orElse("null")
                + ",\"last\":"
                + transactions.lastTransaction()
                        .map(TransactionEngine.Transaction::json).orElse("null")
                + "}}";
        return List.of(json);
    }

    /**
     * Which compiler an apply compiles with, for {@code status}: the opt-in to
     * Maven changes what an apply costs and what it can compile, so it is worth
     * seeing without reading the daemon's command line. An unrecognised value
     * is warned about where the compile leg is built, not on every status.
     */
    private static String compilerName() {
        return Compile.Compiler.configured(text -> {
        }).label();
    }

    /**
     * How this project's application is started, for {@code status}.
     * <p>
     * Worth a word because it is a decision the daemon made about the
     * developer's project rather than something they configured: a WAR run
     * through its own build plugin and a Spring Boot jar launched directly look
     * identical from outside, and when the daemon has guessed wrong this line
     * is the only place that says so. Empty when the project looks like neither
     * shape - {@code start} is where that is worth a full explanation, not
     * here.
     */
    @SuppressWarnings("java:S106")
    private Optional<String> runtimeName() {
        try {
            return Optional.of(launch.runtime().name());
        } catch (IOException e) {
            // Into daemon.log, which is where this daemon logs; start is
            // where the same question gets a full answer.
            System.out.println("runtime undecided: " + e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * The loop in machine-readable form. {@code loop} is empty until something
     * has resolved it, which is itself the answer to "why did my edit not
     * count?".
     */
    private String modulesJson() {
        List<String> loop = launch.resolved()
                .map(project -> moduleNames(project.modules()))
                .orElse(List.of());
        // Nothing resolved means nothing is known to be excluded either; saying
        // every module is out would be a claim the daemon has not made.
        List<String> excluded = loop.isEmpty() ? List.of()
                : excludedModules(loop);
        return "{\"reactorRoot\":\""
                + Json.escape(launch.reactor().root().toString())
                + "\",\"loop\":" + Json.strings(loop) + ",\"excluded\":"
                + Json.strings(excluded) + "}";
    }

    private boolean tokenMatches(String candidate) {
        return java.security.MessageDigest.isEqual(
                candidate.getBytes(StandardCharsets.UTF_8),
                currentToken.getBytes(StandardCharsets.UTF_8));
    }

    private void requestShutdown() {
        shuttingDown = true;
        try {
            serverSocket.close();
        } catch (IOException ignored) {
            // Closing is what unblocks accept(); failure here is not
            // actionable.
        }
    }

    private void cleanup() {
        ScheduledExecutorService watchdog = idleWatchdog;
        if (watchdog != null) {
            watchdog.shutdownNow();
        }
        // Shutting down the daemon stops the app it owns, per the ownership
        // model.
        if (!app.isIdle()) {
            app.stop();
        }
        Handshake.read(root).filter(h -> h.pid == ProcessHandle.current().pid())
                .ifPresent(h -> Handshake.delete(root));
        // Last: the claim is what keeps the next invocation from starting while
        // this one is still stopping the application it owns.
        Handshake.Claim held = claim;
        if (held != null) {
            held.close();
        }
    }
}
