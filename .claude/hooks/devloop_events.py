#!/usr/bin/env python3
"""Feeds the dev-loop journal (devloop/) into the Claude session.

Workflow this implements (see devloop/README.md, skill review-dev-changes):
the developer works in the browser with Copilot and in the IDE while Claude is
idle; changes are *collected*, and when the developer next prompts, Claude gets a
short briefing and decides - answer the prompt first, then review, integrate, ask
or defer. Nothing here interrupts the developer.

  prompt  UserPromptSubmit. Briefing: new problems in the app; developer/Copilot
          changes not yet reviewed (kept until review-dev-changes marks them
          reviewed, so they survive a context clear); pending UI annotations
          (collected, fixed in one batch when asked); open review notes.
  pre     PreToolUse(Bash): mark the start of a shell command, so files it
          changes count as Claude's own.
  post    PostToolUse: Edit/Write -> claim the file as Claude's. Any tool ->
          one line if the developer changed something mid-turn.

Two cursors in the daemon: `claude` (what Claude has been told about; moved by
every briefing) and `review` (developer changes Claude has reviewed; moved only by
`devloop/vdx events --cursor review --set N`). Only talks to a daemon that is already
running - every vaadin-dev command would otherwise spawn one.
"""
import json
import os
import subprocess
import sys
from datetime import datetime

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
APP = ROOT
VDX = os.path.join(ROOT, "devloop", "vdx")
ANNOTATIONS = os.path.join(APP, "vaadin-copilot-comments.json")
NOTES = os.path.join(APP, ".vaadin", "review-notes.md")
PROBLEMS = ("app.error", "app.crashed", "app.start-failed", "apply.failed")


def vdx(*args):
    env = dict(os.environ, VAADIN_DEV_PROGRESS="never")
    try:
        r = subprocess.run([VDX, "--app", APP, *args], capture_output=True,
                           text=True, timeout=8, env=env)
        return r.stdout if r.returncode == 0 else None
    except (OSError, subprocess.TimeoutExpired):
        return None


def events(*args):
    out = vdx("events", "--json", *args)
    try:
        return json.loads(out) if out else None
    except json.JSONDecodeError:
        return None  # a stock daemon: "unknown command"


def epoch(at):
    return datetime.fromisoformat(at.replace("Z", "+00:00")).timestamp()


def bash_windows(since):
    # Reach back past `since`: a change can be reported after the cursor has
    # already moved over the marks of the command that made it.
    marks = events("--type", "agent.bash", "--since", str(max(0, since - 300)), "--limit", "2000")
    return [(m["data"].get("phase"), epoch(m["at"])) for m in (marks or {}).get("events", [])]


def in_bash(t, windows):
    """Inside a Bash call: the last mark before t is a start, or an end at most
    2 s earlier (the watcher reports a burst ~400 ms after it settles)."""
    last = None
    for phase, mt in windows:
        if mt <= t:
            last = (phase, mt)
    return last is not None and (last[0] == "start" or t - last[1] <= 2)


def developer_changes(evs, windows):
    """Per file: Copilot edits (with labels), undos, outside edits."""
    files = {}
    for e in evs:
        t, src = e["type"], e["source"]
        if t == "copilot.write":
            f = files.setdefault(e["files"][0], {"copilot": 0, "labels": set(), "undo": 0, "external": 0})
            f["copilot"] += 1
            if e["data"].get("label"):
                f["labels"].add(e["data"]["label"].replace("Vaadin Copilot ", ""))
        elif t in ("copilot.undo", "copilot.redo"):
            for p in e["files"]:
                files.setdefault(p, {"copilot": 0, "labels": set(), "undo": 0, "external": 0})["undo"] += 1
        elif t == "files.changed" and src == "external" and not in_bash(epoch(e["at"]), windows):
            for p in e["files"]:
                p = p.replace(" (deleted)", "")
                files.setdefault(p, {"copilot": 0, "labels": set(), "undo": 0, "external": 0})["external"] += 1
    return files


def uncommitted(paths):
    if not paths:
        return set()
    try:
        r = subprocess.run(["git", "-C", ROOT, "status", "--porcelain", "--", *paths],
                           capture_output=True, text=True, timeout=5)
        return {line[3:].strip() for line in r.stdout.splitlines()}
    except (OSError, subprocess.TimeoutExpired):
        return set(paths)


def pending_annotations():
    try:
        with open(ANNOTATIONS) as f:
            data = json.load(f)
    except (OSError, ValueError):
        return []
    notes = []
    for view, items in data.items():
        for a in items if isinstance(items, list) else []:
            if a.get("status", "pending") == "pending":
                comps = ", ".join(c.get("displayName", "?") for c in a.get("selectedComponents", []))
                notes.append(f'"{a.get("content", "")}" on {a.get("viewPath", view)}' + (f" → {comps}" if comps else ""))
    return notes


