# Dev Loop, extended: a demo

A plain [start.vaadin.com](https://start.vaadin.com) project (Vaadin 25.4, Flow + Spring Boot)
with a prototype that lets Claude Code **notice what you changed while it was idle**: your
edits with Vaadin Copilot in the browser, your Copilot UI annotations, your IDE edits, and the
errors the running app threw. At your next prompt Claude gets a short "Dev loop" briefing and
reviews those changes alongside whatever you asked.

It works by extending Vaadin's Dev Loop daemon (`.vaadin/vaadin-dev`) with an event journal.
The details are in [`devloop/README.md`](devloop/README.md). A longer write-up with
screenshots: [Dev Loop, Extended](https://claude.ai/artifact/35vYhWGyLQN62T233hCpUR). It also
shows UI driving (`vdx ui`) and Observability Kit findings (`vdx o11y`); those aren't in this
repository yet and will come back.

> **Prototype.** The daemon is a fork of Flow's `flow-devloop-daemon` and reaches Copilot by
> impersonating an IDE plugin. Expect rough edges; see
> [Known issues](devloop/README.md#known-issues-and-rough-edges).

## Prerequisites

- Linux or macOS (the scripts are bash; Windows isn't supported yet).
- JDK 21 or newer, with `javac`: the extended daemon is built from source on first use.
- Python 3, for the Claude Code hooks.
- [Claude Code](https://claude.com/claude-code).
- A browser on the same machine. Open the app at `http://localhost:8080`: Copilot's annotation
  "Add" button only works in a secure context, and `localhost` counts as one.
- Copilot may ask you to sign in with a vaadin.com account.

Maven isn't needed: `./mvnw` downloads it.

## Try it in five minutes

1. **Clone and start Claude in the repository root.** Claude Code only loads the hooks from
   the directory it starts in.

   ```bash
   git clone https://github.com/emarc/devloop-extended-demo.git && cd devloop-extended-demo
   claude
   ```

   Then say **"run the project"**. Claude runs `.vaadin/vaadin-dev start`, which builds the
   extended daemon and starts the app on port 8080 (`PORT=8081` before the first command to
   change it). The first start takes a minute or two while Maven downloads dependencies.

2. **Change the UI with Copilot.** Open http://localhost:8080, open Copilot (the Vaadin
   button at the bottom-right), select a component and ask for a change: "make the Create button say Add task", "add a heading
   above the grid". Copilot's edits go live right away.

3. **Add a UI annotation.** In Copilot's annotations mode, select a component and write a
   note, such as "this should be primary-colored". Annotations are a to-do list for Claude; it
   collects them and fixes them in one batch when you ask.

4. **Send Claude any prompt.** "What's new?" works, and so does real work. Claude answers
   your prompt, reviews what you and Copilot changed (formatting, generated variable names,
   spacing, typos), and ends with a short "Dev loop" note: what changed, what it found, any
   question. Then say **"fix the annotations"**.

Want to see an error reported? Open **Error Demo** in the menu and click "Add sample tasks":
it has a bug that throws on the server. Send Claude a prompt, and the briefing carries the
exception's root cause and the line in `ErrorDemoView.java` it came from.

## What's where

| Path | What |
|---|---|
| `src/` | The generated Vaadin app: a task list (`com.porotype.examplefeature`), plus an Error Demo view with a deliberate bug (`com.porotype.devloopdemo`). |
| `devloop/` | The extended daemon (`daemon/`), its build (`build.sh`) and client (`vdx`). [README](devloop/README.md). |
| `.vaadin/vaadin-dev` | The stock Dev Loop client from `mvn vaadin:install-dev-cli`, with a marked block at the top that hands every command to `devloop/vdx`. Reinstalling the CLI removes that block. |
| `.claude/hooks/devloop_events.py`, `.claude/settings.json` | The hooks: the briefing at each prompt, and the marks that attribute Claude's own edits. |
| `.claude/skills/review-dev-changes/` | What Claude does with the briefing: review, fix silently or ask, batch the annotations, keep your changes in their own commit. |
| `.claude/skills/vaadin-devloop/` | The stock Dev Loop skill (generated): how Claude starts the app and applies its edits. |
| `CLAUDE.md` | Project instructions for Claude. |

## Good to know

- Start the app through `.vaadin/vaadin-dev`, not `./mvnw spring-boot:run` or an IDE run
  configuration. The daemon has to own the app process for anything to be journaled and for
  Copilot's edits to go live.
- If IntelliJ or VS Code with the Vaadin plugin has this project open, Copilot may talk to that
  plugin instead of the daemon. Its edits then show as "edited outside" and aren't applied
  automatically. Untested so far.
- Edits you save in an IDE are journaled but not applied: editors save half-finished code.
  Ask Claude to apply, or run `.vaadin/vaadin-dev apply`.
- Environment variables the app needs must be exported before the first `vaadin-dev`
  command, since the app inherits the daemon's environment. To change them,
  `.vaadin/vaadin-dev shutdown` first.
- The journal lives in `target/devloop/`, so `mvn clean` resets it.
