#!/usr/bin/env python3
"""Exercise authenticated tutorial writes and reads across UserService replicas."""

from __future__ import annotations

import argparse
from concurrent.futures import ThreadPoolExecutor
from dataclasses import asdict, dataclass
import json
import math
from pathlib import Path
import statistics
import time
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import Request, urlopen


USER_ID = "tutorial-replica-jwt-user"
SCOPE = {
    "surface": "frontend",
    "tourId": "consultant-walkthrough",
    "tourVersion": 1,
}


@dataclass(frozen=True)
class Sample:
    operation: str
    target: str
    status: int
    latency_ms: float
    response_bytes: int
    error: str | None = None


def percentile(values: list[float], quantile: float) -> float:
    ordered = sorted(values)
    if not ordered:
        return 0.0
    return ordered[max(0, math.ceil(quantile * len(ordered)) - 1)]


def summarize(samples: list[Sample], elapsed_seconds: float) -> dict[str, float | int]:
    latencies = [sample.latency_ms for sample in samples]
    failures = sum(sample.error is not None or sample.status != 200 for sample in samples)
    return {
        "requests": len(samples),
        "failures": failures,
        "error_rate": failures / len(samples) if samples else 1.0,
        "response_bytes": sum(sample.response_bytes for sample in samples),
        "elapsed_seconds": round(elapsed_seconds, 3),
        "requests_per_second": round(len(samples) / elapsed_seconds, 2),
        "latency_mean_ms": round(statistics.fmean(latencies), 2),
        "latency_p50_ms": round(percentile(latencies, 0.50), 2),
        "latency_p95_ms": round(percentile(latencies, 0.95), 2),
        "latency_max_ms": round(max(latencies), 2),
    }


def request(
    target: str,
    token: str,
    operation: str,
    payload: dict[str, object] | None,
    timeout_seconds: float,
) -> Sample:
    started = time.perf_counter()
    url = f"{target.rstrip('/')}/users/tutorials/progress"
    headers = {"Authorization": f"Bearer {token}"}
    data = None
    method = "GET"
    if payload is None:
        url = f"{url}?{urlencode({'surface': 'frontend'})}"
    else:
        data = json.dumps(payload, separators=(",", ":")).encode()
        method = "PUT"
        headers.update(
            {
                "Content-Type": "application/json",
                "Cookie": "CSRF-TOKEN=replica-proof",
                "X-CSRF-Token": "replica-proof",
            }
        )
    try:
        with urlopen(
            Request(url, data=data, headers=headers, method=method),
            timeout=timeout_seconds,
        ) as response:
            body = response.read()
            error = validate_read(body) if payload is None else None
            return Sample(
                operation,
                target,
                response.status,
                (time.perf_counter() - started) * 1000,
                len(body),
                error,
            )
    except HTTPError as error:
        body = error.read()
        return Sample(
            operation,
            target,
            error.code,
            (time.perf_counter() - started) * 1000,
            len(body),
            f"http-{error.code}",
        )
    except (TimeoutError, URLError, OSError, ValueError, json.JSONDecodeError) as error:
        return Sample(
            operation,
            target,
            0,
            (time.perf_counter() - started) * 1000,
            0,
            type(error).__name__,
        )


def validate_read(body: bytes) -> str | None:
    progress = json.loads(body)
    if not isinstance(progress, list):
        return "read-body-is-not-a-list"
    matching = [
        row
        for row in progress
        if all(row.get(key) == value for key, value in SCOPE.items())
    ]
    if len(matching) != 1:
        return f"canonical-scope-count-{len(matching)}"
    if matching[0].get("status") != "in_progress":
        return "unexpected-status"
    return None


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--replica-url", action="append", required=True)
    parser.add_argument("--token-file", type=Path, required=True)
    parser.add_argument("--requests", type=int, default=200)
    parser.add_argument("--concurrency", type=int, default=16)
    parser.add_argument("--timeout-seconds", type=float, default=5.0)
    parser.add_argument("--max-p95-ms", type=float, default=1000.0)
    args = parser.parse_args()
    if len(args.replica_url) != 2:
        raise ValueError("exactly two --replica-url values are required")
    if args.requests < 2 or args.concurrency < 1:
        raise ValueError("requests must be at least two and concurrency must be positive")

    token = args.token_file.read_text(encoding="utf-8").strip()
    writes = [
        (
            args.replica_url[index % len(args.replica_url)],
            {
                **SCOPE,
                "status": "in_progress",
                "currentStepId": "profile" if index % 2 == 0 else "messages",
            },
        )
        for index in range(args.requests)
    ]
    started = time.perf_counter()
    with ThreadPoolExecutor(max_workers=args.concurrency) as executor:
        write_samples = list(
            executor.map(
                lambda item: request(
                    item[0], token, "upsert", item[1], args.timeout_seconds
                ),
                writes,
            )
        )
    read_samples = [
        request(target, token, "read", None, args.timeout_seconds)
        for target in args.replica_url
    ]
    samples = [*write_samples, *read_samples]
    elapsed_seconds = time.perf_counter() - started
    summary: dict[str, object] = summarize(samples, elapsed_seconds)
    summary["operations"] = {
        operation: summarize(
            [sample for sample in samples if sample.operation == operation],
            elapsed_seconds,
        )
        for operation in ("upsert", "read")
    }
    summary["replicas"] = {
        target: summarize(
            [sample for sample in samples if sample.target == target],
            elapsed_seconds,
        )
        for target in args.replica_url
    }
    output = {
        "authenticated_user": USER_ID,
        "scope": SCOPE,
        "summary": summary,
        "errors": [asdict(sample) for sample in samples if sample.error is not None][
            :10
        ],
    }
    print(json.dumps(output, indent=2, sort_keys=True))
    checked = [
        summary,
        *summary["operations"].values(),
        *summary["replicas"].values(),
    ]
    return int(
        any(
            result["error_rate"] > 0
            or result["latency_p95_ms"] > args.max_p95_ms
            for result in checked
        )
    )


if __name__ == "__main__":
    raise SystemExit(main())
