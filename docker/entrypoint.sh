#!/bin/sh
# Real Hoori only. Keep this process replaced with exec so signals reach the runtime.
set -eu
home=${HOORI_HOME:-/opt/hoori}
app_lib=${HOORI_APP_LIB:-/opt/app/lib}
: "${HOORI_MAIN_CLASS:?Set the guest main class, in slash notation}"
classpath=
for jar in "$app_lib"/*.jar "$home"/lib/*.jar; do
  [ -f "$jar" ] || { echo "Missing JARs in $app_lib or $home/lib" >&2; exit 2; }
  classpath=${classpath:+$classpath:}$jar
done
set -- run --engine "${HOORI_ENGINE:-mixed}" --live-output --graceful-signals \
  --max-heap-bytes "${HOORI_MAX_HEAP_BYTES:-33554432}" \
  --allow-environment-read --allow-network-listen --class-path "$classpath"
case "${HOORI_OUTBOUND:-none}" in
  none) ;;
  http) set -- "$@" --allow-network-connect --allow-host-resolution ;;
  *) echo 'HOORI_OUTBOUND must be none or http' >&2; exit 2 ;;
esac
if [ -n "${HOORI_TLS_CA_FILE:-}" ]; then
  [ -r "$HOORI_TLS_CA_FILE" ] || { echo 'Cannot read HOORI_TLS_CA_FILE' >&2; exit 2; }
  set -- "$@" --tls-ca-file "$HOORI_TLS_CA_FILE"
fi
exec "$home/bin/hoori" "$@" "$HOORI_MAIN_CLASS"
