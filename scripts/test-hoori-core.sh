#!/usr/bin/env bash
# Real guest execution of the portable checks after scripts/build.sh; no HotSpot fallback.
set -euo pipefail
cd "$(dirname "$0")/.."
runtime="$PWD/.docker-context/runtime"
[[ -x "$runtime/bin/hoori" && -d framework/target/test-classes ]] || {
  echo 'Run scripts/build.sh first' >&2; exit 2;
}
cp="$PWD/framework/target/classes:$PWD/framework/target/test-classes:$(python3 scripts/runtime_check.py "$runtime" --classpath)"
"$runtime/bin/hoori" run --engine "${HOORI_ENGINE:-mixed}" --class-path "$cp" hoori/micro/CoreChecks
"$runtime/bin/hoori" run --engine "${HOORI_ENGINE:-mixed}" --class-path "$cp" hoori/micro/AdmissionChecks
"$runtime/bin/hoori" run --engine "${HOORI_ENGINE:-mixed}" --class-path "$cp" hoori/micro/TaskChecks
"$runtime/bin/hoori" run --engine "${HOORI_ENGINE:-mixed}" --class-path "$cp" hoori/micro/CapacityChecks
