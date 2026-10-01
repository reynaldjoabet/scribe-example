#!/usr/bin/env python3
"""Summarise bench/run.sh results (cats vs ZIO logging) as Markdown on stdout, e.g. for $GITHUB_STEP_SUMMARY.

Usage: bench/report.py <results-dir> [--baseline LABEL --candidate LABEL] [--check] [--noise PCT]

  Reports medians across rounds for every <label>.jsonl in <results-dir>:
    - correctness: log lines with a missing/wrong requestId, for cats and ZIO
    - the cost of logging itself: each runtime minus its own no-logging baseline (cats - cats-baseline, zio - zio-baseline),
      since cats-effect and ZIO have different costs before any logging happens
    - the full per-scenario numbers for all four variants
  With --baseline/--candidate: the change per metric between two checkouts, marked better/worse only beyond the noise
  threshold. The *-baseline variants run no logging code, so their change shows how noisy the machine was.
  --check: exit 1 if cats or ZIO logged any line with a missing or wrong requestId.
"""
import argparse
import json
import statistics
import sys
from collections import defaultdict
from pathlib import Path

VARIANTS = ["cats-baseline", "cats", "zio-baseline", "zio"]
VARIANT_NOTES = {
    "cats-baseline": "cats-effect, no logging",
    "cats": "Log[IO] + LogContext",
    "zio-baseline": "ZIO, no logging",
    "zio": "ZIO.log* + logAnnotate -> ScribeZLogger",
}
RUNTIMES = [("cats", "cats-baseline"), ("zio", "zio-baseline")]

# metric -> (label, better, format, noise multiplier). Latency/throughput are noisier than CPU/allocation.
METRICS = {
    "requests": {
        "reqPerSec": ("req/s", "higher", "{:,.0f}", 2.0),
        "p50Ms": ("p50 ms", "lower", "{:.2f}", 2.0),
        "p99Ms": ("p99 ms", "lower", "{:.2f}", 3.0),
        "p999Ms": ("p99.9 ms", "lower", "{:.2f}", 4.0),
        "cpuUsPerReq": ("CPU µs/req", "lower", "{:.1f}", 1.0),
        "allocKbPerReq": ("alloc KB/req", "lower", "{:.1f}", 1.0),
        "gcCount": ("GC runs", "lower", "{:.0f}", 2.0),
        "gcMs": ("GC ms", "lower", "{:.0f}", 3.0),
        "dropped": ("dropped lines", "lower", "{:,.0f}", 1.0),
    },
    "debug-hot": {
        "nsPerCall": ("ns/call", "lower", "{:.1f}", 1.0),
        "bytesPerCall": ("bytes/call", "lower", "{:.1f}", 1.0),
    },
}
COMPARED = {"requests": ["reqPerSec", "p99Ms", "cpuUsPerReq", "allocKbPerReq"], "debug-hot": ["nsPerCall", "bytesPerCall"]}

# The cost of logging: (scenario, metric, label, format)
COSTS = [
    ("requests", "cpuUsPerReq", "CPU µs per request", "{:.1f}"),
    ("requests", "allocKbPerReq", "Allocated KB per request", "{:.1f}"),
    ("debug-hot", "nsPerCall", "Disabled debug: ns per call", "{:.1f}"),
    ("debug-hot", "bytesPerCall", "Disabled debug: bytes per call", "{:.1f}"),
]


def load(results_dir):
    """label -> scenario -> variant -> metric -> [values]"""
    data, env = {}, {}
    for path in sorted(Path(results_dir).glob("*.jsonl")):
        runs = defaultdict(lambda: defaultdict(lambda: defaultdict(list)))
        for line in path.read_text().splitlines():
            if line.strip():
                row = json.loads(line)
                env = {"cores": row["cores"], "java": row["java"]}
                for metric, value in row["metrics"].items():
                    runs[row["scenario"]][row["variant"]][metric].append(value)
        data[path.stem] = runs
    return data, env


def median(values):
    return statistics.median(values) if values else None


def fmt(value, pattern):
    return "–" if value is None else pattern.format(value)


def metric(runs, scenario, variant, name):
    return median(runs.get(scenario, {}).get(variant, {}).get(name, []))


def rounds_of(runs, scenario):
    return max((len(vals) for m in runs.get(scenario, {}).values() for vals in m.values()), default=0)


def verdict(change, better, noise):
    if change is None:
        return ""
    if abs(change) < noise:
        return "≈ within noise"
    improved = change < 0 if better == "lower" else change > 0
    return "✅ better" if improved else "⚠️ worse"


def correctness_table(runs):
    rows = ["| Variant | Lines checked | Missing requestId | Wrong requestId |", "|---|---:|---:|---:|"]
    for v, _ in RUNTIMES:
        if runs.get("verify", {}).get(v):
            rows.append(f"| `{v}` ({VARIANT_NOTES[v]}) | {fmt(metric(runs, 'verify', v, 'checked'), '{:,.0f}')} | "
                        f"{fmt(metric(runs, 'verify', v, 'missing'), '{:,.0f}')} | "
                        f"{fmt(metric(runs, 'verify', v, 'wrong'), '{:,.0f}')} |")
    return rows