def describe(path, f):
    parts = []
    if f["copilot"]:
        labels = "; ".join(sorted(f["labels"]))
        parts.append(f'{f["copilot"]} Copilot edit(s)' + (f" ({labels})" if labels else ""))
    if f["undo"]:
        parts.append(f'{f["undo"]} Copilot undo/redo')
    if f["external"]:
        parts.append("edited outside Claude/Copilot")
    return f"    {path} — {', '.join(parts)}"


def briefing():
    new = events("--cursor", "claude", "--limit", "200")
    if new is None:
        return None
    review = events("--cursor", "review", "--peek", "--limit", "500")
    review_since = review["since"] if review else 0
    windows = bash_windows(review_since)
    lines = []

    problems = [e for e in new["events"] if e["type"] in PROBLEMS]
    if problems:
        lines.append("Problems in the running app (new):")
        for e in problems[-5:]:
            frame = e["data"].get("firstAppFrame") or e["data"].get("applicationFrame")
            lines.append(f'    #{e["seq"]} {e["type"]}: {e["summary"][:160]}' + (f" [{frame}]" if frame else ""))

    changes = developer_changes(review["events"] if review else [], windows)
    # A change since committed or reverted leaves nothing to review.
    dirty = uncommitted(list(changes))
    changes = {p: f for p, f in changes.items() if p in dirty}
    if changes:
        lines.append(f"Developer/Copilot changes not yet reviewed (since event #{review_since}):")
        lines += [describe(p, f) for p, f in changes.items()]

    added = [e for e in new["events"] if e["type"] == "copilot.annotation.added"]
    pending = pending_annotations()
    if pending:
        lines.append(f"Pending UI annotations: {len(pending)}" + (f" ({len(added)} new)" if added else "")
                     + " — collected for a batch fix; fix only when the developer asks.")
        lines += [f"    {n[:160]}" for n in pending[:6]]
        if len(pending) > 6:
            lines.append(f"    … {len(pending) - 6} more in vaadin-copilot-comments.json")

    if os.path.isfile(NOTES) and os.path.getsize(NOTES) > 0:
        lines.append("Open review notes from an earlier review: .vaadin/review-notes.md")

    if not lines:
        return None
    return "\n".join(
        ["Dev loop — collected while you were away:"] + lines +
        ["Handle per the review-dev-changes skill: answer the developer's prompt first; "
         "review unreviewed changes alongside it (re-read those files before editing them); "
         "fix silently only what the skill allows, otherwise add one short 'Dev loop' note "
         "with findings/questions at the end of your reply. Don't fix annotations unprompted."])


def midturn():
    new = events("--cursor", "claude", "--limit", "100")
    if not new:
        return None
    windows = bash_windows(new["since"])
    changes = developer_changes(new["events"], windows)
    problems = [e for e in new["events"] if e["type"] in PROBLEMS]
    added = [e for e in new["events"] if e["type"].startswith("copilot.annotation.")]
    bits = []
    if changes:
        bits.append("the developer changed " + ", ".join(os.path.basename(p) for p in changes)
                    + " (re-read before editing; review at the end of this task)")
    if added:
        bits.append(f"{len(added)} annotation change(s)")
    for e in problems[-2:]:
        bits.append(f'{e["type"]}: {e["summary"][:140]}')
    return ("Dev loop, meanwhile: " + "; ".join(bits) + ".") if bits else None


def main():
    mode = sys.argv[1] if len(sys.argv) > 1 else "prompt"
    try:
        hook = json.load(sys.stdin)
    except ValueError:
        hook = {}
    if not os.path.isfile(os.path.join(APP, ".vaadin", "daemon.properties")):
        return
    tool = hook.get("tool_name", "")
    if mode == "pre":
        if tool == "Bash":
            vdx("emit", "agent.bash", "source=agent", "phase=start", "--", "Bash started")
        return
    if mode == "post":
        if tool in ("Edit", "Write", "MultiEdit", "NotebookEdit"):
            path = (hook.get("tool_input") or {}).get("file_path") or (hook.get("tool_input") or {}).get("notebook_path")
            if path:
                vdx("emit", "claim", "agent", path, "--", tool)
            return
        if tool == "Bash":
            vdx("emit", "agent.bash", "source=agent", "phase=end", "--", "Bash finished")
        text, event = midturn(), "PostToolUse"
    else:
        text, event = briefing(), "UserPromptSubmit"
    if text:
        print(json.dumps({"hookSpecificOutput": {"hookEventName": event, "additionalContext": text}}))


if __name__ == "__main__":
    main()
