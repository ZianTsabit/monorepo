# langfuse

[Langfuse](https://langfuse.com) v4, self-hosted **on the laptop** with Docker
Compose, to trace the agents: every run of the in-cluster `dev`
([`projects/agents`](../agents)) becomes one trace with each tool call, its
timing, and the run's cost and tokens.

It runs on the laptop, not on Proxmox, because the Proxmox host is already
memory-overcommitted and Langfuse needs ~3 GiB (ClickHouse is most of it).

```
agent Job on k8s-w2 ──POST /api/public/ingestion──► laptop :3000 (langfuse-web)
                                                         │
                     langfuse-worker ◄── redis ──────────┘
                          │
             postgres · clickhouse · minio      (all internal, no published ports)
```

## First start

```bash
cd projects/langfuse
./init-secrets.sh          # once: random secrets into Vault kv/laptop/langfuse
./up.sh                    # resolve them from Vault and start everything
```

First start takes a few minutes (image pulls, ClickHouse and Postgres
migrations). Then open http://localhost:3000 and sign in with the email
`init-secrets.sh` printed and the password from
`vault kv get -mount=kv -field=USER_PASSWORD laptop/langfuse`. Sign-up is
disabled; the org `ZianTsabit` and project `agents` are created on first start,
with the API keys from Vault (`PUBLIC_KEY`, `SECRET_KEY`).

## Day to day

```bash
./up.sh                               # start / apply changes
docker compose -p langfuse stop       # free the ~3 GiB when you don't need it
docker compose -p langfuse logs -f langfuse-web langfuse-worker
docker compose -p langfuse ps
```

While it is stopped, agent runs still work -- they just send no trace.

## Reaching it from the cluster

The agent Jobs post to `http://192.168.0.108:3000`, the laptop's Wi-Fi address.
Reserve that address for the laptop in the router's DHCP settings, or the
traces stop arriving when it changes. Port 3000 is the only one open to the LAN;
MinIO is published on 127.0.0.1 only, and the rest not at all.

## What gets traced

- **In-cluster agent runs**, one trace per issue (`issue #N: title`), sent by the
  agent's publish step ([`projects/agents`](../agents)).
- **This laptop's Claude Code sessions**, one trace per turn (`Claude Code Turn`,
  grouped by session), through Langfuse's Claude Code plugin
  (`langfuse-observability`, configured with `/plugin configure`; base URL
  `http://localhost:3000`, the keys from Vault).

## Recall: searching past sessions

The traces double as a searchable history:

```bash
projects/langfuse/bin/recall.sh OOMKilled                 # where did that happen, and what did we do?
projects/langfuse/bin/recall.sh -n 3 "heap out of memory" langfuse
projects/langfuse/bin/recall.sh -d 7 -s 5d085242 tunnel   # last week, one session
```

Keyword search over what each step produced, newest first, local time. Claude Code
uses it through the `recall` skill (`.claude/skills/recall/`) when asked "how did we
fix X before?". It's history, not current state -- check the live system before
acting on what it finds.

## Journal: one Obsidian note per day

`projects/langfuse/bin/day.sh [YYYY-MM-DD]` prints a day's Claude Code turns (prompt +
reply, by session) and agent runs (with cost). The `journal` skill
(`.claude/skills/journal/`) turns that into
`~/Documents/Obsidian Vault/Claude/Journal/<date>.md` -- summary, decisions and why,
problems and fixes, open items. Say "journal" in Claude Code. It only ever writes
under the vault's `Claude/` folder.

## Upgrading

Image versions are pinned in `compose.yml` (web and worker must match). Bump
both tags, then `./up.sh`. Data lives in the `langfuse_*` Docker volumes;
`docker compose -p langfuse down` keeps them, `down -v` deletes everything.
