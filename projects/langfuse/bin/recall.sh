#!/usr/bin/env bash
# Search past agent and Claude Code sessions stored in Langfuse (episodic memory).
#
#   recall.sh [-n LIMIT] [-d DAYS] [-s SESSION_PREFIX] [-i] TERM [TERM...]
#
# Every TERM must appear (case-insensitive) in an observation's name or output --
# what a step produced, which is where a fact first shows up. -i also searches
# inputs; the Claude Code plugin stores the whole conversation so far as each
# model call's input, so -i finds a fact again in every later turn. Prints the
# newest matches first, one per trace, with a snippet around the first term, in
# local time. Keyword search only: "out of memory" won't find "OOMKilled", so try
# the words that would have been in the logs.
#
# Read-only. Queries ClickHouse inside the langfuse-clickhouse-1 container with
# that container's own password, so no secret passes through here; search terms
# go in as query parameters, never spliced into the SQL.
set -euo pipefail

limit=10 days=90 session="" with_input=0
usage() { sed -n '2,16p' "$0" | sed 's/^# \{0,1\}//'; exit "${1:-0}"; }
while getopts "n:d:s:ih" o; do
  case $o in n) limit=$OPTARG ;; d) days=$OPTARG ;; s) session=$OPTARG ;; i) with_input=1 ;; h) usage ;; *) usage 2 ;; esac
done
tz=$(timedatectl show -p Timezone --value 2>/dev/null || true); tz=${tz:-UTC}
shift $((OPTIND - 1))
[ $# -gt 0 ] || usage 2
[[ $limit =~ ^[0-9]+$ && $days =~ ^[0-9]+$ ]] || { echo "recall: -n and -d take numbers" >&2; exit 2; }

docker ps --format '{{.Names}}' 2>/dev/null | grep -qx langfuse-clickhouse-1 || {
  echo "recall: Langfuse isn't running (or no Docker access). Start it: projects/langfuse/up.sh" >&2; exit 1; }

# One condition and one query parameter per term.
conds=() params=()
i=0
for t in "$@"; do
  conds+=("positionCaseInsensitive(txt, {t$i:String}) > 0")
  params+=("--param_t$i=$t")
  i=$((i + 1))
done
where=$(printf '%s' "${conds[0]}"; for c in "${conds[@]:1}"; do printf ' AND %s' "$c"; done)

query="
WITH replaceRegexpAll(replaceAll(concat(name, ' ', if({q_input:UInt8} = 1, toString(input), ''), ' ', toString(output)), '\\\\n', ' '), '\\\\s+', ' ') AS txt
SELECT
  formatDateTime(start_time, '%Y-%m-%d %H:%i', {q_tz:String}) AS at,
  substring(if(session_id = '', trace_id, session_id), 1, 8) AS sess,
  type,
  substring(if(trace_name = '', name, trace_name), 1, 40) AS what,
  substring(txt, greatest(1, positionCaseInsensitive(txt, {t0:String}) - 80), 240) AS snippet
FROM events_full
WHERE start_time > now() - INTERVAL {q_days:UInt32} DAY
  AND ({q_session:String} = '' OR startsWith(session_id, {q_session:String}))
  AND $where
ORDER BY start_time DESC
LIMIT 1 BY trace_id
LIMIT {q_limit:UInt32}
FORMAT TabSeparatedRaw"

docker exec langfuse-clickhouse-1 sh -c 'exec clickhouse-client --user clickhouse --password "$CLICKHOUSE_PASSWORD" "$@"' sh \
    --param_q_days="$days" --param_q_limit="$limit" --param_q_session="$session" \
    --param_q_input="$with_input" --param_q_tz="$tz" "${params[@]}" -q "$query" \
  | awk -F'\t' '{ printf "%s  %s  %-10s %s\n    %s\n\n", $1, $2, $3, $4, $5 } END { if (NR == 0) print "recall: no matches" }'
