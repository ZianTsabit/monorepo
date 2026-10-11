# k3s learning plan

What to learn next on the home cluster, now that the basics are written down in
[`notes/kubernetes/`](../../notes/kubernetes/). Each step fixes something that is
currently manual or fragile in the real setup
([`deployments/k8s/ziantsabit/`](../../deployments/k8s/ziantsabit/)), so it isn't
just a toy exercise.

Suggested order: **1 → 2 → 3 → 4 → 6**, then 5 and 7 when they're needed. Each
step builds on the ones before it: GitOps is easier once backups exist,
External Secrets fits naturally into GitOps, and alerts need metrics first.

| # | Topic | Fixes | Status |
| --- | --- | --- | --- |
| 1 | [Resilience](#1-resilience-backups-ha-upgrades) | One server, with state in SQLite, is a single point of failure | ☐ |
| 2 | [GitOps](#2-gitops-argo-cd-or-flux) | Deploys are `kubectl apply` run by hand | ☐ |
| 3 | [Secrets operator](#3-secrets-external-secrets-operator) | Secrets come from `vault-env.sh` on a laptop | ☐ |
| 4 | [Observability](#4-observability-metrics-logs-alerts) | No metrics, no log history, no alerts | ☐ |
| 5 | [Workload hygiene](#5-workload-hygiene) | No resource tuning; the backend is stuck at one replica | ☐ |
| 6 | [Networking and security](#6-networking-and-security) | Every pod can reach every other pod; admin kubeconfig used for everything | ☐ |
| 7 | [In-cluster storage](#7-in-cluster-storage) | Only `local-path`, so a volume dies with its node | ☐ |

---

## 1. Resilience: backups, HA, upgrades

**Why here:** `k8s-cp1` is the only server and keeps the cluster's state in
SQLite. If that disk dies, every object (Deployments, Secrets, ...) is gone.
See [architecture.md](../../notes/kubernetes/architecture.md).

**Learn**
- Where k3s keeps its state (`/var/lib/rancher/k3s/server`) and what a backup
  of it has to include (the datastore *and* the `token`).
- Embedded etcd: restarting the server with `--cluster-init` moves it from
  SQLite to etcd, then `k3s etcd-snapshot save` / `list`, and scheduled
  snapshots (`--etcd-snapshot-schedule-cron`), optionally sent to S3 (RustFS).
- Restoring: `k3s server --cluster-reset --cluster-reset-restore-path=...`.
- High availability: 3 servers with embedded etcd (quorum is why it's 3, not 2).
- Upgrades: `system-upgrade-controller` and its `Plan` objects, which upgrade
  servers first and then agents, one node at a time.

**Exercises**
- [ ] Take a manual etcd snapshot and copy it off the node.
- [ ] Schedule snapshots to a RustFS bucket.
- [ ] Disaster-recovery drill: rebuild the cluster from this repo plus a
      snapshot, and write down everything that was missing.
- [ ] Upgrade k3s by one minor version with `system-upgrade-controller`.
- [ ] (Optional) Promote an agent, or add VMs, to get 3 servers.

**Done when:** a lost `k8s-cp1` is an inconvenience you've rehearsed, not a
rebuild from memory.

---

## 2. GitOps: Argo CD or Flux

**Why here:** a deploy today is someone running `kubectl apply -k` against an
overlay. Nothing notices when the cluster drifts from git.

**Learn**
- The pull model: a controller in the cluster watches the repo and applies it.
- Argo CD `Application` / `ApplicationSet` (or Flux `GitRepository` +
  `Kustomization`) pointed at `deployments/k8s/ziantsabit/overlays/{stg,prod}`.
- Sync policies: auto-sync, self-heal, prune, sync waves.
- Bootstrapping: how the GitOps tool itself is installed and managed from git
  ("app of apps").
- CI on the image side: build `ghcr.io/ziantsabit/<image>:<version>` on a tag,
  then a PR that bumps the tag in the manifest (Renovate, or Flux image
  automation).

**Exercises**
- [ ] Install Argo CD (or Flux) and point it at the stg overlay.
- [ ] Change a `.env` value in git and watch it roll the pods without touching
      `kubectl`.
- [ ] `kubectl edit` something by hand and watch self-heal revert it.
- [ ] Add prod, with manual sync to start with.
- [ ] GitHub Actions workflow that builds and pushes the images on a tag.

**Done when:** a merged commit is the deploy.

---

## 3. Secrets: External Secrets Operator

**Why here:** the Kubernetes Secret is generated from the `vault:` references
by `vault-env.sh`, run outside the cluster. That doesn't fit GitOps: nothing in
git can produce the Secret. See [config.md](../../notes/kubernetes/config.md).

**Learn**
- External Secrets Operator: `SecretStore` / `ClusterSecretStore` (how to reach
  Vault) and `ExternalSecret` (which paths/fields become which keys).
- Vault's Kubernetes auth method: the pod's ServiceAccount token as the login,
  with a Vault policy per namespace.
- Refresh intervals and rotation: what happens to running pods when a value
  changes (hint: env vars don't update; look at Reloader or a checksum
  annotation).
- Alternative to compare: Vault Secrets Operator.

**Exercises**
- [ ] Enable Kubernetes auth in Vault, with a role per `ziantsabit-<env>`
      namespace.
- [ ] Replace the generated Secret in stg with an `ExternalSecret`, keeping the
      app's variable names (`AWS_S3_ACCESS_KEY_ID`, ...).
- [ ] Rotate a value in Vault and get the pod to pick it up.
- [ ] Update the deployment README and drop the manual step.

**Done when:** no secret value passes through a laptop to reach the cluster.

---

## 4. Observability: metrics, logs, alerts

**Why here:** right now `kubectl logs` and `kubectl get pods` are the only view.
Logs disappear with the pod, and nobody hears about a crash loop.

**Learn**
- `kube-prometheus-stack` (Helm): Prometheus, Alertmanager, Grafana,
  node-exporter, kube-state-metrics. `ServiceMonitor` / `PodMonitor`.
- Spring Boot metrics: add `micrometer-registry-prometheus` so Actuator serves
  `/actuator/prometheus` (JVM, HTTP latency, Hikari pool).
- Logs: Loki plus Grafana Alloy (or Promtail) collecting every pod's stdout;
  querying with LogQL.
- Basic PromQL: `rate()`, `histogram_quantile()`, `sum by`.
- What's worth an alert: pod restarts, a node not ready, a disk nearly full, a
  5xx rate, a cert or tunnel down.

**Exercises**
- [ ] Install `kube-prometheus-stack`; open the node and pod dashboards.
- [ ] Expose and scrape the backend's metrics; build a small dashboard of
      request rate, p95 latency and errors.
- [ ] Install Loki + Alloy; find a backend error by searching logs instead of
      `kubectl logs`.
- [ ] Alertmanager → a phone notification (Telegram, ntfy, email).
- [ ] Kill a pod on purpose and check the alert fires.

**Done when:** you hear about a problem before a visitor does.

---

## 5. Workload hygiene

**Why here:** pods run without tuned requests and limits, and the backend is
pinned to **one replica** because its rate limits live in memory per pod. See
[workloads.md](../../notes/kubernetes/workloads.md).

**Learn**
- Requests vs limits: what the scheduler uses, what the kernel enforces. QoS
  classes (Guaranteed / Burstable / BestEffort).
- The failure modes: `OOMKilled`, CPU throttling, pods stuck `Pending` with
  `Insufficient cpu`.
- `PodDisruptionBudget`, `topologySpreadConstraints`, pod anti-affinity.
- `kubectl drain` / `cordon` / `uncordon`.
- Rolling update settings (`maxSurge`, `maxUnavailable`) and why probes matter
  to them.
- `HorizontalPodAutoscaler` (needs metrics-server, which k3s ships).
- `ResourceQuota` / `LimitRange` per namespace.

**Exercises**
- [ ] Use the Grafana data from step 4 to set requests and limits for fe, be
      and `cloudflared`.
- [ ] Give the JVM a memory limit that's too low and watch it get OOMKilled;
      then size `-XX:MaxRAMPercentage` properly.
- [ ] Add a PDB and a spread constraint to `cloudflared`, then drain `k8s-w1`
      and watch what moves (and whether the site stays up).
- [ ] Move the backend's throttles to shared state (e.g. Redis/Valkey), then run
      2 replicas and try an HPA.

**Done when:** draining a node or doubling traffic is uneventful.

---

## 6. Networking and security

**Why here:** with no policies, any pod in any namespace can reach any other,
including stg talking to prod. Everything is done with the admin kubeconfig.
See [networking.md](../../notes/kubernetes/networking.md) and
[cluster.md](../../notes/kubernetes/cluster.md).

**Learn**
- `NetworkPolicy`: default-deny, then allow by namespace/pod selector. Flannel
  doesn't enforce policies itself; k3s's embedded network policy controller
  does.
- Traefik (k3s's bundled ingress): `IngressRoute`, middlewares, and
  `forwardedHeaders.trustedIPs`, which is the reason the tunnel bypasses it
  today.
- cert-manager: `Issuer` / `Certificate`, a DNS-01 challenge via Cloudflare.
- RBAC: `Role`/`ClusterRole`, bindings, a scoped ServiceAccount for CI or Argo;
  `kubectl auth can-i --as=...`.
- Pod security: `securityContext` (`runAsNonRoot`, `readOnlyRootFilesystem`,
  `capabilities.drop: [ALL]`, `seccompProfile`), and Pod Security Admission
  labels per namespace (`restricted`).
- (Later) Gateway API as Ingress's successor.

**Exercises**
- [ ] Default-deny ingress in `ziantsabit-prod`; allow only `cloudflared` → fe/be.
- [ ] Prove stg can no longer reach prod (`kubectl exec ... -- curl`).
- [ ] Allow be → Postgres / RustFS egress explicitly.
- [ ] Make every pod pass the `restricted` Pod Security level, then enforce it.
- [ ] Route staging through Traefik with a TLS cert from cert-manager,
      configuring `trustedIPs` first.
- [ ] A read-only kubeconfig for day-to-day `get`/`logs`.

**Done when:** each pod can reach only what it needs, and nothing runs as root.

---

## 7. In-cluster storage

**Why here:** Postgres and RustFS deliberately live outside the cluster, but
k3s's default `local-path` StorageClass ties a volume to one node. Worth
understanding before ever moving anything stateful in. See
[storage.md](../../notes/kubernetes/storage.md).

**Learn**
- Longhorn: replicated block volumes across the three nodes, snapshots,
  backups to S3 (RustFS).
- StorageClass parameters, `volumeBindingMode: WaitForFirstConsumer`, access
  modes, reclaim policies.
- StatefulSets with `volumeClaimTemplates`.
- Velero for backing up namespaces (objects + volumes).
- (Read about) operators like CloudNativePG for Postgres in-cluster, and the
  tradeoffs against the current external server.

**Exercises**
- [ ] Install Longhorn; create a PVC with 2 replicas.
- [ ] Write data, power off the node holding it, and check the pod comes back
      elsewhere with its data.
- [ ] Back a volume up to RustFS and restore it.
- [ ] Velero backup and restore of the `ziantsabit-stg` namespace.

**Done when:** you can say why Postgres should (or shouldn't) move in.

---

## Log

Notes, dates and gotchas as each step gets done. When a topic is understood
well enough to explain, move the explanation into
[`notes/kubernetes/`](../../notes/kubernetes/) and link it from here.

-
