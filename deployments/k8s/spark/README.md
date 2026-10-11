# Kubernetes: spark

Spark jobs from [`projects/spark`](../../../projects/spark) on the home k3s
cluster, through the [Spark operator](https://github.com/kubeflow/spark-operator).
It's a learning lab ([`learnings/spark/`](../../../learnings/spark/)), so it has
one environment and no `base/` and `overlays/`.

```
kubectl apply SparkApplication
        │
        ▼
spark-operator controller ── runs spark-submit --master k8s://... ──► driver pod
                                                                        │ creates (via ServiceAccount spark)
                                                                        ▼
                                                     executor pod   executor pod
                                                        │               │
                                                        └──── s3a ──────┴──► rustfs Service ──► RustFS 192.168.0.111:9000
```

There's no long-running Spark cluster. The driver asks Kubernetes for
executors, and every pod goes away when the job ends. The operator's webhook
patches the pods with what `spark-submit` can't set itself: affinity,
`envFrom` and the priority class.

| Path | Holds |
| --- | --- |
| `operator/` | Namespace `spark-operator` and a k3s `HelmChart` that installs the operator (chart 2.5.2). k3s's built-in helm-controller runs it, so no `helm` CLI is needed |
| `lab/` | Namespace `spark`, where jobs run: `ResourceQuota` + `LimitRange`, `PriorityClass spark-batch`, the driver's ServiceAccount and Role, and a `rustfs` Service for the server outside the cluster |
| `apps/` | One `SparkApplication` per job. Each is a single run, applied by hand and not part of a kustomization |

## Room on the cluster

| Node | CPU | RAM | Used when written |
| --- | --- | --- | --- |
| k8s-cp1 | 2 | 2 GB | 69% (kept off-limits by node affinity) |
| k8s-w1 | 2 | 3 GB | ~30% |
| k8s-w2 | 2 | 3 GB | ~35% |

Three guardrails keep a job from hurting the site:
- **`ResourceQuota`** caps the `spark` namespace at 2 CPU and 3 GiB of memory
  requests, and 5 pods.
- **`PriorityClass spark-batch`** (value -100, never preempts) means the
  scheduler evicts Spark pods to place a site pod, never the reverse.
- **Node affinity** on every Spark pod (and the operator) keeps them off
  `k8s-cp1`.

A job is 3 pods × 896 MiB (512m heap plus Spark's minimum 384 MiB overhead).
It's enough for a few GB of input. More data means more executors, which
means more RAM on the workers.

Every command below is run from the monorepo root.

## First deploy

1. **Create a bucket and an access key in RustFS.** In the RustFS console,
   create the bucket `spark-lab` and an access key whose policy only allows
   that bucket. Keep it private: unlike the media buckets, nothing here is
   public-read.

2. **Upload the input data.** No CLI is needed on the laptop:

   ```bash
   (cd projects/spark && uv run jobs/make_data.py data/orders.csv --rows 1000000)
   docker run --rm -v "$PWD/projects/spark/data:/data" \
     -e AWS_ACCESS_KEY_ID=<key> -e AWS_SECRET_ACCESS_KEY=<secret> -e AWS_REGION=us-east-1 \
     amazon/aws-cli:2.37.12 --endpoint-url http://192.168.0.111:9000 \
     s3 cp /data/orders.csv s3://spark-lab/raw/orders.csv
   ```

3. **Build and push the image.** About 650 MB of it is the AWS SDK jar; see
   the [Dockerfile](../../../projects/spark/Dockerfile) for why it's baked in.

   ```bash
   docker build -t ghcr.io/ziantsabit/spark-jobs:0.1.0 projects/spark
   docker push ghcr.io/ziantsabit/spark-jobs:0.1.0
   ```

4. **Install the operator** and wait for its two pods:

   ```bash
   kubectl apply -k deployments/k8s/spark/operator
   kubectl -n kube-system logs -f job/helm-install-spark-operator
   kubectl -n spark-operator get pods
   ```

5. **Create the lab namespace, then the credentials Secret.** The Secret is
   made by hand and never committed:

   ```bash
   kubectl apply -k deployments/k8s/spark/lab
   kubectl -n spark create secret generic rustfs-spark \
     --from-literal=AWS_ACCESS_KEY_ID=<key> \
     --from-literal=AWS_SECRET_ACCESS_KEY=<secret>
   ```

## Running a job

```bash
kubectl apply -f deployments/k8s/spark/apps/orders-report.yaml
kubectl -n spark get sparkapp orders-report -w        # SUBMITTED → RUNNING → COMPLETED
kubectl -n spark get pods -w                           # watch the executors come and go
kubectl -n spark logs -f orders-report-driver

# Spark UI while it runs
kubectl -n spark port-forward pod/orders-report-driver 4040:4040   # http://localhost:4040

# Results
docker run --rm -e AWS_ACCESS_KEY_ID=<key> -e AWS_SECRET_ACCESS_KEY=<secret> -e AWS_REGION=us-east-1 \
  amazon/aws-cli:2.37.12 --endpoint-url http://192.168.0.111:9000 s3 ls --recursive s3://spark-lab/reports/
```

A SparkApplication runs once, so applying it again changes nothing. **To
re-run**, delete it first with `kubectl -n spark delete sparkapp
orders-report`, which also removes its pods. Finished ones are cleaned up
after a day (`timeToLiveSeconds`).

## Releasing new job code

The code is in the image, so a change to `projects/spark/jobs/` is a new
version. Build and push `spark-jobs:<version>`, set `image:` in the
SparkApplication, then delete and re-apply it. Upgrading Spark itself means
moving four version numbers together; see `projects/spark/CLAUDE.md`.

## When it doesn't work

| Symptom | Look at |
| --- | --- |
| No driver pod at all | `kubectl -n spark describe sparkapp orders-report` (events), and the operator controller's logs |
| Driver `Pending` | `kubectl -n spark describe pod orders-report-driver`: quota exceeded, or no worker with room |
| Driver runs, never gets executors | The driver log: `forbidden` means RBAC (`lab/rbac.yaml`); `exceeded quota` means the quota |
| `CreateContainerConfigError` | The `rustfs-spark` Secret is missing |
| `ImagePullBackOff` | The GHCR package is private: see the comment in `apps/orders-report.yaml` |
| `OOMKilled` | Exit code 137 in `describe pod`. Raise `memory` or `memoryOverhead`, and check the quota still fits |
| `ClassNotFoundException: ...S3AFileSystem` | The pod isn't running the `spark-jobs` image |
| `403` / `AccessDenied` from S3 | The key's policy doesn't cover `spark-lab` |
| Pods land on `k8s-cp1`, or ignore the Secret | The operator's webhook isn't running, so the pod patches never happened |

## Removing it

```bash
kubectl -n spark delete sparkapp --all
kubectl delete -k deployments/k8s/spark/lab
kubectl delete -k deployments/k8s/spark/operator   # helm-controller uninstalls the chart
```

Helm leaves the chart's CRDs behind after an uninstall. Find them with
`kubectl get crd | grep sparkoperator.k8s.io` and delete them by name.
