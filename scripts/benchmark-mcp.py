#!/usr/bin/env python3
"""Sequential real MCP wall-clock benchmark. Stops on failure; never retries.

Caller owns server lifecycle. Use isolated ports and identical workloads for
before/after runs. Reports omit tokens, queries and result bodies.
"""
import argparse
import datetime
import hashlib
import json
import math
import os
import platform
import statistics
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path


def summarize(samples):
    values = sorted(s["wall_ms"] for s in samples if s["ok"])
    return {
        "calls": len(samples), "successes": len(values),
        "failures": len(samples) - len(values),
        "median_ms": round(statistics.median(values), 3) if values else None,
        "p95_ms": values[math.ceil(len(values) * .95) - 1] if values else None,
        "p95_method": "nearest rank, successes only; inspect failures separately",
        "p95_sample_warning": len(values) < 20,
    }


class MCP:
    def __init__(self, url, timeout):
        self.url, self.timeout = url, timeout
        self.protocol, self.session, self.request_id = "2025-11-25", None, 0

    def call(self, method, params=None, notification=False):
        self.request_id += 1
        payload = {"jsonrpc": "2.0", "method": method}
        if not notification:
            payload["id"] = self.request_id
        if params is not None:
            payload["params"] = params
        headers = {"Content-Type": "application/json",
                   "Accept": "application/json, text/event-stream",
                   "MCP-Protocol-Version": self.protocol}
        token = os.environ.get("MCP_BENCH_TOKEN")
        if token:
            headers["Authorization"] = "Bearer " + token
        if self.session:
            headers["Mcp-Session-Id"] = self.session
        request = urllib.request.Request(self.url, json.dumps(payload).encode(), headers)
        started = time.perf_counter_ns()
        try:
            with urllib.request.urlopen(request, timeout=self.timeout) as response:
                status = response.status
                self.session = response.headers.get("Mcp-Session-Id", self.session)
                if "text/event-stream" in response.headers.get("Content-Type", ""):
                    chunks = []
                    while True:
                        line = response.readline()
                        if not line:
                            raise RuntimeError("MCP stream ended without a response")
                        if line.startswith(b"data:"):
                            chunks.append(line[5:].strip())
                        elif not line.strip() and chunks:
                            raw = b"\n".join(chunks)
                            if json.loads(raw).get("id") == payload.get("id"):
                                break
                            chunks = []
                else:
                    raw = response.read()
        except urllib.error.HTTPError as error:
            status, raw = error.code, b""
            error.close()
        elapsed = (time.perf_counter_ns() - started) / 1e6
        return json.loads(raw) if raw else {}, {
            "wall_ms": round(elapsed, 3), "http_status": status,
            "matched_payload_bytes": len(raw)}


