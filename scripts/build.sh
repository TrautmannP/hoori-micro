#!/usr/bin/env bash
# Imports only a verified distribution; builds against actual SDK JARs, never local stubs.
set -euo pipefail
cd "$(dirname "$0")/.."
runtime=${1:-${HOORI_DISTRIBUTION:-}}
if [[ -z "$runtime" ]]; then
  echo 'usage: scripts/build.sh /path/to/hoori/headless/distribution' >&2
  exit 2
fi
runtime=$(cd "$runtime" && pwd)
for command in python3 java javac mvn; do command -v "$command" >/dev/null; done
# Original SDK POMs, isolated by the complete distribution identity (versions are reused upstream).
repo=$(python3 scripts/runtime_check.py "$runtime" --install)
mvn --batch-mode --no-transfer-progress -Dmaven.repo.local="$repo" clean verify
mvn --batch-mode --no-transfer-progress -Dmaven.repo.local="$repo" -f starter/pom.xml \
  org.apache.maven.plugins:maven-dependency-plugin:3.8.1:copy-dependencies \
  -DincludeScope=runtime -DexcludeGroupIds=dev.hoori -DoutputDirectory=target/lib
python3 scripts/stage.py "$runtime"
echo 'Maven verification and Docker staging complete. Run: docker compose up --build --wait'
