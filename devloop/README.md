# devloop: "the dev loop tells the agent what happened"

Vaadin's Dev Loop daemon (`vaadin-dev`) answers *"what is the state of my last change?"*. A
coding agent working next to a developer also needs *"what happened since I last looked?"*:
- Copilot rewrote a view.
- The developer edited a file in the IDE.
- A click in the browser threw an exception.
- The app crashed.

This directory prototypes that on top of the real Dev Loop.

- **`daemon/`** is the Dev Loop daemon from [vaadin/flow](https://github.com/vaadin/flow),
  module `flow-devloop-daemon` (Apache 2.0), tag **`25.4.0-alpha5`**, with an event journal
  added. The additions were first written on `25.3.0` and rebased; see [Files](#files).
- **`build.sh`** builds `target/flow-devloop-daemon-x.jar` with `javac` and `jar`. The daemon
  is JDK-only; its Maven build extension (`mavenext/`, upstream since 25.4) compiles against
  the jars of a local Maven installation, which `./mvnw` provides.
- **`vdx`** is the stock `.vaadin/vaadin-dev` client, pointed at the extended jar through the
  supported `-Dvaadin.dev.daemonJar=` override. It rebuilds the jar when a source is newer.
  The client itself is unchanged, except for a marked block at its top that hands every
  command to `vdx`.
- **`../.claude/hooks/devloop_events.py`** feeds the journal into Claude Code, and
  **`../.claude/skills/review-dev-changes/`** says what Claude does with it.

## How it fits together

```
Copilot in the browser ──plugin protocol──▶ CopilotBridge ─────────┐  (writes, undo/redo,
                                                                   │   auto-apply after 500 ms)
Copilot annotations ──vaadin-copilot-comments.json──▶ Annotations ─┤
IDE / git / Claude ──src/main/**──▶ SourceWatch + Attribution ─────┤  (copilot / agent / external)
running app ──app.log──▶ LogTail (app.error, triggers) ────────────┤
apply / start / crash ──▶ Daemon ──────────────────────────────────┤
                                                                   ▼
                                     Events: target/devloop/events.jsonl
                                                                   │ events --cursor claude|review
Claude Code ◀──briefing at the next prompt── devloop_events.py ◀───┘
  Edit/Write ──▶ emit claim agent <file>      Bash ──▶ agent.bash start/end marks
```

## Try it

```bash
.vaadin/vaadin-dev start                 # hands off to devloop/vdx: the extended daemon
devloop/vdx events                       # what happened (journal: target/devloop/events.jsonl)
devloop/vdx watch --type copilot.write --once --timeout 600   # block until Copilot writes
devloop/vdx watch --log --match 'Exception'                   # live app.log lines
devloop/vdx trigger add login 'Authentication (success|failure)'  # journal matching log lines
devloop/vdx events --cursor review --set 42                   # "reviewed up to #42"
```

Everything the stock CLI does (`apply`, `status`, …) still works, through `vdx` or the stock
script, against the same daemon.

## What it adds

| Command | What it does |
|---|---|
| `events --cursor NAME --set N\|end` | Moves a consumer's cursor without reading; the agent's "reviewed up to here". |
| `events [--since N \| --cursor NAME [--peek]] [--type P,…] [--source S] [--not-source S] [--match RE] [--limit N] [--json]` | Reads the journal. A named cursor shows each consumer each event once. |
| `watch [same filters] [--since N] [--once \| --settle S] [--timeout S] [--json]` | Streams events. Use `--once` to block until something specific happens. `--since` replays first, so "start an action, then watch for its result" has no race. |
| `watch --log [--match RE]` | Streams raw `app.log` lines. |
| `trigger add NAME RE \| rm NAME \| list` | Turns matching log lines into `trigger.NAME` journal events. Kept in `.vaadin/triggers.properties`, so it survives `mvn clean` and can be committed. |
| `emit TYPE [k=v…] [-- text]` | Records an outside event (an agent's note, a CI step). |
| `emit claim SOURCE PATH… [-- label]` | Claims a write: "these bytes are mine". |
| *(app-contributed verbs)* | The extension seam, below. Nothing uses it in this project yet. |

The events the journal records, each with `seq`, `at`, `type`, `source`, `summary`, `files` and
`data`:

| Type | Source | Comes from |
|---|---|---|
| `app.started` / `app.start-failed` / `app.stopped` / `app.crashed` / `app.registered` | daemon / app | Lifecycle. Crashes include the cause the daemon already extracts. |
| `apply.stable` / `apply.failed` / `apply.compiled` / … | daemon | Every transaction, with its change-set, diagnostics and next action. |
| `app.error` | app | `LogTail` follows `app.log` continuously. It folds one ERROR report and its stack into one event with `rootCause` and `firstAppFrame`. These are the errors nobody was waiting for, like a click ten minutes after the last apply. |
| `files.changed` | `copilot` / `agent` / `external` | `SourceWatch` watches the `src/main` trees of every module in the loop, debounces bursts, and attributes each file. |
| `copilot.write` / `copilot.undo` / `copilot.redo` | copilot | `CopilotBridge`, see below. Each write carries Copilot's own label (e.g. "Vaadin Copilot Apply UI annotations"), a line delta, and before/after snapshots under `target/devloop/copilot/`. |
| `copilot.annotation.added` / `.edited` / `.resolved` / `.removed` | copilot | One per annotation in `vaadin-copilot-comments.json`, with its text, view and selected components (`Annotations`). |
| `copilot.apply` | daemon | The bridge applying a Copilot write 500 ms after it lands, as an IDE plugin's hot-swap would. Without it, Copilot reports success and nothing goes live. |
| `trigger.NAME` | app | A registered log pattern matched. |
| `classes.stale` | daemon | `start` found `target/classes` older than the sources, or classes whose source is gone (changed while no daemon ran: a `git checkout`, a revert). Orphans are deleted and the start recompiles; see `StaleClasses`. |
| `agent.bash` | agent | Start/end marks around Claude's Bash calls, for attribution (below). |

### How a change gets its author

Nothing on disk records who wrote a file, so writers that pass through the daemon claim the
**content hash** they wrote (`Attribution`):
- Copilot is claimed by the bridge.
- Claude's Edit/Write calls are claimed by its PostToolUse hook.
- Anything unclaimed is `external`: the developer's editor, git, a formatter.

Hashes beat timestamps here: the agent and the developer can touch the same file in the same
second, and only the bytes say whose version is on disk. Two cases Claude's hook can't claim
directly:
- **Bash-tool edits:** the hook brackets each Bash call with `agent.bash` start/end marks.
  External changes inside a window (plus 2 s of watcher settle time) count as Claude's own.
  The flip side: a developer edit made *while* a Claude Bash command runs is counted as
  Claude's.
- **The Write tool's atomic `X.tmp.<pid>.<hash>` temp files:** ignored.

### How Copilot's writes are seen

Copilot writes source from inside the app JVM and logs nothing about it. But when an IDE plugin
is present, Copilot doesn't write the file itself. It POSTs
`{command, projectBasePath, data:{file, undoLabel, content}}` to the plugin's endpoint, so the
edit lands in the IDE's undo history. Copilot finds the plugin through a `.copilot-plugin`
properties file:
- `-Dvaadin.copilot.pluginDotFilePath`, **only** when Copilot detected that an IDE launched the
  app, which a daemon-launched app never is;
- otherwise the first of `.idea/`, `.vscode/`, `.eclipse/` + `.copilot-plugin` in the app
  module.

The bridge serves that protocol on a loopback port and publishes its dot file in the `.eclipse/`
slot (gitignored, deleted on shutdown). It:
- writes the file;
- keeps a per-file undo/redo stack that Copilot's own undo button drives;
- records the event;
- claims the content.

When a real IDE plugin's dot file is found, the bridge forwards the request to it instead, so
IDE undo keeps working. Copilot reports
`idePluginState = {active: true, ide: "devloop", supportedActions: [write, writeBase64, undo, redo, refresh]}`.

### Who applies what

- Copilot writes: applied by the bridge, 500 ms after the write.
- Claude's edits: Claude runs `.vaadin/vaadin-dev apply` itself (the `vaadin-devloop` skill).
- IDE edits: journaled, **not** applied. That's deliberate: editors save half-finished code.
- Theme CSS: pushed by Vaadin itself.

### The Claude Code side (`.claude/hooks/devloop_events.py`)

The hooks *collect, then brief at the next prompt*; nothing interrupts the developer.

| Hook | Effect |
|---|---|
| `UserPromptSubmit` | Briefing: new problems in the app (`app.error`, crashes, failed applies); developer/Copilot changes not yet reviewed, per file (since the `review` cursor, and only files that still differ from git); pending UI annotations; whether open review notes exist (`.vaadin/review-notes.md`). |
| `PreToolUse(Bash)` | Marks the start of a Bash call (`agent.bash`). |
| `PostToolUse` | Edit/Write: claims the file as Claude's. Bash: marks the end. Any tool: one line if the developer changed something mid-turn. |

Two cursors live in the daemon: `claude` (what Claude has been told about; moved by every
briefing) and `review` (developer changes Claude has reviewed; moved only by
`vdx events --cursor review --set N`). The hooks do nothing unless the daemon is already
running: any `vaadin-dev` command would spawn one. Under a stock daemon `events` is an unknown
command, so they stay silent.

### The extension seam

The app can contribute verbs. It opens a second connection, `<token> extend NAME VERB[,…]`,
using the token the daemon passes it in `-Dvaadin.devloop.token`. The daemon then forwards
`vaadin-dev VERB …` down that connection and prints the answer. `vdx` base64-encodes the
arguments of app verbs, so quoting survives the client's join-on-space. Nothing uses it in this
project yet; it's how UI driving (`vdx ui inspect|apply|navigate|impersonate`) and
Observability Kit findings (`vdx o11y`) from the original prototype come back.

## Known issues and rough edges

- **Copilot is reached by impersonating its IDE plugin.** A real IntelliJ or VS Code plugin
  dot file in `.idea/` / `.vscode/` of the app module wins, and Copilot writes then show as
  `external` and aren't auto-applied. Untested with a real IntelliJ. The ask upstream: Copilot
  reports writes to the daemon directly.
- **Resolving annotations by editing the file** may be overwritten by Copilot's panel if it is
  open, because it saves its whole in-memory list. Not verified.
- **Copilot's annotation "Add" needs a secure context** (`crypto.randomUUID`): fine on
  `localhost`, broken on plain-HTTP remote origins. A Copilot bug worth reporting.
- **Bash-tool attribution is by time window**, see above.
- **`firstAppFrame` in `app.error`** is a library-prefix heuristic; the daemon could use the
  reactor's own packages instead.
- **Hooks load only from the directory Claude starts in.** Start Claude in the repository
  root.
- **No Windows support for `vdx`.** The hand-off block is only in the bash `vaadin-dev`;
  `vaadin-dev.cmd`/`.ps1` still spawn the stock daemon.
- **Copilot's "hotswap is not enabled" warning.** Copilot decides from the app's JVM arguments
  (`JdkInfo`): a `-javaagent` path ending in exactly `hotswap-agent.jar`, and `jdwp`. Upstream
  caches the agent as `hotswap-agent-<version>.jar` and runs without a debugger agent, so
  Copilot warned on every Java edit. Here the jar is cached as `<version>/hotswap-agent.jar`
  and the app runs with `-agentlib:jdwp=…,suspend=n,address=127.0.0.1:0`: a debugger can
  attach on the port printed in `app.log`. `-Dvaadin.dev.jdwp=false` turns that off.
- **Upstream `start` runs stale classes** when sources changed while no daemon was running:
  it recompiles only when a pom changed, and `apply` never removes the class of a deleted
  source (a reverted `@Route` view stays live). `StaleClasses` fixes this here, before each
  `start`. Its orphan check is name-based: a second top-level class in one file looks like an
  orphan and costs a recompile on every start.
- **`-XX:HotswapAgent` in `JAVA_TOOL_OPTIONS`** (some JetBrains Runtime setups set it for every
  JVM) crashes the `jar` tool and makes the app load HotswapAgent twice (`ClassFormatError:
  Duplicate method name "$$ha$…"`). `vdx` and `build.sh` drop that one flag. The stock daemon
  doesn't, so it's an upstream issue too.
- **No tests for the daemon additions yet.** Upstream's tests for the stock classes aren't
  copied here either.

## Asks for upstream (Flow Dev Loop / Copilot)

- An event journal in the daemon, with `events --cursor` and `watch`. This prototype's
  `Events`/`LogTail` are a starting point.
- A file watcher with attribution, and `claim`, so agents and IDEs can mark their own writes.
- An extension seam: app-contributed verbs over the registration channel, or a second connection
  as here.
- Copilot → daemon write notifications, replacing the IDE-plugin impersonation.
- Hook recipes for agents in the installed `vaadin-devloop` skill.
- Name the cached HotswapAgent jar so Copilot recognises it, and agree with Copilot on how it
  detects the dev loop's hotswap (it currently requires JDWP).
- `start` should compile sources that changed while no daemon ran, and drop classes whose
  source is gone (`apply` too).
- Ignore (or strip) `-XX:HotswapAgent` from an inherited `JAVA_TOOL_OPTIONS` when launching the
  app, since the daemon adds HotswapAgent itself.

## Files

- `daemon/src/main/java/com/vaadin/flow/devloop/daemon/`: new are `Events`, `LogTail`,
  `SourceWatch`, `Attribution`, `CopilotBridge`, `Annotations`, `StaleClasses`, `MiniJson`.
  Changed are `Daemon` (verbs, event emission, extension seam, the stale-class check on start),
  `Launch` (JDWP flag), `HotswapAgentJar` (cache file name) and `AppProcess` (crash event). Everything
  else is untouched upstream `25.4.0-alpha5` source.
- To rebase onto a newer upstream: take `flow-devloop-daemon/src/main` at the new tag, and
  reapply `git diff <old tag> -- Daemon.java AppProcess.java` from this tree plus the new
  files. The 25.3 → 25.4 rebase had three small conflicts, all in `Daemon.java`.