def places_from(message):
    result = message.get("result", {})
    if "error" in message or result.get("isError"):
        return None
    if isinstance(result.get("structuredContent"), list):
        return result["structuredContent"]
    for block in result.get("content", []):
        if block.get("type") == "text":
            try:
                places = json.loads(block["text"])
                if isinstance(places, list):
                    return places
            except (ValueError, KeyError):
                pass
    return None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--url", default="http://127.0.0.1:13000/mcp")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--label", required=True)
    parser.add_argument("--rounds", type=int, default=3)
    parser.add_argument("--timeout", type=float, default=120)
    parser.add_argument("--interval", type=float, default=3)
    state = parser.add_mutually_exclusive_group()
    state.add_argument("--cold-first", action="store_true",
                        help="Caller confirms worker is stopped at start")
    state.add_argument("--warm-first", action="store_true",
                       help="Caller confirms worker is already warm; otherwise first state is unknown")
    parser.add_argument("--show-results", action="store_true",
                        help="Print two public places for an actual live recording")
    parser.add_argument("--compare", type=Path)
    parser.add_argument("--discovery-only", action="store_true",
                        help="Initialize and list tools without contacting NAVER")
    args = parser.parse_args()
    if args.rounds < 1 or args.interval < 0 or args.timeout <= 0:
        parser.error("rounds/timeout must be positive; interval nonnegative")
    report = {
        "label": args.label,
        "started_utc": datetime.datetime.now(datetime.timezone.utc).isoformat(),
        "checkout_revision": subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip(),
        "dirty": bool(subprocess.check_output(["git", "status", "--porcelain"], text=True)),
        "environment": {"platform": platform.platform(), "cpu_count": os.cpu_count()},
        "rounds": args.rounds, "interval_seconds": args.interval, "samples": [],
        "schema_version": 2,
        "initial_worker_state": "cold" if args.cold_first else "warm" if args.warm_first else "unknown",
        "warm_state_assumption": "caller keeps worker alive between successful calls; interval below idle timeout",
        "method": "real tools/call; monotonic time around HTTP through complete response body",
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)

    def save():
        report["all"] = summarize(report["samples"])
        warm = [s for s in report["samples"] if s["worker_state"] == "warm"]
        report["warm"] = summarize(warm)
        report["by_workload"] = {
            name: summarize([s for s in warm if s["workload"] == name])
            for name in dict.fromkeys(s["workload"] for s in warm)}
        args.output.write_text(json.dumps(report, indent=2) + "\n")

    client = MCP(args.url, args.timeout)
    init, timing = client.call("initialize", {
        "protocolVersion": client.protocol, "capabilities": {},
        "clientInfo": {"name": "naver-live-latency-agent", "version": "1.0"}})
    if "result" not in init:
        raise RuntimeError("MCP initialization failed; check endpoint/authentication")
    client.protocol = init["result"]["protocolVersion"]
    client.call("notifications/initialized", notification=True)
    listing, list_timing = client.call("tools/list")
    names = [t["name"] for t in listing.get("result", {}).get("tools", [])]
    if not {"naver_map_search", "naver_map_coordinate_search"}.issubset(names):
        raise RuntimeError("Tool discovery did not return both search tools")
    report["discovery"] = {"initialize": timing, "tools_list": list_timing, "tools": names}
    print("MCP initialized; discovered:", ", ".join(names), flush=True)
    if args.discovery_only:
        save()
        print(json.dumps(report["discovery"], indent=2), flush=True)
        return 0
    workloads = [
        ("keyword_page1", "naver_map_search", {"query": "노원역 맛집", "page": 1}),
        ("keyword_page2", "naver_map_search", {"query": "노원역 맛집", "page": 2}),
        ("coordinate_page1", "naver_map_coordinate_search",
         {"query": "카페", "longitude": 126.9784, "latitude": 37.5667, "page": 1}),
        ("coordinate_page2", "naver_map_coordinate_search",
         {"query": "카페", "longitude": 126.9784, "latitude": 37.5667, "page": 2})]
    report["workload_sha256"] = hashlib.sha256(json.dumps(workloads, sort_keys=True).encode()).hexdigest()
    calls = ([workloads[0]] if args.cold_first else []) + workloads * args.rounds
    previous_ids = {}
    for index, (name, tool, arguments) in enumerate(calls):
        if index:
            time.sleep(args.interval)
        sample = {"index": index + 1, "workload": name,
                  "worker_state": report["initial_worker_state"] if index == 0 else "warm"}
        call_started = time.perf_counter_ns()
        try:
            message, timing = client.call("tools/call", {"name": tool, "arguments": arguments})
            sample.update(timing)
            places = places_from(message)
            ok = timing["http_status"] == 200 and places is not None
            if ok:
                ids = {str(p["id"]) for p in places if p.get("id")}
                sample["result_count"] = len(places)
                sample["ids_sha256"] = hashlib.sha256("\n".join(sorted(ids)).encode()).hexdigest()
                ok = len(places) >= 10 and len(ids) == len(places)
                key = name.split("_page")[0]
                if name.endswith("page1"):
                    previous_ids[key] = ids
                elif key in previous_ids:
                    sample["new_ids_vs_page1"] = len(ids - previous_ids[key])
                    ok = ok and bool(ids - previous_ids[key])
            sample["ok"] = ok
            if not ok:
                sample["error"] = "mcp_error" if "error" in message else (
                    "tool_error" if message.get("result", {}).get("isError") else "result_validation")
            if ok and args.show_results:
                print(json.dumps(places[:2], ensure_ascii=False, indent=2), flush=True)
        except Exception as error:
            sample.update(ok=False, error=type(error).__name__,
                          wall_ms=round((time.perf_counter_ns() - call_started) / 1e6, 3))
        report["samples"].append(sample)
        save()
        print(json.dumps(sample, ensure_ascii=False), flush=True)
        if not sample["ok"]:
            print("Stopped after failure; no retries or further NAVER probes.", flush=True)
            return 1
    if args.compare:
        before = json.loads(args.compare.read_text())
        reasons = []
        for field in ("schema_version", "rounds", "interval_seconds", "initial_worker_state", "workload_sha256", "environment"):
            if before.get(field) != report.get(field):
                reasons.append("mismatched_" + field)
        for label, run in (("baseline", before), ("candidate", report)):
            if run["all"]["failures"] or run["warm"]["successes"] == 0:
                reasons.append(label + "_incomplete_or_failed")
        before_sequence = [(s["workload"], s.get("worker_state")) for s in before["samples"]]
        after_sequence = [(s["workload"], s.get("worker_state")) for s in report["samples"]]
        if before_sequence != after_sequence:
            reasons.append("mismatched_sequence")
        comparable = not reasons
        report["comparison"] = {"baseline_label": before["label"], "comparable": comparable,
                                "reasons": reasons, "target_verified": False}
        if comparable:
            for metric in ("median_ms", "p95_ms"):
                report["comparison"][metric + "_reduction_percent"] = round(
                    100 * (1 - report["warm"][metric] / before["warm"][metric]), 2)
            report["comparison"]["by_workload"] = {
                name: {metric + "_reduction_percent": round(
                    100 * (1 - metrics[metric] / before["by_workload"][name][metric]), 2)
                    for metric in ("median_ms", "p95_ms")}
                for name, metrics in report["by_workload"].items()}
            report["comparison"]["target_verified"] = (
                report["warm"]["successes"] >= 20 and
                report["comparison"]["median_ms_reduction_percent"] >= 50 and
                report["comparison"]["p95_ms_reduction_percent"] >= 50)
    save()
    print(json.dumps({"warm": report["warm"], "comparison": report.get("comparison")}, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
