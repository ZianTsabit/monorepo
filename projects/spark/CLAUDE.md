# CLAUDE.md — spark

Guidance for this project of the monorepo; the monorepo-wide conventions are in the root `CLAUDE.md`. A learning project: PySpark jobs that run both on the laptop and on the k3s cluster (`deployments/k8s/spark/` at the monorepo root). The learning plan they serve is `learnings/spark/README.md`.

## One job file, two environments

A job takes its input and output as arguments and never sets `--master` or any storage settings in code. Locally that means `local[*]` and local paths; on the cluster the Spark operator supplies `--master k8s://...`, and the SparkApplication's `hadoopConf` supplies the S3A settings for `s3a://` paths. Keep it that way: a job that hardcodes either stops running in the other place.

## Versions move together

Four numbers have to agree, and nothing checks them:

- `pyspark` in `pyproject.toml`
- the `spark:` base image tag in `Dockerfile`
- `HADOOP_VERSION` and `AWS_SDK_VERSION` in `Dockerfile`, which must be what that Spark release was built against (the `spark-parent` pom on Maven Central lists both). A mismatched `hadoop-aws` fails at runtime with `NoSuchMethodError` or `ClassNotFoundException`, not at build time.
- `sparkVersion` in `deployments/k8s/spark/apps/*.yaml`

The image tag itself is this project's version, not Spark's: a change to `jobs/` is a new image and a tag bump in the SparkApplication, as with every image in the repo.

## Java

Spark 4.2 supports Java 17, 21 and 25. The machine's global JDK is newer, so `mise.toml` pins 25 for this directory; run things through mise (or from a shell where it's active), or PySpark fails to start the JVM.

## The S3A jars are baked in on purpose

`hadoop-aws` depends on the AWS SDK v2 `bundle` jar, about 650 MB. Pulling it with `spark.jars.packages` would download it in every driver and executor pod on every run. It's an image layer instead, placed before `COPY jobs/` so a code change only rebuilds the small top layer. `.dockerignore` keeps `data/` (which can be gigabytes) out of the build context.
