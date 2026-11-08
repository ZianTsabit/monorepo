#!/usr/bin/env bash
# Run by the CronJob every few minutes. Starts at most one agent Job, for the
# oldest open issue labelled agent:todo that the owner opened, and marks issues
# whose Job died without reporting (OOM, deadline) as agent:failed.
#
# Env: REPO (owner/name), OWNER_LOGIN, IMAGE, GH_TOKEN, NAMESPACE.
# The Job template is mounted at /etc/agents/job-template.yaml.
set -euo pipefail

say() { echo "[poller] $*"; }
lower() { tr '[:upper:]' '[:lower:]'; }
owner_lc=$(printf %s "$OWNER_LOGIN" | lower)

# Labels the workflow uses; created on first run, then left alone.
existing=$(gh label list -R "$REPO" --limit 200 --json name -q '.[].name')
while IFS='|' read -r name color desc; do
  grep -qxF "$name" <<<"$existing" || gh label create "$name" -R "$REPO" --color "$color" --description "$desc" >/dev/null
done <<'EOF'
agent:todo|0E8A16|Ready for the dev agent (owner only)
agent:working|FBCA04|The dev agent is on it
agent:done|5319E7|The agent opened a draft PR
agent:failed|B60205|The agent run failed; see the issue comment
EOF

# 1. Jobs that failed before their publish step ran (OOM, deadline): report once.
kubectl -n "$NAMESPACE" get jobs -l app=agent-run,reported!=true -o json \
  | jq -r '.items[] | select((.status.failed // 0) > 0) | .metadata.labels.issue' \
  | while read -r n; do
      [ -n "$n" ] || continue
      say "job agent-issue-$n failed without reporting; marking issue #$n failed"
      gh issue edit "$n" -R "$REPO" --remove-label agent:working --add-label agent:failed >/dev/null || true
      reason=$(kubectl -n "$NAMESPACE" get pods -l "job-name=agent-issue-$n" -o json \
        | jq -r '[.items[].status | (.initContainerStatuses // [])[], (.containerStatuses // [])[]
                  | select(.state.terminated and .state.terminated.exitCode != 0)
                  | "\(.name): \(.state.terminated.reason) (exit \(.state.terminated.exitCode))"] | join(", ") | if . == "" then "deadline or eviction" else . end')
      gh issue comment "$n" -R "$REPO" --body "🤖 The dev agent's run stopped before it could report: $reason. Logs: \`kubectl -n $NAMESPACE logs job/agent-issue-$n -c agent\` (and \`-c publish\`)." >/dev/null || true
      kubectl -n "$NAMESPACE" label job "agent-issue-$n" reported=true --overwrite >/dev/null
    done

# 2. One run at a time: the nodes have room for one.
active=$(kubectl -n "$NAMESPACE" get jobs -l app=agent-run -o json \
  | jq '[.items[] | select((.status.active // 0) > 0 or ((.status.succeeded // 0) == 0 and (.status.failed // 0) == 0))] | length')
if [ "$active" -gt 0 ]; then say "a run is in progress; nothing to start"; exit 0; fi

# 3. Oldest open agent:todo issue, opened by the owner. The author check is
#    done here on the API's answer, not only through --author.
n=$(gh issue list -R "$REPO" --state open --label agent:todo --author "$OWNER_LOGIN" \
      --limit 50 --json number,author \
    | jq -r --arg o "$owner_lc" '[.[] | select((.author.login | ascii_downcase) == $o)] | sort_by(.number) | .[0].number // empty')
if [ -z "$n" ]; then say "no agent:todo issues"; exit 0; fi

if kubectl -n "$NAMESPACE" get job "agent-issue-$n" >/dev/null 2>&1; then
  say "job agent-issue-$n already exists; fixing labels only"
else
  ISSUE="$n" envsubst '${ISSUE} ${IMAGE} ${REPO} ${OWNER_LOGIN} ${NAMESPACE}' \
    < /etc/agents/job-template.yaml | kubectl create -f -
  gh issue comment "$n" -R "$REPO" --body "🤖 The dev agent picked this up (job \`agent-issue-$n\`). It will open a draft PR for review, or comment here if it can't." >/dev/null
fi
gh issue edit "$n" -R "$REPO" --remove-label agent:todo --add-label agent:working >/dev/null
say "started issue #$n"
