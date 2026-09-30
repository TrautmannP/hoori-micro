#!/usr/bin/env bash
# Real guest execution of the portable checks after scripts/build.sh; no HotSpot fallback.
set -euo pipefail
cd "$(dirname "$0")/.."
runtime="$PWD/.docker-context/runtime"
[[ -x "$runtime/bin/hoori" && -d framework/target/test-classes ]] || {
  echo 'Run scripts/build.sh first' >&2; exit 2;
}
python3 scripts/runtime_check.py "$runtime"
cp="$PWD/framework/target/classes:$PWD/framework/target/test-classes"
for jar in "$runtime"/lib/*.jar; do cp="$cp:$jar"; done
"$runtime/bin/hoori" run --engine "${HOORI_ENGINE:-mixed}" --class-path "$cp" hoori/micro/CoreChecks
"$runtime/bin/hoori" run --engine "${HOORI_ENGINE:-mixed}" --class-path "$cp" hoori/micro/AdmissionChecks
