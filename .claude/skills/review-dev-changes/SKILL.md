---
name: review-dev-changes
description: The dev-loop workflow for changes the developer makes outside this conversation — Vaadin Copilot edits in the browser, IDE edits, Copilot UI annotations — collected by the dev-loop journal (devloop/) and briefed by the UserPromptSubmit hook. Use whenever that briefing lists unreviewed changes, pending annotations or review notes; when the developer asks to fix the annotations; when committing while developer changes are in the tree; or when asked what the developer changed.
---

# Developer and Copilot changes: collect, then decide

The developer works in the browser — Copilot edits, Copilot UI annotations — and in the IDE,
usually while you are idle. The dev loop (`devloop/vdx`, design in `devloop/README.md`)
journals all of it and attributes every change to Copilot, to you, or to "outside" (the IDE).
At the developer's next prompt the hook briefs you on:

- **problems** in the running app since you last looked (errors, crashes, failed applies);
- **unreviewed developer/Copilot changes**, file by file. They stay listed until you mark them
  reviewed, so they survive a context clear;
- **pending UI annotations** in `vaadin-copilot-comments.json`;
- **open review notes** in `.vaadin/review-notes.md`, your findings from an earlier review that
  the developer has not acted on yet.

These are the developer's changes, made on purpose. Your job is to keep them working and in line
with the project, without taking them over and without hijacking the conversation.

## Order of work at each prompt

1. **The developer's request comes first.** Do what they asked. If it touches a file with
   unreviewed changes, re-read that file first and build on the new version; never "restore"
   what you remember.
2. **Problems:** if one is new and plausibly caused by a developer change or by your work,
   mention it up front and look into it. Otherwise list it in the note (step 4).
3. **Unreviewed changes:** review them (checklist below) at a natural point. That is after the
   requested work, or right away if the request depends on them. Review the net change
   (`git diff -- <file>`) unless an undo or a reversal matters.
4. **One short "Dev loop" note at the end of your reply**, only if there is something to say:
   - what changed, in one line;
   - what you fixed silently, if anything;
   - findings and questions.

   Skip the note entirely when everything checks out and nothing was fixed.
5. **Record and mark reviewed.**
   - Write anything still open (unanswered questions, deferred fixes, tests to add) to
     `.vaadin/review-notes.md`, replacing what is resolved, and delete the file when nothing
     is open.
   - Then mark the journal reviewed **up to the last event you actually looked at**:
     `devloop/vdx events --cursor review --set <seq>`. Use the highest `seq` you saw in
     `devloop/vdx events --since <cursor>`; never pass `end` blindly, since changes made during
     your review must stay unreviewed.

## Checklist

| Area | Look for |
|---|---|
| Works | Applied `stable`? A new `app.error`? Tests that cover the view (`grep -rl <ViewClass> src/test`); run them. |
| Looks right | Screenshot the view (Playwright). Copilot adds components with **default** padding and margins: misalignment against hand-tuned siblings, doubled padding from a wrapper layout, wrong heading level. |
| Text | Spelling, grammar, tone, consistency with the rest of the UI copy. |
| Code | Copilot writes at column 0, on one long line, with names generated from the text (`loremIpsumDolorSitAm`, `verticallayout`), and moves `add(...)` calls. Compare with the surrounding code. |
| Rules | `CLAUDE.md` and the conventions of the surrounding code (theme tokens rather than hard-coded px, package structure). |

## Fix silently, ask now, or ask later

- **Silently** (and say so in the note): restoring indentation and line breaks, renaming
  Copilot's placeholder variable names, fixing an obvious typo in UI text. Only if the
  developer has granted standing permission for these (check memory); without it, propose them
  as one batch.
- **Ask now**, in the note at the end of the reply, as one question: anything about intent,
  such as a heading's purpose, a layout choice, wording beyond a typo, or a change that alters
  what the UI does. Offer the likely options; use AskUserQuestion when the choices are discrete.
- **Ask later** (write it to the review notes and mention it once): tests to add, refactoring a
  Copilot-built structure. Raise it again when the developer next works in that area, or when
  committing.
- **Never:** revert, rewrite or "improve" a Copilot or IDE change without a yes. Don't re-ask
  what was already answered (check the notes and memory). Don't nag: one mention per open item
  per session unless it blocks something.
- When the developer answers a recurring question in a general way ("always reformat Copilot
  code"), save it as a feedback memory so the next session acts on it.

## UI annotations: collect, fix in one batch

Annotations are the developer's to-do list for the UI. They are collected, not acted on one by
one. The briefing shows the pending count and text; do not fix them unprompted. When the
developer asks to fix them:

1. Read all pending ones from `vaadin-copilot-comments.json` (keyed by view path, each with
   `content`, `viewFilePath` and `selectedComponents[].treePath`/`displayName`).
2. Fix them together, then run the review checklist on your own result (screenshot each view
   touched).
3. Set each fixed annotation's `"status"` to `"resolved"` and its `updatedAt`, and leave the
   rest alone. If Copilot's panel is open it may overwrite the file with its in-memory copy,
   so tell the developer to reload the panel. This is a known rough edge, see
   `devloop/README.md`.
4. Report one line per annotation: done, or why not.

## Commits

Committing is the developer's call. When a commit comes up and developer/Copilot changes are in
the tree:
- propose **separate commits**: the developer's or Copilot's changes (with your review fixes,
  if they are small and on the same lines) in one, and your own work in another;
- describe Copilot-authored changes as such in the message;
- never sweep developer changes silently into your commit.
