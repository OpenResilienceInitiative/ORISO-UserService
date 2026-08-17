#!/usr/bin/env python3
"""Capture and compare bounded UserService outbound dependency metrics."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any
from urllib.error import HTTPError
from urllib.parse import urlencode
from urllib.request import urlopen

CALLS_METRIC = "userservice.outbound.http.calls"
LATENCY_METRIC = "userservice.outbound.http.latency"
PAYLOAD_METRIC = "userservice.outbound.http.payload"
FALLBACK_METRIC = "userservice.dependency.fallbacks"


def metric_measurements(
    base_url: str,
    metric_name: str,
    tags: dict[str, str],
    timeout_seconds: float = 5.0,
) -> dict[str, float]:
    query = urlencode([("tag", f"{name}:{value}") for name, value in tags.items()])
    url = f"{base_url.rstrip('/')}/actuator/metrics/{metric_name}?{query}"
    try:
        with urlopen(url, timeout=timeout_seconds) as response:
            payload = json.load(response)
    except HTTPError as error:
        if error.code == 404:
            return {}
        raise
    return {
        measurement["statistic"]: float(measurement["value"])
        for measurement in payload.get("measurements", [])
    }


def capture_snapshot(
    base_urls: list[str],
    dependency: str = "127.0.0.1",
) -> dict[str, Any]:
    return {
        "dependency": dependency,
        "targets": {
            base_url: {
                "calls": metric_measurements(
                    base_url,
                    CALLS_METRIC,
                    {
                        "dependency": dependency,
                        "method": "get",
                    },
                ),
                "latency": metric_measurements(
                    base_url,
                    LATENCY_METRIC,
                    {
                        "dependency": dependency,
                        "method": "get",
                    },
                ),
                "response_payload": metric_measurements(
                    base_url,
                    PAYLOAD_METRIC,
                    {
                        "dependency": dependency,
                        "direction": "response",
                    },
                ),
                "fallbacks": metric_measurements(
                    base_url,
                    FALLBACK_METRIC,
                    {
                        "dependency": "agency-service",
                        "operation": "consultant-agency-batch",
                    },
                ),
            }
            for base_url in base_urls
        },
    }


def _measurement(
    snapshot: dict[str, Any],
    target: str,
    metric: str,
    statistic: str,
) -> float:
    return float(
        snapshot.get("targets", {}).get(target, {}).get(metric, {}).get(statistic, 0)
    )


def _delta(
    before: dict[str, Any],
    after: dict[str, Any],
    target: str,
    metric: str,
    statistic: str,
) -> float:
    return max(
        0.0,
        _measurement(after, target, metric, statistic)
        - _measurement(before, target, metric, statistic),
    )


def compare_snapshots(
    before: dict[str, Any],
    after: dict[str, Any],
    load_result: dict[str, Any],
    *,
    max_calls_per_consultant_read: float,
    max_mean_latency_ms: float,
    max_response_bytes_per_call: float,
    expected_fallback: bool = False,
) -> tuple[dict[str, Any], list[str]]:
    targets = sorted(set(before.get("targets", {})) | set(after.get("targets", {})))
    outbound_calls = sum(
        _delta(before, after, target, "calls", "COUNT") for target in targets
    )
    latency_count = sum(
        _delta(before, after, target, "latency", "COUNT") for target in targets
    )
    latency_total_seconds = sum(
        _delta(before, after, target, "latency", "TOTAL_TIME") for target in targets
    )
    latency_max_seconds = max(
        (_measurement(after, target, "latency", "MAX") for target in targets),
        default=0.0,
    )
    payload_count = sum(
        _delta(before, after, target, "response_payload", "COUNT") for target in targets
    )
    response_bytes = sum(
        _delta(before, after, target, "response_payload", "TOTAL") for target in targets
    )
    max_response_bytes = max(
        (_measurement(after, target, "response_payload", "MAX") for target in targets),
        default=0.0,
    )
    fallback_count = sum(
        _delta(before, after, target, "fallbacks", "COUNT") for target in targets
    )
    operations = load_result.get("summary", {}).get("operations", {})
    consultant_reads = sum(
        int(summary.get("requests", 0))
        for name, summary in operations.items()
        if name.startswith("consultant-profile")
    )

    calls_per_consultant_read = (
        outbound_calls / consultant_reads if consultant_reads else 0.0
    )
    latency_mean_ms = (
        latency_total_seconds * 1000 / latency_count if latency_count else 0.0
    )
    response_bytes_per_call = response_bytes / payload_count if payload_count else 0.0
    fallbacks_per_consultant_read = (
        fallback_count / consultant_reads if consultant_reads else 0.0
    )
    report = {
        "targets": targets,
        "consultant_reads": consultant_reads,
        "outbound_calls": int(outbound_calls),
        "calls_per_consultant_read": round(calls_per_consultant_read, 4),
        "latency_measurements": int(latency_count),
        "latency_mean_ms": round(latency_mean_ms, 2),
        "latency_max_ms": round(latency_max_seconds * 1000, 2),
        "response_payload_measurements": int(payload_count),
        "response_bytes": int(response_bytes),
        "response_bytes_per_call": round(response_bytes_per_call, 2),
        "response_bytes_max": int(max_response_bytes),
        "fallbacks": int(fallback_count),
        "fallbacks_per_consultant_read": round(fallbacks_per_consultant_read, 4),
    }

    violations: list[str] = []
    if consultant_reads <= 0:
        violations.append("load result did not contain consultant-profile reads")
    if outbound_calls <= 0:
        violations.append("load did not exercise the outbound AgencyService dependency")
    if latency_count != outbound_calls:
        violations.append(
            "outbound latency measurements do not match call attempts "
            f"({latency_count:g} != {outbound_calls:g})"
        )
    if not expected_fallback and payload_count != outbound_calls:
        violations.append(
            "known response-payload measurements do not match successful calls "
            f"({payload_count:g} != {outbound_calls:g})"
        )
    if expected_fallback and payload_count > outbound_calls:
        violations.append(
            "response-payload measurements exceeded call attempts "
            f"({payload_count:g} > {outbound_calls:g})"
        )
    if expected_fallback and fallback_count != outbound_calls:
        violations.append(
            "application fallback measurements do not match failed call attempts "
            f"({fallback_count:g} != {outbound_calls:g})"
        )
    if not expected_fallback and fallback_count != 0:
        violations.append(
            "healthy dependency run unexpectedly used the application fallback "
            f"({fallback_count:g} fallbacks)"
        )
    if calls_per_consultant_read > max_calls_per_consultant_read:
        violations.append(
            "outbound calls per consultant read exceeded the bound "
            f"({calls_per_consultant_read:.4f} > "
            f"{max_calls_per_consultant_read:.4f})"
        )
    if latency_mean_ms > max_mean_latency_ms:
        violations.append(
            "outbound mean latency exceeded the bound "
            f"({latency_mean_ms:.2f} ms > {max_mean_latency_ms:.2f} ms)"
        )
    if response_bytes_per_call > max_response_bytes_per_call:
        violations.append(
            "outbound response bytes per call exceeded the bound "
            f"({response_bytes_per_call:.2f} > "
            f"{max_response_bytes_per_call:.2f})"
        )
    return report, violations


def _load_json(path: str) -> dict[str, Any]:
    with Path(path).open(encoding="utf-8") as input_file:
        return json.load(input_file)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Capture and compare UserService outbound dependency metrics."
    )
    commands = parser.add_subparsers(dest="command", required=True)

    capture = commands.add_parser("capture")
    capture.add_argument("--base-url", action="append", required=True)
    capture.add_argument("--dependency", default="127.0.0.1")
    capture.add_argument("--output", required=True)

    compare = commands.add_parser("compare")
    compare.add_argument("--before", required=True)
    compare.add_argument("--after", required=True)
    compare.add_argument("--load-result", required=True)
    compare.add_argument("--max-calls-per-consultant-read", type=float, default=1.0)
    compare.add_argument("--max-mean-latency-ms", type=float, default=500.0)
    compare.add_argument("--max-response-bytes-per-call", type=float, default=4096.0)
    compare.add_argument("--expected-fallback", action="store_true")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    if args.command == "capture":
        snapshot = capture_snapshot(args.base_url, args.dependency)
        with Path(args.output).open("w", encoding="utf-8") as output_file:
            json.dump(snapshot, output_file, indent=2, sort_keys=True)
            output_file.write("\n")
        return 0

    report, violations = compare_snapshots(
        _load_json(args.before),
        _load_json(args.after),
        _load_json(args.load_result),
        max_calls_per_consultant_read=args.max_calls_per_consultant_read,
        max_mean_latency_ms=args.max_mean_latency_ms,
        max_response_bytes_per_call=args.max_response_bytes_per_call,
        expected_fallback=args.expected_fallback,
    )
    print(json.dumps({"report": report, "violations": violations}, indent=2))
    return int(bool(violations))


if __name__ == "__main__":
    raise SystemExit(main())
