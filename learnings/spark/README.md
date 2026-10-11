# Spark lab

Learning Apache Spark in two phases: **locally** first, to learn Spark itself,
then **on the home k3s cluster**, to learn how it runs distributed. One job,
[`jobs/orders_report.py`](jobs/orders_report.py), runs unchanged in both. Only
the paths change: local files on the laptop, `s3a://` on RustFS in the
cluster.

Versions: Spark / PySpark **4.2.0**, Java **25**, Spark operator chart
**2.5.2**.

| Path | Holds |
| --- | --- |
| `jobs/make_data.py` | Generates a synthetic orders CSV (seeded, any size) |
| `jobs/orders_report.py` | The job: CSV → two aggregations → Parquet |
| `pyproject.toml`, `mise.toml` | Local setup: PySpark via uv, JDK 25 via mise |
| `image/Dockerfile` | Official Spark image plus the S3A jars, pushed as `ghcr.io/ziantsabit/spark-s3a:4.2.0` |
| `k8s/operator/` | The Spark operator, installed through k3s's built-in HelmChart controller |
| `kustomization.yaml` + `k8s/lab/` | The `spark` namespace: quota, priority, RBAC, the `rustfs` Service, and the job code as a ConfigMap |
| `k8s/apps/orders-report.yaml` | One run of the job as a `SparkApplication` |

These manifests are a learning lab, so they live here rather than in
`deployments/k8s/`, which holds what the site runs on.

---

## Phase 1: Spark on the laptop

Everything about the Spark API is the same in `local[*]` mode, and it's much
faster to iterate on than the cluster.

```bash
cd learnings/spark
mise install                    # JDK 25; Spark 4.2 doesn't run on the global JDK 27
uv run jobs/make_data.py data/orders.csv --rows 1000000
uv run jobs/orders_report.py data/orders.csv out/
uv run pyspark                  # interactive shell, `spark` already defined
```

While a job runs, the Spark UI is at http://localhost:4040. It disappears when
the job ends; in `pyspark` it stays up for the life of the shell.

### Concepts and exercises

Work through these in the `pyspark` shell or by editing the job:

- [ ] **DataFrames and lazy evaluation.** Transformations (`select`,
      `filter`, `groupBy`) only build a plan. Actions (`count`, `show`,
      `write`) run it. Time a chain of transformations, then the `count()`
      at the end.
- [ ] **Read the plan.** Look at `df.explain()` and `df.explain("formatted")`.
      Find the `Exchange` lines: each one is a shuffle.
- [ ] **Jobs, stages and tasks** in the Spark UI. Match each `Exchange` to a
      stage boundary, and each task to a partition.
- [ ] **Partitions.** Try `df.rdd.getNumPartitions()`, `repartition(n)` vs
      `coalesce(n)`, and `spark.sql.shuffle.partitions`. The default is 200,
      which is far too many for this data; see whether adaptive query
      execution (AQE) shrinks it.
- [ ] **Skew.** `make_data.py` puts 40% of orders in `ID`. Group by country
      and find the slow task in the stage's task list.
- [ ] **Caching.** Add `orders.cache()` and compare the two write jobs in the
      UI before and after.
- [ ] **Spark SQL.** `orders.createOrReplaceTempView("orders")`, then
      rewrite the report with `spark.sql("...")` and compare the plans. They
      should be identical.
- [ ] **Joins.** Build a small `products` DataFrame (category, cost) and join
      it in. Find `BroadcastHashJoin` in the plan, then turn broadcasting off
      (`spark.sql.autoBroadcastJoinThreshold=-1`) and compare.
- [ ] **File formats.** Compare the CSV vs Parquet size and read time. Filter
      the Parquet output on `month` and look for `PartitionFilters` in the plan
      (partition pruning).
- [ ] **Window functions.** Rank countries by revenue within each month.
- [ ] **UDFs.** Write a Python UDF, then the same thing with built-in
      functions, and time both. Then try a pandas UDF.

---

## Phase 2: Spark on the cluster

### How it works

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
patches the pods with what `spark-submit` can't set itself: the affinity,
`envFrom`, the priority class and the mounted job code.

### Room on the cluster

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
- **Node affinity** keeps every Spark pod off `k8s-cp1`.

