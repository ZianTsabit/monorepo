#!/usr/bin/env bash
# One day's activity from Langfuse, condensed for writing a journal note:
# every Claude Code turn (your prompt + the final reply, trimmed) grouped by
# session, then every in-cluster agent run with its cost.
#
#   day.sh [YYYY-MM-DD]        (default: today, in the laptop's timezone)
#
# Read-only, same access as recall.sh (ClickHouse inside the container, with the
# container's own password; the date goes in as a query parameter).
set -euo pipefail

day=${1:-$(date +%F)}
[[ $day =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}$ ]] || { echo "day.sh: date must be YYYY-MM-DD" >&2; exit 2; }
tz=$(timedatectl show -p Timezone --value 2>/dev/null || true); tz=${tz:-UTC}

docker ps --format '{{.Names}}' 2>/dev/null | grep -qx langfuse-clickhouse-1 || {
  echo "day.sh: Langfuse isn't running (or no Docker access). Start it: projects/langfuse/up.sh" >&2; exit 1; }

ch() { docker exec langfuse-clickhouse-1 sh -c 'exec clickhouse-client --user clickhouse --password "$CLICKHOUSE_PASSWORD" "$@"' sh \
         --param_q_day="$day" --param_q_tz="$tz" -q "$1"; }
flat() { echo "replaceRegexpAll(replaceAll(JSONExtractString(toString($1), 'content'), '\\\\n', ' '), '\\\\s+', ' ')"; }

echo "# Claude activity on $day ($tz)"
echo
echo "## Claude Code turns"
ch "
SELECT session_id,
       formatDateTime(start_time, '%H:%i', {q_tz:String}),
       substring($(flat input), 1, 300),
       substring($(flat output), 1, 500)
FROM events_full
WHERE type = 'SPAN' AND name = 'Conversational Turn'
  AND toDate(start_time, {q_tz:String}) = toDate({q_day:String})
ORDER BY session_id, start_time
FORMAT TabSeparatedRaw" \
| awk -F'\t' '
    $1 != s { s = $1; printf "\n### session %s\n", substr(s, 1, 8) }
    { printf "- %s Q: %s\n  A: %s\n", $2, $3, $4 }
    END { if (NR == 0) print "\n(none)" }'

echo
echo "## In-cluster agent runs"
ch "
SELECT formatDateTime(min(start_time), '%H:%i', {q_tz:String}),
       anyIf(name, type = 'AGENT'),
       round(sumIf(toFloat64OrZero(toString(cost_details['total'])), name = 'total cost (Claude Code)'), 3),
       round(dateDiff('second', min(start_time), max(end_time)) / 60, 1)
FROM events_full
WHERE trace_id IN (SELECT trace_id FROM events_full WHERE type = 'AGENT' AND name LIKE 'issue #%'
                   AND toDate(start_time, {q_tz:String}) = toDate({q_day:String}))
GROUP BY trace_id
ORDER BY 1
FORMAT TabSeparatedRaw" \
| awk -F'\t' '{ printf "- %s %s ($%s, %s min)\n", $1, $2, $3, $4 } END { if (NR == 0) print "(none)" }'
