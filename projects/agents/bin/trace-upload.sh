#!/usr/bin/env bash
# Turn one agent run's recorded stream into a Langfuse trace and send it, as
# OpenTelemetry (Langfuse v4 takes traces only through /api/public/otel).
#
#   trace-upload.sh <stream.tsv> <status> [pr-url]
#
# stream.tsv is run-agent.sh's record: "<unix microseconds>\t<stream-json event>"
# per line, with the Claude credential already blanked out. The trace:
#   AGENT       issue #N: title       input = issue, output = the agent's report
#   ├ GENERATION claude: <model>       one per model message, with its token usage
#   ├ TOOL       Bash: npm run build   one per tool call, input/output, ERROR level on failure
#   └ GENERATION total cost            Claude Code's own total, so the trace's cost is exact
#
# Best effort: without LANGFUSE_HOST/KEYS, or with Langfuse down, it prints a
# warning and exits 0. Env: ISSUE, REPO, LANGFUSE_HOST, LANGFUSE_PUBLIC_KEY,
# LANGFUSE_SECRET_KEY; optional ISSUE_TITLE_FILE, ISSUE_BODY_FILE, REPORT_FILE.
set -uo pipefail

stream=$1 status=$2 pr=${3:-}
warn() { echo "[trace] $*" >&2; exit 0; }
[ -n "${LANGFUSE_HOST:-}" ] && [ -n "${LANGFUSE_PUBLIC_KEY:-}" ] && [ -n "${LANGFUSE_SECRET_KEY:-}" ] || warn "no Langfuse settings; not tracing"
[ -s "$stream" ] || warn "no recorded stream; not tracing"

