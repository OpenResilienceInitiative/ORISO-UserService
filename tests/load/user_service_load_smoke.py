#!/usr/bin/env python3
"""Small dependency-free concurrent HTTP load smoke for a deployed UserService."""

from __future__ import annotations

import argparse
from concurrent.futures import ThreadPoolExecutor
from dataclasses import asdict, dataclass
import json
import math
import os
import statistics
import time
from typing import Iterable
from urllib.error import HTTPError, URLError
from urllib.parse import urljoin
from urllib.request import Request, urlopen


@dataclass(frozen=True)
class Sample:
    status: int
    response_bytes: int
    latency_ms: float
    error: str | None = None


def percentile(values: list[float], quantile: float) -> float:
    if not values:
        return 0.0
    ordered = sorted(values)
    index = max(0, math.ceil(quantile * len(ordered)) - 1)
    return ordered[index]


def request_once(url: str, timeout_seconds: float, headers: dict[str, str]) -> Sample:
    started = time.perf_counter()
    try:
        with urlopen(Request(url, headers=headers), timeout=timeout_seconds) as response:
            payload = response.read()
            return Sample(
                status=response.status,
                response_bytes=len(payload),
                latency_ms=(time.perf_counter() - started) * 1000,
            )
    except HTTPError as error:
        payload = error.read()
        return Sample(
            status=error.code,
            response_bytes=len(payload),
            latency_ms=(time.perf_counter() - started) * 1000,
            error=f"http-{error.code}",
        )
    except (TimeoutError, URLError, OSError) as error:
        return Sample(
            status=0,
            response_bytes=0,
            latency_ms=(time.perf_counter() - started) * 1000,
            error=type(error).__name__,
        )


def run_load(
    url: str,
    requests: int,
    concurrency: int,
    timeout_seconds: float,
    headers: dict[str, str] | None = None,
) -> tuple[dict[str, float | int], list[Sample]]:
    headers = headers or {}
    started = time.perf_counter()
    with ThreadPoolExecutor(max_workers=concurrency) as executor:
        samples = list(
            executor.map(
                lambda _: request_once(url, timeout_seconds, headers),
                range(requests),
            )
        )
    elapsed_seconds = time.perf_counter() - started
    latencies = [sample.latency_ms for sample in samples]
    failures = sum(
        sample.error is not None or not 200 <= sample.status < 400 for sample in samples
    )
    summary: dict[str, float | int] = {
        "requests": len(samples),
        "concurrency": concurrency,
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
    return summary, samples


def parse_headers(values: Iterable[str]) -> dict[str, str]:
    headers: dict[str, str] = {}
    for value in values:
        if ":" not in value:
            raise ValueError(f"Header must use 'Name: value' syntax: {value}")
        name, header_value = value.split(":", 1)
        headers[name.strip()] = header_value.strip()
    return headers


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Run a bounded concurrent smoke load against UserService."
    )
    parser.add_argument(
        "--base-url",
        default=os.environ.get("USERSERVICE_BASE_URL"),
        required=os.environ.get("USERSERVICE_BASE_URL") is None,
    )
    parser.add_argument("--path", default="/actuator/health")
    parser.add_argument("--requests", type=int, default=200)
    parser.add_argument("--concurrency", type=int, default=10)
    parser.add_argument("--timeout-seconds", type=float, default=5.0)
    parser.add_argument("--max-error-rate", type=float, default=0.0)
    parser.add_argument("--max-p95-ms", type=float, default=1000.0)
    parser.add_argument("--header", action="append", default=[])
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    if args.requests < 1 or args.concurrency < 1:
        raise ValueError("requests and concurrency must be positive")
    url = urljoin(args.base_url.rstrip("/") + "/", args.path.lstrip("/"))
    summary, samples = run_load(
        url,
        args.requests,
        args.concurrency,
        args.timeout_seconds,
        parse_headers(args.header),
    )
    output = {
        "target": url,
        "summary": summary,
        "errors": [
            asdict(sample) for sample in samples if sample.error is not None
        ][:10],
    }
    print(json.dumps(output, indent=2, sort_keys=True))
    return int(
        summary["error_rate"] > args.max_error_rate
        or summary["latency_p95_ms"] > args.max_p95_ms
    )


if __name__ == "__main__":
    raise SystemExit(main())