def cost_table(runs):
    """Each runtime's logging variant minus its own no-logging baseline."""
    rows = ["| Cost of logging | `cats` | `zio` | Cheaper |", "|---|---:|---:|---|"]
    for scenario, name, label, pattern in COSTS:
        costs = {}
        for v, base in RUNTIMES:
            with_logging, without = metric(runs, scenario, v, name), metric(runs, scenario, base, name)
            costs[v] = None if with_logging is None or without is None else with_logging - without
        c, z = costs["cats"], costs["zio"]
        if c is None or z is None:
            cheaper = "–"
        elif min(c, z) <= 0 or abs(c - z) / max(c, z) < 0.10:
            cheaper = "≈ same"
        else:
            cheaper = f"cats, {z / c:.1f}× less" if c < z else f"zio, {c / z:.1f}× less"
        rows.append(f"| {label} | {fmt(c, pattern)} | {fmt(z, pattern)} | {cheaper} |")
    return rows


def scenario_table(runs, scenario):
    metrics = METRICS[scenario]
    rows = ["| Variant | " + " | ".join(label for label, *_ in metrics.values()) + " |",
            "|---|" + "---:|" * len(metrics)]
    for v in VARIANTS:
        if runs.get(scenario, {}).get(v):
            cells = [fmt(metric(runs, scenario, v, k), f) for k, (_, _, f, _) in metrics.items()]
            rows.append(f"| `{v}` | " + " | ".join(cells) + " |")
    return rows


def comparison_table(base_runs, cand_runs, noise):
    rows = ["| Scenario | Variant | Metric | Baseline | Candidate | Change | |", "|---|---|---|---:|---:|---:|---|"]
    for scenario, keys in COMPARED.items():
        for v in VARIANTS:
            for k in keys:
                label, better, pattern, mult = METRICS[scenario][k]
                b, c = metric(base_runs, scenario, v, k), metric(cand_runs, scenario, v, k)
                if b is None or c is None:
                    continue
                change = None if b == 0 else (c - b) / abs(b) * 100
                change_s = "–" if change is None else f"{change:+.1f}%"
                rows.append(f"| {scenario} | `{v}` | {label} | {fmt(b, pattern)} | {fmt(c, pattern)} | {change_s} | "
                            f"{verdict(change, better, noise * mult)} |")
    return rows


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("results_dir")
    parser.add_argument("--baseline")
    parser.add_argument("--candidate")
    parser.add_argument("--check", action="store_true")
    parser.add_argument("--noise", type=float, default=10.0, help="base noise threshold in %% (default 10)")
    args = parser.parse_args()

    data, env = load(args.results_dir)
    if not data:
        sys.exit(f"No *.jsonl results in {args.results_dir}")
    comparing = bool(args.baseline and args.candidate and args.baseline in data and args.candidate in data)
    labels = [args.baseline, args.candidate] if comparing else list(data)

    out = ["# Logging benchmark: cats (`Log[IO]` + `LogContext`) vs ZIO (`ZIO.log*` + `ScribeZLogger`)", "",
           f"Runner: {env.get('cores', '?')} cores, Java {env.get('java', '?')}. Medians across rounds. Both use the "
           "same workload (256 concurrent requests, 1 ms DB call, 2 INFO + 4 disabled DEBUG each) and the same output "
           "pipeline (JsonFormatter + AsyncStdoutWriter)."]

    if comparing:
        out += ["", f"## Change: `{args.baseline}` → `{args.candidate}`", "",
                f"Marked better/worse only beyond the noise threshold ({args.noise:g}% for CPU/allocation, higher for "
                "latency and throughput). `cats-baseline` and `zio-baseline` run no logging code, so their change shows how "
                "noisy this machine was during the run."]
        rounds = min(rounds_of(data[label], "requests") for label in labels)
        if rounds < 3:
            out += ["", f"> **Only {rounds} round(s):** medians of fewer than 3 rounds are unreliable; treat the "
                        "verdicts below as indicative."]
        out += [""] + comparison_table(data[args.baseline], data[args.candidate], args.noise)

    for label in labels:
        runs = data[label]
        out += ["", f"## `{label}`", "", "### Correctness", ""] + correctness_table(runs)
        out += ["", "### Cost of logging (each runtime minus its own no-logging baseline)", ""] + cost_table(runs)
        for scenario, title in [("requests", "Requests"), ("debug-hot", "Disabled debug in a hot loop")]:
            if runs.get(scenario):
                n = rounds_of(runs, scenario)
                out += ["", f"### {title} ({n} round{'s' if n != 1 else ''})", ""] + scenario_table(runs, scenario)

    print("\n".join(out))

    if args.check:
        runs = data[args.candidate] if comparing else data[labels[-1]]
        for v, _ in RUNTIMES:
            missing, wrong = metric(runs, "verify", v, "missing"), metric(runs, "verify", v, "wrong")
            if missing is None or wrong is None:
                sys.exit(f"--check: no correctness results for `{v}`")
            if missing + wrong > 0:
                sys.exit(f"--check: `{v}` logged {missing + wrong:,.0f} lines with a missing or wrong requestId")


if __name__ == "__main__":
    main()
