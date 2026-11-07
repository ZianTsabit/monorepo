# CLAUDE.md — langfuse

Self-hosted Langfuse on the laptop for agent traces. `README.md` has the how-to;
this is what is easy to break.

- **Never re-generate the secrets.** `init-secrets.sh` refuses to overwrite
  `kv/laptop/langfuse` on purpose: Postgres and ClickHouse were initialised with
  those passwords inside their volumes, and new values lock Langfuse out of its
  own data. Rotating one means changing it inside the database too.
- **`docker compose up` without `./up.sh` fails by design** -- every secret in
  `compose.yml` is `${VAR:?}`. Don't add defaults; Langfuse's upstream file
  ships `postgres`/`miniosecret`-style defaults, and those must never come back.
- **Only port 3000 is on the LAN.** The upstream file also publishes MinIO
  (9090) and the AI gateway to every interface; here MinIO is 127.0.0.1-only and
  the gateway is dropped. Internal services publish nothing, so they can't clash
  with other local Compose projects' 5432/6379/9000.
- **Web and worker tags must match** (`4.70.0` now). `TELEMETRY_ENABLED` is off:
  traces contain repo code and prompts, and nothing about them should leave the
  laptop.
- `NEXTAUTH_URL` is `http://localhost:3000` because the UI is used on the laptop.
  The ingestion API answers on any host, which is all the cluster needs.
