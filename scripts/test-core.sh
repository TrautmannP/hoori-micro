#!/usr/bin/env bash
# Pure configuration/discovery checks; NOT a substitute for Hoori/Docker integration.
set -euo pipefail
cd "$(dirname "$0")/.."
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
src=framework/src/main/java/hoori/micro
javac --release 21 -d "$work" "$src/Environment.java" "$src/ServiceName.java" \
  "$src/ServiceDirectory.java" "$src/ServiceConfig.java" \
  framework/src/test/java/hoori/micro/CoreChecks.java
java -cp "$work" hoori.micro.CoreChecks
