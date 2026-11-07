#!/usr/bin/env bash
# The Job's first container: does the work, has the Claude credential and
# nothing else -- no GitHub token, no service-account token.
#
# Writes, for publish.sh in the next container:
#   /work/out/status         ok | nochange | failed
#   /work/out/report.md      the agent's final message (becomes the PR body)
#   /work/out/changes.patch  the change, as a binary git patch
#   /work/out/base           the commit it starts from
#   /work/out/title          the issue title
#
# Env: ISSUE, REPO, OWNER_LOGIN, and ANTHROPIC_API_KEY or CLAUDE_CODE_OAUTH_TOKEN.
set -uo pipefail

out=/work/out
mkdir -p "$out"
finish() { echo "$1" > "$out/status"; [ -n "${2:-}" ] && printf '%s\n' "$2" > "$out/report.md"; echo "[agent] status: $1"; exit 0; }

issue=$(curl -fsSL "https://api.github.com/repos/$REPO/issues/$ISSUE") || finish failed "Could not read issue #$ISSUE from GitHub."
author=$(jq -r '.user.login' <<<"$issue" | tr '[:upper:]' '[:lower:]')
want=$(printf %s "$OWNER_LOGIN" | tr '[:upper:]' '[:lower:]')
[ "$author" = "$want" ] || finish failed "Issue #$ISSUE was not opened by $OWNER_LOGIN; refusing to work on it."
jq -e '.pull_request' <<<"$issue" >/dev/null 2>&1 && finish failed "#$ISSUE is a pull request, not an issue."
title=$(jq -r '.title' <<<"$issue")
body=$(jq -r '.body // ""' <<<"$issue")
printf '%s' "$title" > "$out/title"
printf '%s' "$body" > "$out/issue.md"

git clone --quiet --depth 50 "https://github.com/$REPO.git" /work/repo || finish failed "Could not clone $REPO."
cd /work/repo
git rev-parse HEAD > "$out/base"

role_file=.claude/agents/software-engineer.md
[ -f "$role_file" ] || finish failed "$role_file is missing from the repository."
role=$(awk '/^---$/ { n++; next } n >= 2' "$role_file")

prompt=$(cat <<EOF
You are working on GitHub issue #$ISSUE of $REPO, in a fresh clone at $(pwd).
The issue was written by the repository owner and is your task.

<issue title="$title">
$body
</issue>

Do the work as your role instructions describe, including their definition of done.
Rules for this run:
- Do not run git commit, git push, or open pull requests: the harness commits your
  working-tree changes and opens a draft PR after you finish.
- Do not deploy, build container images, or contact any server other than package
  registries your builds need.
- If the issue is unclear or too large to finish safely, make no changes and explain
  what you need in your final message.
- Your final message becomes the PR description. Use your role's report format.
EOF
)

echo "[agent] issue #$ISSUE: $title"
timeout 40m claude -p "$prompt" \
  --append-system-prompt "$role" \
  --permission-mode acceptEdits \
  --allowedTools "Bash,Read,Edit,Write,Grep,Glob,TodoWrite" \
  --disallowedTools "WebFetch,WebSearch" \
  --output-format stream-json --verbose 2> "$out/claude.err" \
  | while IFS= read -r line; do
      # stream.tsv: <unix microseconds>\t<event>, for the trace (trace-upload.sh)
      printf '%s\t%s\n' "${EPOCHREALTIME/./}" "$line" >> "$out/stream.tsv"
      printf '%s\n' "$line"
    done \
  | jq -R --unbuffered -r '
      fromjson? // empty
      | if .type == "assistant" then
          .message.content[]?
          | if .type == "text" then "💬 " + (.text | gsub("\n"; " ") | .[0:300])
            elif .type == "tool_use" then
              "🔧 \(.name): " + ((.input.command // .input.file_path // .input.pattern // .input.description // "") | tostring | gsub("\n"; " ") | .[0:200])
            else empty end
        elif .type == "result" then
          "✅ finished: \(.subtype), \(.num_turns) turns, \((.duration_ms // 0) / 60000 | floor) min, $\(.total_cost_usd // 0)"
        else empty end'
rc=${PIPESTATUS[0]}
cat "$out/claude.err" >&2
touch "$out/stream.tsv"
# The recorded stream goes to Langfuse from the publish container, which never
# sees the Claude credential -- so blank it out here, where it is known.
for v in ANTHROPIC_API_KEY CLAUDE_CODE_OAUTH_TOKEN; do
  [ -n "${!v:-}" ] && TOK="${!v}" perl -pi -e 's/\Q$ENV{TOK}\E/[REDACTED]/g' "$out/stream.tsv"
done
# The final message (the PR body) is the stream's result event.
cut -f2- "$out/stream.tsv" | jq -R -r 'fromjson? // empty | select(.type == "result") | .result // ""' > "$out/report.raw"

# Never let the credential reach the PR, the patch or the issue.
for v in ANTHROPIC_API_KEY CLAUDE_CODE_OAUTH_TOKEN; do
  val="${!v:-}"
  [ -n "$val" ] || continue
  if grep -rqF -- "$val" "$out/report.raw" . --exclude-dir=.git 2>/dev/null; then
    finish failed "The run's output contained a credential, so nothing was published. Check the job logs."
  fi
done
mv "$out/report.raw" "$out/report.md"

[ $rc -eq 0 ] || finish failed "$(printf 'Claude Code exited with %s (124 = 40-minute limit).\n\n%s' "$rc" "$(tail -c 3000 "$out/report.md")")"

git add -A
if git diff --cached --quiet; then finish nochange; fi
git diff --cached --binary > "$out/changes.patch"
finish ok
