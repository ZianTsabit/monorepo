# Workloads: the objects that run containers

Every one of these ends in **Pods**. They differ in *how many*, *for how long*,
and *with what identity*.

| Kind | Use when | Example |
| --- | --- | --- |
| Pod | Almost never directly | -- |
| Deployment | Long-running, interchangeable copies | web servers, APIs |
| StatefulSet | Each copy needs its own name and disk | databases, Kafka |
| DaemonSet | Exactly one copy per node | log shippers, node exporters |
| Job | Run to completion once | migrations, backfills |
| CronJob | Run to completion on a schedule | nightly backups |

## Pod

The smallest thing Kubernetes runs: one or more containers that share a network
address (they reach each other on `localhost`) and can share volumes.

Pods are **disposable**. A pod is never moved or restarted elsewhere -- it is
replaced by a new pod with a new name and a new IP. That's why you don't create
them yourself and why nothing should depend on a pod's IP (use a Service).

Things set at pod level, inside every workload's `template.spec`:

- `containers[]` -- image, args, env, ports, resources, probes.
- `initContainers[]` -- run to completion, in order, before the main containers start.
- `volumes[]` -- storage the containers can mount (see [storage.md](storage.md)).
- `securityContext` -- e.g. `runAsNonRoot`, `runAsUser`.
- `topologySpreadConstraints` / `affinity` / `nodeSelector` -- where it may be scheduled.
- `imagePullSecrets` -- credentials for a private registry.

### Probes

| Probe | Question it answers | When it fails |
| --- | --- | --- |
| `startupProbe` | Has the app finished starting? | Keeps the other probes off until it passes; kills the container if it never does |
| `livenessProbe` | Is it stuck? | Container is **restarted** |
| `readinessProbe` | Should it get traffic right now? | Pod is **removed from Services** (not restarted) |

`ziantsabit-be` uses all three: a long startup probe (JVM + Flyway can take
minutes), then liveness and readiness on Spring's health endpoints.

### Resources

```yaml
resources:
  requests: { cpu: 100m, memory: 512Mi }   # reserved; used to pick a node
  limits:   { memory: 768Mi }              # hard cap
```

- `requests` decide scheduling: a pod only lands on a node with that much unreserved.
- Going over the **memory limit** gets the container OOM-killed.
- Going over a **CPU limit** only throttles it -- which is why many setups set no CPU limit, as here.
- `100m` = 0.1 of a core. `Mi`/`Gi` are powers of two.

### Pod lifecycle states you'll see

`Pending` (not scheduled yet, or pulling the image) → `Running` → `Succeeded` / `Failed`.
Common trouble in `kubectl get pods`:

- `ImagePullBackOff` -- wrong image name/tag, or a private registry without `imagePullSecrets`.
- `CrashLoopBackOff` -- the container keeps exiting; read `kubectl logs --previous`.
- `CreateContainerConfigError` -- usually a referenced ConfigMap/Secret doesn't exist.
- `OOMKilled` -- hit its memory limit.

## Deployment

Runs N identical pods and keeps it at N. The default choice for anything
stateless. Example: [`ziantsabit-fe.yaml`](../../deployments/k8s/ziantsabit/ziantsabit-fe.yaml).

```yaml
spec:
  replicas: 2
  selector: { matchLabels: { app: ziantsabit-fe } }   # must match template labels
  template:                                          # the pod blueprint
    metadata: { labels: { app: ziantsabit-fe } }
    spec: { containers: [...] }
```

**Rollouts.** Changing anything in `template` (image tag, env, ...) triggers a
rolling update: new pods come up, old ones go away a few at a time, gated on
readiness. Tune with `spec.strategy`:

- `RollingUpdate` (default) -- `maxSurge` / `maxUnavailable` control the pace.
- `Recreate` -- kill all old pods first, then start new ones. For apps that
  can't run two versions at once; causes downtime.

```bash
kubectl rollout status deploy/ziantsabit-fe -n ziantsabit-prod
kubectl rollout undo deploy/ziantsabit-fe -n ziantsabit-prod     # back to previous version
kubectl rollout restart deploy/ziantsabit-be -n ziantsabit-prod  # fresh pods, same spec (e.g. after a ConfigMap edit)
```

**Replicas and state.** More replicas is only safe if pods don't hold state in
memory that must be shared. `ziantsabit-be` stays at 1 because its rate-limit
counters live in RAM -- each replica would count separately.

## ReplicaSet

What a Deployment actually creates: the object that keeps "N pods of this exact
template" running. Each rollout makes a new ReplicaSet and scales the old one
down; old ones are kept (scaled to 0) so `rollout undo` has something to go back
to. You see them in `kubectl get rs`, but you never write one.

## StatefulSet

Like a Deployment, but each pod has a **stable identity**:

- Names are ordered and fixed: `db-0`, `db-1`, `db-2` -- `db-0` comes back as `db-0`.
- Each gets its own disk via `volumeClaimTemplates`, which follows it across restarts.
- Pods start and stop in order.
- Paired with a *headless* Service (`clusterIP: None`) so each pod has its own
  DNS name: `db-0.db.<ns>.svc.cluster.local`.

Use for databases and clustered systems. If you'd run Postgres inside the
cluster, this is what it would be.

## DaemonSet

One pod on every node (or every node matching a selector), added automatically
when nodes join. For per-node agents: log collectors, monitoring exporters,
CNI and storage plugins. k3s runs several of its own components this way --
see `kubectl get ds -A`.

## Job

Runs pods until a set number **complete successfully**, retrying failures.
For one-off tasks: a data migration, a backfill.

```yaml
apiVersion: batch/v1
kind: Job
spec:
  backoffLimit: 3                 # retries before giving up
  ttlSecondsAfterFinished: 3600   # clean itself up an hour after finishing
  template:
    spec:
      restartPolicy: Never        # required: Never or OnFailure
      containers: [...]
```

## CronJob

Creates a Job on a cron schedule.

```yaml
apiVersion: batch/v1
kind: CronJob
spec:
  schedule: "0 3 * * *"           # 03:00 every day
  timeZone: Asia/Jakarta          # otherwise the controller's time zone (usually UTC)
  concurrencyPolicy: Forbid       # skip a run if the last is still going
  jobTemplate:
    spec: { template: { spec: {...} } }
```

Test one immediately: `kubectl create job --from=cronjob/<name> manual-run -n <ns>`.

## HorizontalPodAutoscaler (HPA)

Changes a Deployment's `replicas` automatically based on a metric, usually CPU
usage relative to `requests`. Needs metrics-server (k3s ships it).

```yaml
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
spec:
  scaleTargetRef: { apiVersion: apps/v1, kind: Deployment, name: ziantsabit-fe }
  minReplicas: 2
  maxReplicas: 5
  metrics:
    - type: Resource
      resource: { name: cpu, target: { type: Utilization, averageUtilization: 70 } }
```

Don't also set `replicas` in the Deployment you apply, or every apply resets it.

## PodDisruptionBudget (PDB)

Limits how many pods of a group can be taken down *voluntarily* at once --
node drains, cluster upgrades. It doesn't protect against crashes.

```yaml
apiVersion: policy/v1
kind: PodDisruptionBudget
spec:
  minAvailable: 1
  selector: { matchLabels: { app: ziantsabit-fe } }
```

With this, `kubectl drain` waits rather than evicting the last frontend pod. On
a single-replica Deployment, `minAvailable: 1` blocks drains forever -- use it
only where there are spares.
