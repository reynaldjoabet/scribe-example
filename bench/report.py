#!/usr/bin/env python3
"""Summarise bench/run.sh results as Markdown on stdout (e.g. for $GITHUB_STEP_SUMMARY).

Usage: bench/report.py <results-dir> [--baseline LABEL --candidate LABEL] [--check] [--noise PCT]

  Reports medians across rounds for every <label>.jsonl in <results-dir>:
    - correctness: log lines with a missing/wrong requestId, per variant
    - the request scenario and the disabled-debug scenario, per variant
    - the cost of logging itself (minus the no-logging baseline): custom vs scribe-cats
  With --baseline/--candidate: the change per metric, marked better/worse only beyond the noise threshold.
  The `none` variant runs no logging code, so its change shows how noisy the machine was.
  --check: exit 1 if the `custom` variant logged any line with a missing or wrong requestId.
"""
import argparse
import json
import statistics
import sys
from collections import defaultdict
from pathlib import Path

VARIANTS = ["none", "custom", "scribe-cats-data", "scribe-cats-mdc"]
VARIANT_NOTES = {
    "none": "no logging (baseline)",
    "custom": "Log[IO] + LogContext",
    "scribe-cats-data": "scribe.cats.io + data(...) per call",
    "scribe-cats-mdc": "scribe.cats.io + thread-local MDC",
}

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


def load(results_dir):
    """label -> scenario -> variant -> metric -> [values]"""
    data = {}
    env = {}
    for path in sorted(Path(results_dir).glob("*.jsonl")):
        runs = defaultdict(lambda: defaultdict(lambda: defaultdict(list)))
        for line in path.read_text().splitlines():
            if not line.strip():
                continue
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


def pct_change(base, cand):
    if base in (None, 0) or cand is None:
        return None
    return (cand - base) / abs(base) * 100


def verdict(change, better, noise):
    if change is None:
        return ""
    if abs(change) < noise:
        return "≈ within noise"
    improved = change < 0 if better == "lower" else change > 0
    return "✅ better" if improved else "⚠️ worse"


def correctness_table(runs):
    rows = ["| Variant | Lines checked | Missing requestId | Wrong requestId |", "|---|---:|---:|---:|"]
    for v in VARIANTS[1:]:
        m = runs.get("verify", {}).get(v)
        if not m:
            continue
        rows.append(f"| `{v}` ({VARIANT_NOTES[v]}) | {fmt(median(m['checked']), '{:,.0f}')} | "
                    f"{fmt(median(m['missing']), '{:,.0f}')} | {fmt(median(m['wrong']), '{:,.0f}')} |")
    return rows


def scenario_table(runs, scenario):
    metrics = METRICS[scenario]
    header = "| Variant | " + " | ".join(label for label, *_ in metrics.values()) + " |"
    rows = [header, "|---|" + "---:|" * len(metrics)]
    for v in VARIANTS:
        m = runs.get(scenario, {}).get(v)
        if not m:
            continue
        cells = [fmt(median(m.get(k, [])), f) for k, (_, _, f, _) in metrics.items()]
        rows.append(f"| `{v}` | " + " | ".join(cells) + " |")
    rounds = max((len(vals) for m in runs.get(scenario, {}).values() for vals in m.values()), default=0)
    return rows, rounds


