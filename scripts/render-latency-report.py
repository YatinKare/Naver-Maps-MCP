#!/usr/bin/env python3
"""Export charts and CSV from actual benchmark-mcp.py reports (requires matplotlib)."""
import argparse
import csv
import json
from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("baseline", type=Path)
    parser.add_argument("candidate", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    before, after = [json.loads(p.read_text()) for p in (args.baseline, args.candidate)]
    if before["all"]["failures"] or after["all"]["failures"]:
        parser.error("a failed run cannot produce a successful-latency comparison")
    args.output.mkdir(parents=True, exist_ok=True)
    colors = ["#4b86db", "#e5a33f"]
    fig, (aggregate, calls) = plt.subplots(2, 1, figsize=(10, 7), layout="constrained")
    for index, (label, data) in enumerate((("Fresh browser", before), ("Optimized", after))):
        values = [data["warm"][metric] / 1000 for metric in ("median_ms", "p95_ms")]
        bars = aggregate.bar([v + (index - .5) * .32 for v in range(2)], values,
                             width=.32, color=colors[index], label=label)
        aggregate.bar_label(bars, labels=[f"{v:.2f} s" for v in values], padding=4)
        samples = data["samples"]
        calls.plot([s["index"] for s in samples], [s["wall_ms"] / 1000 for s in samples],
                   "o-", markersize=4, color=colors[index], label=label)
    aggregate.set_xticks([0, 1], ["Warm median", "Warm p95 (nearest rank)"])
    aggregate.set_ylabel("End-to-end seconds")
    aggregate.set_ylim(0, max(before["warm"]["p95_ms"], after["warm"]["p95_ms"]) / 1000 * 1.25)
    aggregate.legend(loc="upper right")
    reductions = [100 * (1 - after["warm"][m] / before["warm"][m]) for m in ("median_ms", "p95_ms")]
    aggregate.set_title(f"Median −{reductions[0]:.1f}%  ·  p95 −{reductions[1]:.1f}%")
    calls.set_xlabel("Real MCP call index (cold call included when present)")
    calls.set_ylabel("End-to-end seconds")
    calls.set_ylim(bottom=0)
    calls.grid(axis="y", alpha=.2)
    calls.legend(loc="upper right")
    fig.suptitle(f"NAVER MCP · {before['warm']['calls']} warm calls per version · 4 workloads", fontsize=14)
    fig.savefig(args.output / "latency-comparison.png", dpi=160)
    fig.savefig(args.output / "latency-comparison.svg")
    plt.close(fig)
    fields = ["version", "index", "workload", "worker_state", "wall_ms", "http_status",
              "result_count", "new_ids_vs_page1", "ids_sha256", "ok"]
    with (args.output / "wall-clock.csv").open("w", newline="") as output:
        writer = csv.DictWriter(output, fields, extrasaction="ignore")
        writer.writeheader()
        for name, data in (("before", before), ("after", after)):
            for sample in data["samples"]:
                writer.writerow(dict(sample, version=name))
    html = '<!doctype html><html><meta charset="utf-8"><figure style="margin:0"><img style="width:100%;height:auto" src="' + str(
        (args.output / "latency-comparison.png").resolve()) + '" alt="Recorded live MCP wall-clock latency"></figure>'
    (args.output / "latency-chart.html").write_text(html + '</html>')
    print(json.dumps({"median_reduction_percent": reductions[0],
                      "p95_reduction_percent": reductions[1],
                      "chart": str(args.output / "latency-comparison.png")}, indent=2))


if __name__ == "__main__":
    main()
