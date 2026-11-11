# Dev loop demo

A Vaadin 25.4 (Flow + Spring Boot) app with a prototype of an extended Dev Loop daemon in
`devloop/`: an event journal that tells you what the developer and Vaadin Copilot changed while
you were idle. Design and command reference: `devloop/README.md`.

## Running the app

- "Run the project" / "start the app" means `.vaadin/vaadin-dev start` (port 8080 unless
  `PORT` or `server.port` says otherwise). That script hands off to `devloop/vdx`, which builds
  and runs the extended daemon jar on first use.
- Don't start the app through Maven (`./mvnw spring-boot:run`) or an IDE run configuration: the
  daemon must own the app process, or nothing is journaled and Copilot edits don't go live.
- After editing source, `.vaadin/vaadin-dev apply` (see the `vaadin-devloop` skill). Copilot's
  own edits are applied by the daemon; IDE edits are journaled but not applied.
- Environment variables the app needs (API keys) must be exported before the first
  `vaadin-dev` command: the app inherits the daemon's environment, which is fixed when the
  daemon spawns.

## The dev-loop briefing

When a prompt arrives with a "Dev loop — collected while you were away" briefing, or when the
developer asks what changed or to fix the UI annotations, follow the `review-dev-changes`
skill. Answer the developer's prompt first.

## Conventions

- Java package root: `com.porotype`; one package per feature (`examplefeature`), with its UI
  in a `ui` subpackage. Shared layout in `base.ui`.
- Styling: Vaadin theme tokens (`--vaadin-*`, `--aura-*`) rather than hard-coded px.
