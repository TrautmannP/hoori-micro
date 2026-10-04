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
"$runtime/bin/hoori" run --engine "${HOORI_ENGINE:-mixed}" --class-path "$cp" hoori/micro/ApplicationChecks
dto_cp="$PWD/examples/demo-contracts/target/classes:$PWD/examples/demo-contracts/target/test-classes:$cp"
for stress in normal gc-stress; do
  flags=()
  [[ "$stress" != gc-stress ]] || flags=(--gc-stress)
  PATH=/nonexistent JAVA_HOME=/nonexistent "$runtime/bin/hoori" run --engine "${HOORI_ENGINE:-mixed}" \
    "${flags[@]}" --allow-resource-read --class-path "$dto_cp" dev/hoori/micro/demo/CodecChecks
done
