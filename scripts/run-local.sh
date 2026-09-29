#!/usr/bin/env bash
# One terminal per role, started in this order: registry, recipes, shopping, gateway.
set -euo pipefail
cd "$(dirname "$0")/.."
case "${1:-}" in
  registry) export HOORI_MAIN_CLASS=hoori/micro/Registry HOORI_OUTBOUND=none port=8090 ;;
  recipes) export HOORI_MAIN_CLASS=dev/hoori/micro/demo/RecipesMain HOORI_OUTBOUND=http port=8081 ;;
  shopping) export HOORI_MAIN_CLASS=dev/hoori/micro/demo/ShoppingMain HOORI_OUTBOUND=http port=8082 ;;
  gateway) export HOORI_MAIN_CLASS=hoori/micro/Gateway HOORI_OUTBOUND=http port=8080
    export HOORI_GATEWAY_PERMISSIONS=${HOORI_GATEWAY_PERMISSIONS:-recipes:read,shopping:read,shopping:demo} ;;
  *) echo 'usage: scripts/run-local.sh registry|recipes|shopping|gateway (after scripts/build.sh)' >&2; exit 2 ;;
esac
export HOORI_HOME="$PWD/.docker-context/runtime"
export HOORI_APP_LIB="$PWD/.docker-context/apps/$1/lib"
export HOORI_BIND_ADDRESS=${HOORI_BIND_ADDRESS:-127.0.0.1}
export HOORI_PORT=${HOORI_PORT:-$port}
export HOORI_REGISTRY_URL=${HOORI_REGISTRY_URL:-http://127.0.0.1:8090}
export HOORI_ADVERTISE_URL=${HOORI_ADVERTISE_URL:-http://127.0.0.1:$HOORI_PORT}
exec "$PWD/docker/entrypoint.sh"
