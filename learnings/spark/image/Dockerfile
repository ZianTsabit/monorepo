# The official Spark image plus the S3A connector, so jobs can read and write
# RustFS as s3a://bucket/key.
#
# Why not `spark.jars.packages=org.apache.hadoop:hadoop-aws:...` at submit time:
# hadoop-aws pulls in the AWS SDK v2 `bundle` jar, ~650 MB, and every driver
# and executor pod would download it on every run. Baked in here, each node
# pulls it once with the image.
#
#   docker build -t ghcr.io/ziantsabit/spark-s3a:4.2.0 learnings/spark/image
#   docker push ghcr.io/ziantsabit/spark-s3a:4.2.0
#
# Versions must match what Spark was built against (spark-parent pom 4.2.0):
# Hadoop 3.5.0, AWS SDK v2 2.35.4. Bump all three together.
FROM spark:4.2.0-java25-python3

ARG HADOOP_VERSION=3.5.0
ARG AWS_SDK_VERSION=2.35.4

USER root
ADD --chown=spark:spark --chmod=644 \
    https://repo1.maven.org/maven2/org/apache/hadoop/hadoop-aws/${HADOOP_VERSION}/hadoop-aws-${HADOOP_VERSION}.jar \
    https://repo1.maven.org/maven2/software/amazon/awssdk/bundle/${AWS_SDK_VERSION}/bundle-${AWS_SDK_VERSION}.jar \
    /opt/spark/jars/
USER spark
