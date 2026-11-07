---
name: journal
description: Write (or update) the daily journal note in Zian's Obsidian vault from that day's Claude Code sessions and agent runs recorded in Langfuse. Use when Zian says "/journal", "journal", "write today's journal", "summarise today" or "what did we do on <date>" and wants it kept as a note.
---

# Journal: one Obsidian note per day

Source: `projects/langfuse/bin/day.sh [YYYY-MM-DD]` (default today, local time) prints
every Claude Code turn of the day (prompt + reply, trimmed, grouped by session) and
every in-cluster agent run with its cost. If Docker says permission denied, run it as
`echo 'projects/langfuse/bin/day.sh <date>' | newgrp docker`. If Langfuse is down,
say so and offer `projects/langfuse/up.sh`.

Destination: `~/Documents/Obsidian Vault/Claude/Journal/<YYYY-MM-DD>.md`.
**Only ever write inside `~/Documents/Obsidian Vault/Claude/`** -- the rest of the
vault is Zian's own notes; don't read or change them.

## Steps

1. Run `day.sh` for the requested date (default today). Read all of it.
2. If the note already exists, read it and **update** it (merge new sessions in; keep
   anything Zian added by hand). Otherwise create it.
3. Write it in this shape:

```markdown
---
date: YYYY-MM-DD
tags: [claude/journal]
sessions: [5d085242]
---
# YYYY-MM-DD

## Summary
- 3–5 bullets: the outcomes that mattered, in plain words.

## Done
### <topic>
- what was built / changed / deployed, with versions and file paths

## Decisions
- **<decision>** — why (the reason is the valuable part)

## Problems and fixes
- **<symptom>** → cause → fix

## Open
- [ ] things left to do, as Obsidian checkboxes

## Links
- Memory: [[homelab-topology]] · [[project-agents-pipeline]]  (only notes that are relevant)
- Sessions: `5d085242` (search with `projects/langfuse/bin/recall.sh -s 5d085242 <term>`)
- Agent runs: issue #N → PR #M ($cost)
```

## Rules

- Group by **topic**, not chronologically; drop chit-chat and dead ends unless the
  dead end taught something (then it goes under Problems and fixes).
- Facts only from `day.sh` output; don't invent outcomes. If a turn's reply is cut
  off and the result matters, check with `recall.sh` or the repo.
- **No secrets**, tokens or passwords -- name the Vault path instead. Internal IPs are
  fine (the vault is private), but the note must never be copied into the public repo.
- Link memory notes with `[[name]]` (they live in `Claude/Memory/`). If the day
  produced a lesson worth keeping that no memory note has, say so at the end and
  offer to add it -- don't silently edit memory from the journal.
- Finish by telling Zian the note's path and its Summary bullets.
