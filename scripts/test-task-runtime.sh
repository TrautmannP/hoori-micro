#!/usr/bin/env bash
# Independent SDK-only consumer; no Micro classes, processor or optional DB SDKs.
set -euo pipefail
cd "$(dirname "$0")/.."
runtime="$PWD/.docker-context/runtime"
cp=$(python3 scripts/runtime_check.py "$runtime" --classpath)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
javac --release 21 -cp "$cp" -d "$work" framework/src/test/java/hoori/micro/TaskRuntimeProbe.java
for engine in interpreter mixed; do
  PATH=/nonexistent JAVA_HOME=/nonexistent "$runtime/bin/hoori" run --engine "$engine" \
    --allow-network-listen --allow-network-connect --class-path "$work:$cp" hoori/micro/TaskRuntimeProbe
done
