# CLAUDE.md — agents

The GitHub-issue runner for the software-engineer agent. Read `README.md` for the
flow; this file is what is easy to break.

- **The two containers' credentials must never meet.** `agent` (init container) gets
  the Claude credential and runs model-driven code; `publish` gets the GitHub token
  and runs only `publish.sh`. Don't give `agent` the GitHub token "to open the PR
  itself", and don't run anything from the agent's clone in `publish`.
- **`publish.sh` re-clones and applies a patch on purpose.** The agent could leave
  `.git/hooks` or `.git/config` entries (`core.fsmonitor` runs a command) in its own
  clone; those would execute next to the GitHub token. A patch from `git diff` can't
  carry them. Keep `core.hooksPath=/dev/null` on every git call there too.
- **The owner check happens twice** -- the poller filters by author, and
  `run-agent.sh` re-reads the issue and refuses one opened by anyone else. The repo is
  public: anyone can open an issue, only collaborators can label one.
- **Never on the control plane, one Job at a time.** The first run landed on
  `k8s-cp1` (2 GB then), filled it, and stalled the API server and CoreDNS -- the
  whole site went down until the VM was hard-stopped. Jobs now run only on nodes
  labelled `agents=true` (`k8s-w2`) and request their full 3Gi (1792Mi was OOM-killed), so the scheduler
  can't overcommit. That budget plus `JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=25` is
  what lets Gradle, its test JVM, embedded Postgres and Claude Code fit. Don't lower
  the request below the limit, and don't drop the node affinity.
- **Credentials are scrubbed, not trusted.** `run-agent.sh` refuses to hand over a
  report or tree containing the Claude credential's value; it does not filter
  anything else, so the agent must have nothing else worth leaking (no kubeconfig,
  no ServiceAccount token: `automountServiceAccountToken: false`).
- The role prompt comes from the clone's `.claude/agents/software-engineer.md` at run
  time, so editing that file on `main` changes the agent without a new image.
- Claude Code flags in `run-agent.sh` are the safety boundary for tools:
  `--allowedTools` has no web tools, and `--disallowedTools` blocks them explicitly.
