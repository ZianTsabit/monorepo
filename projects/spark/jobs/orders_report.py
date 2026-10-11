"""Read orders CSV, build two reports, write them as Parquet.

The same file runs everywhere; only the paths change:

    # laptop, local files
    uv run jobs/orders_report.py data/orders.csv out/

    # cluster, RustFS (see deployments/k8s/spark/apps/ at the monorepo root)
    orders_report.py s3a://spark-lab/raw/orders.csv s3a://spark-lab/reports/

On the laptop there is no --master, so it runs in local[*] mode: one JVM, a
task slot per core. On the cluster the operator submits it with
--master k8s://..., and the same code fans out over executor pods.
"""

import argparse

from pyspark.sql import SparkSession
from pyspark.sql import functions as F
from pyspark.sql.types import (
    DoubleType,
    IntegerType,
    LongType,
    StringType,
    StructField,
    StructType,
    TimestampType,
)

# Declared rather than inferred: inferSchema reads the whole file once just to
# guess types, which is a second full scan of the input.
SCHEMA = StructType([
    StructField("order_id", LongType()),
    StructField("ordered_at", TimestampType()),
    StructField("country", StringType()),
    StructField("product", StringType()),
    StructField("quantity", IntegerType()),
    StructField("unit_price", DoubleType()),
])


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("input")
    parser.add_argument("output")
    args = parser.parse_args()
    output = args.output.rstrip("/")

    spark = SparkSession.builder.appName("orders-report").getOrCreate()

    # Transformations only -- nothing is read yet. Spark is building a plan.
    orders = (
        spark.read.csv(args.input, header=True, schema=SCHEMA)
        .withColumn("revenue", F.col("quantity") * F.col("unit_price"))
        .withColumn("month", F.date_format("ordered_at", "yyyy-MM"))
    )

    # groupBy needs every row of a key on one task, so this is a *shuffle*:
    # the stage boundary you'll see in the Spark UI and in explain() below.
    monthly = (
        orders.groupBy("month", "country")
        .agg(
            F.count("*").alias("orders"),
            F.sum("revenue").cast("decimal(14,2)").alias("revenue"),
        )
        .orderBy("month", F.desc("revenue"))
    )

    top_products = (
        orders.groupBy("product")
        .agg(
            F.sum("quantity").alias("units"),
            F.sum("revenue").cast("decimal(14,2)").alias("revenue"),
        )
        .orderBy(F.desc("revenue"))
    )

    monthly.explain()

    # Actions: each of these runs a job. Both re-read the CSV, because nothing
    # is cached -- try orders.cache() and compare the jobs in the UI.
    monthly.write.mode("overwrite").partitionBy("month").parquet(f"{output}/monthly_by_country")
    top_products.write.mode("overwrite").parquet(f"{output}/top_products")

    top_products.show(truncate=False)
    spark.stop()


if __name__ == "__main__":
    main()