def logging_cost(runs):
    """CPU and allocation spent on logging: each variant minus the no-logging baseline."""
    req = runs.get("requests", {})
    base = req.get("none")
    if not base:
        return []
    def cost(variant, metric):
        m = req.get(variant)
        return None if not m else median(m[metric]) - median(base[metric])
    rows = ["| Logging cost per request | `custom` | `scribe-cats-data` | custom saves |", "|---|---:|---:|---:|"]
    for metric, label, pattern in [("cpuUsPerReq", "CPU µs", "{:.1f}"), ("allocKbPerReq", "Allocated KB", "{:.1f}")]:
        c, s = cost("custom", metric), cost("scribe-cats-data", metric)
        saving = "–" if c is None or not s else f"{(1 - c / s) * 100:.0f}%"
        rows.append(f"| {label} | {fmt(c, pattern)} | {fmt(s, pattern)} | {saving} |")
    hot = runs.get("debug-hot", {})
    if hot.get("none") and hot.get("custom") and hot.get("scribe-cats-data"):
        for metric, label in [("nsPerCall", "Disabled debug ns/call"), ("bytesPerCall", "Disabled debug bytes/call")]:
            b = median(hot["none"][metric])
            c, s = median(hot["custom"][metric]) - b, median(hot["scribe-cats-data"][metric]) - b
            ratio = "–" if c <= 0 else f"{s / c:.0f}× less"
            rows.append(f"| {label} | {c:.1f} | {s:.1f} | {ratio} |")
    return rows


def comparison_table(base_runs, cand_runs, noise):
    rows = ["| Scenario | Variant | Metric | Baseline | Candidate | Change | |", "|---|---|---|---:|---:|---:|---|"]
    for scenario, keys in COMPARED.items():
        for v in VARIANTS:
            b, c = base_runs.get(scenario, {}).get(v), cand_runs.get(scenario, {}).get(v)
            if not b or not c:
                continue
            for k in keys:
                label, better, pattern, mult = METRICS[scenario][k]
                bm, cm = median(b.get(k, [])), median(c.get(k, []))
                change = pct_change(bm, cm)
                change_s = "–" if change is None else f"{change:+.1f}%"
                rows.append(f"| {scenario} | `{v}` | {label} | {fmt(bm, pattern)} | {fmt(cm, pattern)} | {change_s} | "
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
    labels = list(data)
    main_label = args.candidate or labels[-1]

    out = ["# Logging benchmark: `Log[IO]` + `LogContext` vs scribe-cats", ""]
    out.append(f"Runner: {env.get('cores', '?')} cores, Java {env.get('java', '?')}. Medians across rounds; "
               "every variant uses the same output pipeline (JsonLogFormat + AsyncStdoutWriter).")

    if args.baseline and args.candidate and args.baseline in data and args.candidate in data:
        out += ["", f"## Change: `{args.baseline}` → `{args.candidate}`", "",
                f"Marked better/worse only beyond the noise threshold ({args.noise:g}% for CPU/allocation, "
                "higher for latency and throughput). `none` runs no logging code, so its change shows how noisy "
                "this machine was during the run."]
        rounds = min(scenario_table(data[label], "requests")[1] for label in (args.baseline, args.candidate))
        if rounds < 3:
            out += ["", f"> **Only {rounds} round(s):** medians of fewer than 3 rounds are unreliable; treat the "
                        "verdicts below as indicative."]
        out += [""]
        out += comparison_table(data[args.baseline], data[args.candidate], args.noise)

    for label in ([args.baseline, args.candidate] if args.baseline and args.candidate else labels):
        if label not in data:
            continue
        runs = data[label]
        out += ["", f"## `{label}`", "", "### Correctness", ""]
        out += correctness_table(runs)
        cost = logging_cost(runs)
        if cost:
            out += ["", "### Cost of logging (minus the no-logging baseline)", ""] + cost
        for scenario, title in [("requests", "Requests: 256 concurrent fibers, 1 ms DB call, 2 INFO + 4 disabled DEBUG each"),
                                ("debug-hot", "Disabled `log.debug` in a hot loop")]:
            rows, rounds = scenario_table(runs, scenario)
            if len(rows) > 2:
                out += ["", f"### {title} ({rounds} round{'s' if rounds != 1 else ''})", ""] + rows

    print("\n".join(out))

    if args.check:
        m = data[main_label].get("verify", {}).get("custom")
        bad = (median(m["missing"]) or 0) + (median(m["wrong"]) or 0) if m else None
        if bad is None:
            sys.exit("--check: no correctness results for `custom`")
        if bad > 0:
            sys.exit(f"--check: `custom` logged {bad:,.0f} lines with a missing or wrong requestId")


if __name__ == "__main__":
    main()
