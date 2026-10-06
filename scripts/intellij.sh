#!/usr/bin/env bash
# Shared IntelliJ configurations delegate to the same verified CLI workflows.
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ -f .idea/hoori.env ]]; then
  set -a
  source .idea/hoori.env
  set +a
fi
if [[ -n "${HOORI_JAVA21_HOME:-}" ]]; then
  export JAVA_HOME="$HOORI_JAVA21_HOME"
  export PATH="$JAVA_HOME/bin:$PATH"
fi

usage() {
  echo 'usage: scripts/intellij.sh build [core|task-facade|local-data]' >&2
  echo '       scripts/intellij.sh run registry|recipes|pantry|shopping|gateway|mvc-crud|task-facade|local-data' >&2
  echo '       scripts/intellij.sh test core|http|mvc|task-facade|local-data [test options]' >&2
  exit 2
}

action=${1:-}
target=${2:-core}
case "$action:$target" in
  build:core)
    exec scripts/build.sh "${HOORI_DISTRIBUTION:-$PWD/.docker-context/runtime}" ;;
  build:task-facade|build:local-data)
    exec python3 scripts/optional_example.py build "$target" ;;
  run:registry|run:recipes|run:pantry|run:shopping|run:gateway|run:mvc-crud)
    python3 scripts/runtime_check.py "$PWD/.docker-context/runtime" >/dev/null
    exec scripts/run-local.sh "$target" ;;
  run:task-facade|run:local-data)
    exec python3 scripts/optional_example.py run "$target" ;;
  test:*)
    [[ $# -ge 2 ]] || usage
    shift 2
    case "$target" in
      core) exec scripts/test-hoori-core.sh "$@" ;;
      http) exec python3 scripts/test_http.py "$@" ;;
      mvc) exec python3 scripts/test_mvc.py "$@" ;;
      task-facade) exec python3 scripts/test_composition.py --facade "$@" ;;
      local-data) exec python3 scripts/test_data.py --hoori-checkout "${HOORI_CHECKOUT:-$PWD/../hoori}" "$@" ;;
      *) usage ;;
    esac ;;
  *) usage ;;
esac
