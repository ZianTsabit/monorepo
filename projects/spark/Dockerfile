# The official Spark image, plus the S3A connector so jobs can read and write
# RustFS as s3a://bucket/key, plus the job code under /opt/spark/jobs.
#
#   docker build -t ghcr.io/ziantsabit/spark-jobs:<version> projects/spark
#   docker push ghcr.io/ziantsabit/spark-jobs:<version>
#
# The tag is this project's version, not Spark's: a change to jobs/ is a new
# image and a tag bump in deployments/k8s/spark/apps/.
#
# Why not `spark.jars.packages=org.apache.hadoop:hadoop-aws:...` at submit time:
# hadoop-aws pulls in the AWS SDK v2 `bundle` jar, ~650 MB, and every driver
# and executor pod would download it on every run. Baked in here, each node
# pulls it once with the image -- and that layer comes before COPY jobs/, so
# a code-only change rebuilds and re-pulls just the small top layer.
#
# Versions must match what Spark was built against (spark-parent pom 4.2.0):
# Hadoop 3.5.0, AWS SDK v2 2.35.4. Bump all three together, and pyspark in
# pyproject.toml with them.
FROM spark:4.2.0-java25-python3

ARG HADOOP_VERSION=3.5.0
ARG AWS_SDK_VERSION=2.35.4

USER root
ADD --chown=spark:spark --chmod=644 \
    https://repo1.maven.org/maven2/org/apache/hadoop/hadoop-aws/${HADOOP_VERSION}/hadoop-aws-${HADOOP_VERSION}.jar \
    https://repo1.maven.org/maven2/software/amazon/awssdk/bundle/${AWS_SDK_VERSION}/bundle-${AWS_SDK_VERSION}.jar \
    /opt/spark/jars/
COPY --chown=spark:spark jobs/ /opt/spark/jobs/
USER spark
