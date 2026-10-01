#!/usr/bin/env bash
# Runs the cats vs ZIO logging benchmarks for one or more checkouts of this project and writes one JSON line per run to
# <out-dir>/<label>.jsonl. Summarise with bench/report.py.
#
#   cats-baseline  CatsProductionBenchmark baseline  cats-effect, same workload, no logging (cats baseline)
#   cats           CatsProductionBenchmark logging   Log[IO] + LogContext
#   zio-baseline   ZioProductionBenchmark baseline   ZIO, same workload, no logging (ZIO baseline)
#   zio            ZioProductionBenchmark logging    ZIO.log* + ZIO.logAnnotate -> ScribeZLogger
#
# Checkouts and variants are interleaved round by round, so everything compared sees the same machine conditions: on
# shared CI runners that matters more than the number of rounds.
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
  sed -n '2,19p' "$0"
  exit 2
fi

out=$1
rounds=$2
shift 2
mkdir -p "$out"

variants=(cats-baseline cats zio-baseline zio)
labels=()
dirs=()

for target in "$@"; do
  label=${target%%=*}
  dir=${target#*=}
  echo "==> Building $label ($dir)"
  (cd "$dir" && sbt --client "catsApp/Test/compile; zioApp/Test/compile; catsApp/benchClasspath; zioApp/benchClasspath")
  if [ -n "${CI:-}" ]; then
    (cd "$dir" && sbt --client shutdown) || true
  fi
  labels+=("$label")
  dirs+=("$dir")
  : >"$out/$label.jsonl"
done

JAVA_OPTS=${JAVA_OPTS:-"-Xms1g -Xmx1g"}

# run <index> <variant> <scenario> [verify]: one benchmark JVM for labels[index]; results go to its .jsonl
run() {
  local i=$1 variant=$2
  shift 2
  local module main mode
  case "$variant" in
    cats-baseline) module=catsApp main=app.logging.CatsProductionBenchmark mode=baseline ;;
    cats) module=catsApp main=app.logging.CatsProductionBenchmark mode=logging ;;
    zio-baseline) module=zioApp main=app.logging.ZioProductionBenchmark mode=baseline ;;
    zio) module=zioApp main=app.logging.ZioProductionBenchmark mode=logging ;;
    *)
      echo "Unknown variant: $variant" >&2
      return 1
      ;;
  esac
  local cp err
  cp=$(cat "${dirs[$i]}/target/bench-classpath-$module.txt")
  err=$(mktemp)
  # shellcheck disable=SC2086 # JAVA_OPTS is intentionally word-split
  if ! java $JAVA_OPTS -cp "$cp" "$main" "$mode" "$@" --json "$out/${labels[$i]}.jsonl" >/dev/null 2>"$err"; then
    cat "$err" >&2
    rm -f "$err"
    return 1
  fi
  grep -v '^WARNING' "$err" | sed "s/^/  [${labels[$i]}] /" >&2 || true
  rm -f "$err"
}

echo "==> Correctness (requestId on every log line)"
for v in cats zio; do
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
