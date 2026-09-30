#!/usr/bin/env bash
# Runs ProductionBenchmark (cats/src/test/scala/app/logging/ProductionBenchmark.scala) for one or more checkouts of
# this project and writes one JSON line per run to <out-dir>/<label>.jsonl. Summarise with bench/report.py.
#
# Checkouts are interleaved round by round (every variant of every checkout runs once per round), so a baseline and a
# candidate see the same machine conditions: on shared CI runners that matters more than the number of rounds.
#
# Usage: bench/run.sh <out-dir> <rounds> <label>=<project-dir> [<label>=<project-dir> ...]
#   bench/run.sh bench-results 3 current=.                        # one checkout
#   bench/run.sh bench-results 5 baseline=../baseline candidate=.  # compare two checkouts
#   python3 bench/report.py bench-results --baseline baseline --candidate candidate
#
# Env: JAVA_OPTS (default "-Xms1g -Xmx1g"). In CI (CI set), each sbt server is shut down after building so it doesn't
# compete with the benchmark JVMs; locally your running sbt server is left alone.
set -euo pipefail

if [ $# -lt 3 ]; then
  sed -n '2,13p' "$0"
  exit 2
fi

out=$1
rounds=$2
shift 2
mkdir -p "$out"

variants=(none custom scribe-cats-data scribe-cats-mdc)
labels=()
classpaths=()

for target in "$@"; do
  label=${target%%=*}
  dir=${target#*=}
  echo "==> Building $label ($dir)"
  (cd "$dir" && sbt --client "catsApp/Test/compile; catsApp/benchClasspath")
  if [ -n "${CI:-}" ]; then
    (cd "$dir" && sbt --client shutdown) || true
  fi
  labels+=("$label")
  classpaths+=("$(cat "$dir/target/bench-classpath.txt")")
  : >"$out/$label.jsonl"
done

JAVA_OPTS=${JAVA_OPTS:-"-Xms1g -Xmx1g"}

# run <index> <args...>: one benchmark JVM for labels[index]; results go to its .jsonl, the summary line to stderr
run() {
  local i=$1
  shift
  local err
  err=$(mktemp)
  # shellcheck disable=SC2086 # JAVA_OPTS is intentionally word-split
  if ! java $JAVA_OPTS -cp "${classpaths[$i]}" app.logging.ProductionBenchmark "$@" \
    --json "$out/${labels[$i]}.jsonl" >/dev/null 2>"$err"; then
    cat "$err" >&2
    rm -f "$err"
    return 1
  fi
  grep -v '^WARNING' "$err" | sed "s/^/  [${labels[$i]}] /" >&2 || true
  rm -f "$err"
}

echo "==> Correctness (requestId on every log line)"
for v in custom scribe-cats-data scribe-cats-mdc; do
  for i in "${!labels[@]}"; do run "$i" "$v" requests verify; done
done

for r in $(seq 1 "$rounds"); do
  echo "==> Round $r/$rounds: requests"
  for v in "${variants[@]}"; do
    for i in "${!labels[@]}"; do run "$i" "$v" requests; done
  done
  echo "==> Round $r/$rounds: disabled debug in a hot loop"
  for v in "${variants[@]}"; do
    for i in "${!labels[@]}"; do run "$i" "$v" debug-hot; done
  done
done

echo "==> Results in $out/: ${labels[*]/%/.jsonl}"
