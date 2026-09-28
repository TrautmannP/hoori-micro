#!/usr/bin/env bash
# Imports only a verified distribution; builds against actual SDK JARs, never local stubs.
set -euo pipefail
cd "$(dirname "$0")/.."
root=$PWD
runtime=${1:-${HOORI_DISTRIBUTION:-}}
if [[ -z "$runtime" ]]; then
  echo 'usage: scripts/build.sh /path/to/hoori/headless/distribution' >&2
  exit 2
fi
runtime=$(cd "$runtime" && pwd)
for command in python3 java javac mvn; do command -v "$command" >/dev/null; done
python3 scripts/runtime_check.py "$runtime"
# Upstream reuses SDK version 0.1.0 across commits. Isolate the build from stale ~/.m2 entries.
repo="$root/.cache/m2"
mkdir -p "$repo"
for artifact in hoori-guest-base hoori-http-api hoori-rest-api; do
  version=0.1.0
  [[ "$artifact" != hoori-guest-base ]] || version=0.4.0
  mvn --batch-mode --no-transfer-progress -q -Dmaven.repo.local="$repo" \
    org.apache.maven.plugins:maven-install-plugin:3.1.3:install-file \
    -Dfile="$runtime/lib/$artifact-$version.jar" -DgroupId=dev.hoori \
    -DartifactId="$artifact" -Dversion="$version" -Dpackaging=jar -DgeneratePom=true
 done
mvn --batch-mode --no-transfer-progress -Dmaven.repo.local="$repo" clean verify
python3 scripts/stage.py "$runtime"
echo 'Maven verification and Docker staging complete. Run: docker compose up --build --wait'
