# langfuse

[Langfuse](https://langfuse.com) v4, self-hosted **on the laptop** with Docker
Compose, to trace the agents: every run of the in-cluster `software-engineer`
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

## Upgrading

Image versions are pinned in `compose.yml` (web and worker must match). Bump
both tags, then `./up.sh`. Data lives in the `langfuse_*` Docker volumes;
`docker compose -p langfuse down` keeps them, `down -v` deletes everything.
