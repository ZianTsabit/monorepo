# agents

Runs the [`software-engineer`](../../.claude/agents/software-engineer.md) agent on
GitHub issues, inside the home k3s cluster. Open an issue, add the `agent:todo`
label, and within a few minutes a draft PR appears for review.

```
CronJob issue-poller (every 3 min)
  └─ oldest open issue labelled agent:todo, opened by the owner
       └─ Job agent-issue-<n>
            ├─ init container "agent"   Claude credential only
            │    fetch issue → clone → claude -p (software-engineer role) → patch + report
            └─ container "publish"      GitHub token only
                 fresh clone → apply patch → push agent/issue-<n> → draft PR → comment, label
```

The cluster pulls from GitHub; nothing on GitHub can reach in. Deployment steps,
secrets and operations are in
[`deployments/k8s/agents/README.md`](../../deployments/k8s/agents/README.md).

## Using it

1. Open an issue describing the change. Be concrete: which page, what it should do,
   what "done" looks like. Only issues **you** open are picked up -- the repo is
   public, and anyone else's issue is ignored even if labelled.
2. Add the label `agent:todo`.
3. Labels move `agent:todo` → `agent:working` → `agent:done` (a draft PR is linked in a
   comment) or `agent:failed` (the reason is in a comment).
4. Review the PR like any other. The agent never merges, deploys or bumps versions;
   release with the `devops` agent from your laptop as usual.

To retry an issue, delete its Job (`kubectl -n agents delete job agent-issue-<n>`) and
set the label back to `agent:todo`.

## Layout

| Path | Holds |
| --- | --- |
| `Dockerfile` | `ghcr.io/ziantsabit/agent-runner`: Claude Code, `gh`, `kubectl`, git, JDK 25, Node 24 |
| `bin/poll-issues.sh` | The CronJob: creates labels, reports dead Jobs, starts one Job at a time |
| `bin/run-agent.sh` | The Job's `agent` container: does the work |
| `bin/publish.sh` | The Job's `publish` container: commits, pushes, opens the PR |

## Building

```bash
docker build -t ghcr.io/ziantsabit/agent-runner:<version> projects/agents
docker push ghcr.io/ziantsabit/agent-runner:<version>
```

Then set `IMAGE` in `deployments/k8s/agents/agents.env` and apply. Tool versions are
build args at the top of the `Dockerfile`.
