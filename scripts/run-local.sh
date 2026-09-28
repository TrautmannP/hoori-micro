#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
case "${1:-}" in
  recipes) export HOORI_MAIN_CLASS=dev/hoori/micro/demo/RecipesMain HOORI_OUTBOUND=none ;;
  shopping) export HOORI_MAIN_CLASS=dev/hoori/micro/demo/ShoppingMain HOORI_OUTBOUND=http ;;
  *) echo 'usage: scripts/run-local.sh recipes|shopping (after scripts/build.sh)' >&2; exit 2 ;;
esac
export HOORI_HOME="$PWD/.docker-context/runtime"
export HOORI_APP_LIB="$PWD/.docker-context/apps/$1/lib"
export HOORI_BIND_ADDRESS=${HOORI_BIND_ADDRESS:-127.0.0.1}
exec "$PWD/docker/entrypoint.sh"
