#!/bin/sh
# All-in-one container entrypoint: 4 logical storage nodes in one small JVM, then the API in the foreground.
# JVM flags are tuned for a small host (measured: ~380 MB API + ~75 MB nodes).
set -e
DATA_DIR="${DATA_DIR:-/data}"
mkdir -p "$DATA_DIR/nodes" "$DATA_DIR/meta"

DATA_ROOT="$DATA_DIR/nodes" NODE_CAPACITY_BYTES="${NODE_CAPACITY_BYTES:-251658240}" \
  java -Xmx64m -XX:+UseSerialGC -cp /app/storage-node.jar com.vault.node.MultiNodeMain &

exec java -Xmx192m -Xss512k -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -XX:ReservedCodeCacheSize=40m \
  -XX:MaxMetaspaceSize=110m -XX:MaxDirectMemorySize=48m -XX:+ExitOnOutOfMemoryError \
  -Dspring.profiles.active=demo -DDATA_DIR="$DATA_DIR" -jar /app/vault-api.jar
