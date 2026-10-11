# spark

PySpark jobs for learning Spark, runnable on the laptop and on the home k3s
cluster. The learning plan that goes with them is in
[`learnings/spark/`](../../learnings/spark/); the cluster manifests are in
[`deployments/k8s/spark/`](../../deployments/k8s/spark/).

Versions: Spark / PySpark **4.2.0**, Java **25**.

| Path | Holds |
| --- | --- |
| `jobs/make_data.py` | Generates a synthetic orders CSV (seeded, any size). Plain Python, no Spark |
| `jobs/orders_report.py` | The job: CSV → monthly revenue by country and top products → Parquet |
| `pyproject.toml`, `uv.lock` | PySpark, via uv |
| `mise.toml` | JDK 25 for this directory |
| `Dockerfile` | `ghcr.io/ziantsabit/spark-jobs`: official Spark image + S3A jars + `jobs/` |

## Run locally

From this directory:

```bash
mise install                    # JDK 25; Spark 4.2 doesn't run on the global JDK 27
uv run jobs/make_data.py data/orders.csv --rows 1000000
uv run jobs/orders_report.py data/orders.csv out/
uv run pyspark                  # interactive shell, `spark` already defined
```

With no `--master`, Spark runs in `local[*]` mode: one JVM with a task slot
per core. While a job runs, the Spark UI is at http://localhost:4040.

`data/` and `out/` are gitignored.

## Build the image

```bash
docker build -t ghcr.io/ziantsabit/spark-jobs:<version> projects/spark
docker push ghcr.io/ziantsabit/spark-jobs:<version>
```

Then set the tag in `deployments/k8s/spark/apps/` and follow
[its README](../../deployments/k8s/spark/README.md) to run it on the cluster.