A job is 3 pods × 896 MiB (512m heap plus Spark's minimum 384 MiB overhead).
It's enough for a few GB of input. More data means more executors, which
means more RAM on the workers.

### One-time setup

All commands run from the monorepo root.

1. **Create a bucket and an access key in RustFS.** In the RustFS console,
   create the bucket `spark-lab` and an access key whose policy only allows
   that bucket. Keep it private: unlike the media buckets, nothing here is
   public-read.

2. **Upload the input data.** No CLI is needed on the laptop:

   ```bash
   uv run --project learnings/spark learnings/spark/jobs/make_data.py learnings/spark/data/orders.csv --rows 1000000
   docker run --rm -v "$PWD/learnings/spark/data:/data" \
     -e AWS_ACCESS_KEY_ID=<key> -e AWS_SECRET_ACCESS_KEY=<secret> -e AWS_REGION=us-east-1 \
     amazon/aws-cli:2.37.12 --endpoint-url http://192.168.0.111:9000 \
     s3 cp /data/orders.csv s3://spark-lab/raw/orders.csv
   ```

3. **Build and push the image** (about 650 MB of it is the AWS SDK jar; see
   the Dockerfile for why it's baked in):

   ```bash
   docker build -t ghcr.io/ziantsabit/spark-s3a:4.2.0 learnings/spark/image
   docker push ghcr.io/ziantsabit/spark-s3a:4.2.0
   ```

4. **Install the operator** and wait for its two pods:

   ```bash
   kubectl apply -k learnings/spark/k8s/operator
   kubectl -n kube-system logs -f job/helm-install-spark-operator
   kubectl -n spark-operator get pods
   ```

5. **Create the lab namespace, then the credentials Secret.** The Secret is
   made by hand and never committed:

   ```bash
   kubectl apply -k learnings/spark
   kubectl -n spark create secret generic rustfs-spark \
     --from-literal=AWS_ACCESS_KEY_ID=<key> \
     --from-literal=AWS_SECRET_ACCESS_KEY=<secret>
   ```

### Running a job

```bash
kubectl apply -f learnings/spark/k8s/apps/orders-report.yaml
kubectl -n spark get sparkapp orders-report -w        # SUBMITTED → RUNNING → COMPLETED
kubectl -n spark get pods -w                           # watch the executors come and go
kubectl -n spark logs -f orders-report-driver

# Spark UI while it runs
kubectl -n spark port-forward pod/orders-report-driver 4040:4040   # http://localhost:4040

# Results
docker run --rm -e AWS_ACCESS_KEY_ID=<key> -e AWS_SECRET_ACCESS_KEY=<secret> -e AWS_REGION=us-east-1 \
  amazon/aws-cli:2.37.12 --endpoint-url http://192.168.0.111:9000 s3 ls --recursive s3://spark-lab/reports/
```

**To re-run**, delete the SparkApplication and apply it again. **After
editing the job code**, re-apply the lab first (`kubectl apply -k
learnings/spark`) so the ConfigMap updates. The image only changes when Spark
or the jars do.

### When it doesn't work

| Symptom | Look at |
| --- | --- |
| No driver pod at all | `kubectl -n spark describe sparkapp orders-report` (events), and the operator controller's logs |
| Driver `Pending` | `kubectl -n spark describe pod orders-report-driver`: quota exceeded, or no worker with room |
| Driver runs, never gets executors | The driver log: `forbidden` means RBAC (`k8s/lab/rbac.yaml`); `exceeded quota` means the quota |
| `CreateContainerConfigError` | The `rustfs-spark` Secret is missing |
| `ImagePullBackOff` | The GHCR package is private: see the comment in `orders-report.yaml` |
| `OOMKilled` | Exit code 137 in `describe pod`. Raise `memory` or `memoryOverhead`, and check the quota still fits |
| `ClassNotFoundException: ...S3AFileSystem` | The pod isn't running the `spark-s3a` image |
| `403` / `AccessDenied` from S3 | The key's policy doesn't cover `spark-lab` |
| `/opt/spark/jobs/...` not found | Webhook not running (volume never mounted), or the ConfigMap wasn't re-applied |

### Cluster exercises

- [ ] Run the job and match each pod to its role in the Spark UI's
      **Executors** tab.
- [ ] `kubectl get pod orders-report-driver -o yaml`: find what the operator
      and webhook added (env, volumes, affinity, owner references).
- [ ] Delete an executor pod mid-job and watch Spark replace it and retry
      the lost tasks.
- [ ] Raise `executor.instances` to 4 and watch the quota refuse the extra
      pods. Where does the error show up?
- [ ] Remove a verb from the Role (e.g. `delete`) and see what breaks.
- [ ] **Dynamic allocation**: `spec.dynamicAllocation` with
      `shuffleTrackingEnabled`, min 0, max 2. Watch executors scale with the
      stages.
- [ ] Generate 10M rows and compare 1 executor vs 2. Where does it stop
      scaling?
- [ ] **Scheduled runs**: turn it into a `ScheduledSparkApplication` that runs
      nightly.

---

## Phase 3: going further

- **History server**: set `spark.eventLog.enabled=true` and
      `spark.eventLog.dir=s3a://spark-lab/events/`, then run a Spark History
      Server Deployment reading the same path. You get the UI for finished
      jobs, not just running ones.
- **Spark Connect**: a long-running Connect server in the cluster, with
      `pyspark` on the laptop as a thin client
      (`SparkSession.builder.remote("sc://...")`).
- **Table formats**: Apache Iceberg or Delta Lake on RustFS, for ACID
      writes, schema evolution and time travel on top of Parquet.
- **Structured Streaming**: a streaming version of the report, with files
      landing in `s3a://spark-lab/incoming/` as the source.
- **Monitoring**: once Prometheus is up (step 4 of the [k3s plan](../k3s/)),
      enable Spark's `PrometheusServlet` metrics and the operator's
      PodMonitor.
- **GitOps**: once Argo CD or Flux is in (step 2 of the k3s plan), manage the
      operator and lab from git like everything else.

---

## Log

Notes, dates and gotchas as phases get done.

- 2026-10-11: lab written. Ran `orders_report.py` locally on 200k rows
  (PySpark 4.2.0, JDK 25). Cluster side rendered and server-side dry-run only,
  not yet applied.
