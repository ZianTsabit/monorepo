# Spark learning plan

Learning Apache Spark in phases: **locally** first, to learn Spark itself,
then **on the home k3s cluster**, to learn how it runs distributed.

| Where | What |
| --- | --- |
| [`projects/spark/`](../../projects/spark/) | The code: `make_data.py` (synthetic orders CSV), `orders_report.py` (the job), the image. How to run it locally is in its README |
| [`deployments/k8s/spark/`](../../deployments/k8s/spark/) | The cluster side: Spark operator, `spark` namespace with its guardrails, the SparkApplication. Setup and run steps are in its README |

One job, `orders_report.py`, runs unchanged in both places. Only the paths
change: local files on the laptop, `s3a://` on RustFS in the cluster.

| # | Phase | Status |
| --- | --- | --- |
| 1 | [Spark on the laptop](#phase-1-spark-on-the-laptop) | ☐ |
| 2 | [Spark on the cluster](#phase-2-spark-on-the-cluster) | ☐ |
| 3 | [Going further](#phase-3-going-further) | ☐ |

---

## Phase 1: Spark on the laptop

**Why first:** everything about the Spark API is the same in `local[*]` mode,
and it's much faster to iterate on than the cluster, with no memory limits to
fight. Setup is in [`projects/spark/README.md`](../../projects/spark/README.md).
Work in `uv run pyspark` or by editing the job, with the Spark UI open at
http://localhost:4040.

**Exercises**
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

**Done when:** you can predict where a query's stage boundaries will be
before running it.

---

## Phase 2: Spark on the cluster

**Why:** this is where the distributed side shows up. A driver is a
Kubernetes client that creates its own executor pods, which need RBAC, fit
under a quota and get scheduled. Setup and run steps are in
[`deployments/k8s/spark/README.md`](../../deployments/k8s/spark/README.md).

**Learn**
- How `spark-submit --master k8s://` works: the driver pod, executor pods, the
  headless Service the executors use to call back to the driver.
- What the Spark operator adds: the `SparkApplication` CRD, its controller,
  and its mutating webhook.
- What the driver's ServiceAccount needs (`lab/rbac.yaml`), and why.
- How `ResourceQuota`, `PriorityClass` and node affinity interact with
  pods that Spark creates itself.
- S3A: how Spark reads and writes object storage, and what `hadoopConf` sets.

**Exercises**
- [ ] Do the first deploy and run the job. Match each pod to its role in the
      Spark UI's **Executors** tab.
- [ ] `kubectl get pod orders-report-driver -o yaml`: find what the operator
      and webhook added (env, affinity, priority, owner references).
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
- [ ] Release a code change the proper way: new image tag, bump, re-run.
- [ ] **Scheduled runs**: turn it into a `ScheduledSparkApplication` that runs
      nightly.

**Done when:** you can explain every pod that appears during a run, and
what would happen if each one died.

---

## Phase 3: Going further

- [ ] **History server**: set `spark.eventLog.enabled=true` and
      `spark.eventLog.dir=s3a://spark-lab/events/`, then run a Spark History
      Server Deployment reading the same path. You get the UI for finished
      jobs, not just running ones.
- [ ] **Spark Connect**: a long-running Connect server in the cluster, with
      `pyspark` on the laptop as a thin client
      (`SparkSession.builder.remote("sc://...")`).
- [ ] **Table formats**: Apache Iceberg or Delta Lake on RustFS, for ACID
      writes, schema evolution and time travel on top of Parquet.
- [ ] **Structured Streaming**: a streaming version of the report, with files
      landing in `s3a://spark-lab/incoming/` as the source.
- [ ] **Monitoring**: once Prometheus is up (step 4 of the [k3s plan](../k3s/)),
      enable Spark's `PrometheusServlet` metrics and the operator's
      PodMonitor.
- [ ] **GitOps**: once Argo CD or Flux is in (step 2 of the k3s plan), manage
      `deployments/k8s/spark/` from git like everything else.

---

## Log

Notes, dates and gotchas as phases get done.

- 2026-10-11: code and manifests written. Ran `orders_report.py` locally on
  200k rows (PySpark 4.2.0, JDK 25). Cluster side rendered and server-side
  dry-run only, not yet applied.
