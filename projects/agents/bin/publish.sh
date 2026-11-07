#!/usr/bin/env bash
# The Job's second container: publishes what run-agent.sh produced. Has the
# GitHub token and no Claude credential, and never runs anything the agent wrote:
# it clones afresh and applies the agent's patch, so a hook or git config the
# agent left in its own clone can't run here.
#
# Env: ISSUE, REPO, GH_TOKEN.
set -euo pipefail

out=/work/out
status=$(cat "$out/status" 2>/dev/null || echo failed)
report=$(cat "$out/report.md" 2>/dev/null || echo "(no report)")
title=$(cat "$out/title" 2>/dev/null || echo "issue #$ISSUE")
branch="agent/issue-$ISSUE"

label() { gh issue edit "$ISSUE" -R "$REPO" --remove-label agent:working --add-label "$1" >/dev/null; }
comment() { gh issue comment "$ISSUE" -R "$REPO" --body-file - >/dev/null; }

# Any failure from here on (push refused, PR API error) is reported on the issue
# instead of leaving it at agent:working. Exits 0 so the poller doesn't report
# it a second time.
on_error() {
  trap - ERR
  printf '🤖 The software-engineer agent finished its work, but publishing it failed at: `%s`.\n\nCheck the GitHub token'"'"'s permissions (Contents, Issues, Pull requests: read and write). Logs: `kubectl -n agents logs job/agent-issue-%s -c publish`.\n' "$BASH_COMMAND" "$ISSUE" | comment || true
  label agent:failed || true
  exit 0
}
trap on_error ERR

case "$status" in
  ok) ;;
  nochange)
    printf '🤖 The software-engineer agent made no changes.\n\n<details><summary>Its report</summary>\n\n%s\n\n</details>\n' "$report" | comment
    label agent:failed; exit 0 ;;
  *)
    printf '🤖 The software-engineer agent could not finish.\n\n%s\n' "$report" | comment
    label agent:failed; exit 0 ;;
esac

gh auth setup-git
git clone --quiet --depth 50 "https://github.com/$REPO.git" /tmp/pub
cd /tmp/pub
git checkout --quiet "$(cat "$out/base")"
git checkout --quiet -b "$branch"
git -c core.hooksPath=/dev/null apply --index "$out/changes.patch"
git -c core.hooksPath=/dev/null \
    -c user.name="ziantsabit software-engineer agent" \
    -c user.email="software-engineer-agent@users.noreply.github.com" \
    commit --quiet -m "$title (#$ISSUE)" \
    -m "Written by the software-engineer AI agent for issue #$ISSUE. Review before merging."
git -c core.hooksPath=/dev/null push --quiet --force origin "$branch"

# "Closes #N" makes GitHub close the issue when the PR is merged.
pr_body=$(printf '> 🤖 **Opened by the software-engineer AI agent.** Closes #%s. Nothing here is merged or deployed; review it first.\n\n%s\n' "$ISSUE" "$report")
url=$(gh pr list -R "$REPO" --head "$branch" --state open --json url -q '.[0].url')
if [ -n "$url" ]; then
  gh pr edit "$url" --body "$pr_body" >/dev/null
else
  url=$(gh pr create -R "$REPO" --draft --base main --head "$branch" --title "$title (#$ISSUE)" --body "$pr_body")
fi
printf '🤖 Draft PR ready for review: %s\n' "$url" | comment
label agent:done
echo "[publish] $url"