rd() { cat "$1" 2>/dev/null || true; }
payload=$(jq -R -s \
  --arg tid "$(openssl rand -hex 16)" --arg rid "$(openssl rand -hex 4)" \
  --arg issue "${ISSUE:-?}" --arg repo "${REPO:-}" --arg status "$status" --arg pr "$pr" \
  --arg title "$(rd "${ISSUE_TITLE_FILE:-/work/out/title}")" \
  --arg body "$(rd "${ISSUE_BODY_FILE:-/work/out/issue.md}")" \
  --arg report "$(rd "${REPORT_FILE:-/work/out/report.md}")" '
  def a($k; $v): {key: $k, value: {stringValue: ($v | tostring)}};
  def n($k; $v): {key: $k, value: {intValue: ($v | tostring)}};
  def ns: tostring + "000";                                   # microseconds -> ns
  def sid($i): $rid + ("00000000" + ($i | tostring))[-8:];    # 16 hex chars
  def clip($n): if type == "string" then .[0:$n] else (tojson | .[0:$n]) end;
  def tool_label: (.command // .file_path // .pattern // .path // .description // "") | tostring | gsub("\n"; " ") | .[0:80];

  [split("\n")[] | select(length > 0) | index("\t") as $p
   | {ts: .[0:$p], ev: (.[$p + 1:] | fromjson? // null)} | select(.ev != null)] as $evs
  | if ($evs | length) == 0 then empty else . end
  | $evs[0].ts as $t0 | $evs[-1].ts as $t1
  | ([$evs[] | select(.ev.type == "result")][0].ev // {}) as $res
  | "issue #\($issue): \($title)" as $name

  # One generation per model message; it starts when the previous event ended.
  | [range(0; $evs | length) as $i | $evs[$i] | select(.ev.type == "assistant")
     | . + {prev: $evs[[$i - 1, 0] | max].ts}] as $as
  | [$as | group_by(.ev.message.id) | sort_by(.[0].ts) | to_entries[] | .key as $k | .value as $g
     | ($g[-1].ev.message.usage // {}) as $u
     | {traceId: $tid, spanId: sid(1000 + $k), parentSpanId: sid(0), kind: 1,
        name: "claude: \($g[0].ev.message.model // "model")",
        startTimeUnixNano: ($g[0].prev | ns), endTimeUnixNano: ($g[-1].ts | ns),
        attributes: [
          a("langfuse.observation.type"; "generation"),
          a("gen_ai.request.model"; $g[0].ev.message.model // ""),
          a("langfuse.observation.usage_details"; {
              input: ($u.input_tokens // 0), output: ($u.output_tokens // 0),
              cache_read_input_tokens: ($u.cache_read_input_tokens // 0),
              cache_creation_input_tokens: ($u.cache_creation_input_tokens // 0)} | tojson),
          # Zero here, exact total below: Langfuse would otherwise price each
          # message itself and the trace total would not match the bill.
          a("langfuse.observation.cost_details"; {total: 0} | tojson),
          a("langfuse.observation.output"; [$g[].ev.message.content[]?
              | if .type == "text" then .text elif .type == "tool_use" then "[\(.name)]" else empty end]
              | join("\n") | clip(4000))]}] as $gens

  # One tool span per tool_use, ended by its tool_result.
  | [$evs[] | select(.ev.type == "user") | .ts as $t | .ev.message.content[]?
     | select(type == "object" and .type == "tool_result")
     | {id: .tool_use_id, ts: $t, err: (.is_error // false),
        out: (.content | if type == "array" then (map(.text? // "") | join("\n")) else tostring end)}] as $results
  | [$evs[] | select(.ev.type == "assistant") | .ts as $t | .ev.message.content[]?
     | select(.type == "tool_use") | {id, name, input, ts: $t}] as $uses
  | [$uses | to_entries[] | .key as $k | .value as $use
     | ([$results[] | select(.id == $use.id)][0]) as $r
     | {traceId: $tid, spanId: sid(5000 + $k), parentSpanId: sid(0), kind: 1,
        name: ("\($use.name): " + ($use.input | tool_label)),
        startTimeUnixNano: ($use.ts | ns), endTimeUnixNano: (($r.ts // $use.ts) | ns),
        attributes: ([
          a("langfuse.observation.type"; "tool"),
          a("langfuse.observation.input"; $use.input | clip(4000)),
          a("langfuse.observation.output"; ($r.out // "") | clip(4000))]
          + (if ($r.err // false) then [a("langfuse.observation.level"; "ERROR")] else [] end))}] as $tools

  | {traceId: $tid, spanId: sid(9999), parentSpanId: sid(0), kind: 1, name: "total cost (Claude Code)",
     startTimeUnixNano: ($t1 | ns), endTimeUnixNano: ($t1 | ns),
     attributes: [a("langfuse.observation.type"; "generation"),
                  a("langfuse.observation.cost_details"; {total: ($res.total_cost_usd // 0)} | tojson)]} as $cost

  | {traceId: $tid, spanId: sid(0), kind: 1, name: $name,
     startTimeUnixNano: ($t0 | ns), endTimeUnixNano: ($t1 | ns),
     attributes: [
       a("langfuse.trace.name"; $name), a("langfuse.observation.type"; "agent"),
       a("langfuse.trace.session_id"; "issue-\($issue)"),
       a("langfuse.trace.input"; "\($title)\n\n\($body)" | clip(8000)),
       a("langfuse.trace.output"; $report | clip(8000)),
       {key: "langfuse.trace.tags", value: {arrayValue: {values:
         [{stringValue: "dev"}, {stringValue: "issue-\($issue)"}, {stringValue: $status}]}}},
       a("langfuse.trace.metadata.status"; $status), a("langfuse.trace.metadata.pr"; $pr),
       a("langfuse.trace.metadata.repo"; $repo),
       a("langfuse.trace.metadata.num_turns"; $res.num_turns // 0),
       a("langfuse.trace.metadata.duration_ms"; $res.duration_ms // 0)]} as $root

  | {resourceSpans: [{resource: {attributes: [a("service.name"; "agent-runner")]},
                      scopeSpans: [{scope: {name: "agent-runner"},
                                    spans: ([$root] + $gens + $tools + [$cost])}]}]}
' "$stream") || warn "could not build the trace from $stream"
[ -n "$payload" ] || warn "no events in $stream; not tracing"

# Keys through a file descriptor (never on the command line); payload on stdin.
code=$(curl -s -K <(printf 'user = "%s:%s"\n' "$LANGFUSE_PUBLIC_KEY" "$LANGFUSE_SECRET_KEY") \
      --max-time 20 -o /dev/null -w '%{http_code}' \
      -H 'Content-Type: application/json' --data-binary @- \
      "${LANGFUSE_HOST%/}/api/public/otel/v1/traces" <<<"$payload") || code=000
[ "$code" = 200 ] || warn "Langfuse answered HTTP $code; trace not stored"
echo "[trace] sent to Langfuse ($(jq '.resourceSpans[0].scopeSpans[0].spans | length' <<<"$payload") spans)"
