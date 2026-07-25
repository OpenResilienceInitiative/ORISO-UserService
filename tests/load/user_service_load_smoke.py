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
    operation: str = "request"


@dataclass(frozen=True)
class RequestSpec:
    name: str
    path: str
    weight: int = 1


def percentile(values: list[float], quantile: float) -> float:
    if not values:
        return 0.0
    ordered = sorted(values)
    index = max(0, math.ceil(quantile * len(ordered)) - 1)
    return ordered[index]


def request_once(
    url: str,
    timeout_seconds: float,
    headers: dict[str, str],
    operation: str = "request",
) -> Sample:
    started = time.perf_counter()
    try:
        with urlopen(
            Request(url, headers=headers), timeout=timeout_seconds
        ) as response:
            payload = response.read()
            return Sample(
                status=response.status,
                response_bytes=len(payload),
                latency_ms=(time.perf_counter() - started) * 1000,
                operation=operation,
            )
    except HTTPError as error:
        payload = error.read()
        return Sample(
            status=error.code,
            response_bytes=len(payload),
            latency_ms=(time.perf_counter() - started) * 1000,
            error=f"http-{error.code}",
            operation=operation,
        )
    except (TimeoutError, URLError, OSError) as error:
        return Sample(
            status=0,
            response_bytes=0,
            latency_ms=(time.perf_counter() - started) * 1000,
            error=type(error).__name__,
            operation=operation,
        )


def summarize_samples(
    samples: list[Sample],
    concurrency: int,
    elapsed_seconds: float,
) -> dict[str, float | int]:
    latencies = [sample.latency_ms for sample in samples]
    failures = sum(
        sample.error is not None or not 200 <= sample.status < 400 for sample in samples
    )
    return {
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
    summary = summarize_samples(samples, concurrency, elapsed_seconds)
    return summary, samples


def run_workload(
    base_url: str,
    request_specs: list[RequestSpec],
    requests: int,
    concurrency: int,
    timeout_seconds: float,
    headers: dict[str, str] | None = None,
) -> tuple[dict[str, object], list[Sample]]:
    if not request_specs:
        raise ValueError("request_specs must not be empty")
    if any(spec.weight < 1 for spec in request_specs):
        raise ValueError("request weights must be positive")

    headers = headers or {}
    weighted_specs = [spec for spec in request_specs for _ in range(spec.weight)]
    if requests < len(weighted_specs):
        raise ValueError(
            "requests must cover at least one complete weight cycle "
            f"({len(weighted_specs)} requests)"
        )
    scheduled_specs = [
        weighted_specs[index % len(weighted_specs)] for index in range(requests)
    ]
    started = time.perf_counter()
    with ThreadPoolExecutor(max_workers=concurrency) as executor:
        samples = list(
            executor.map(
                lambda spec: request_once(
                    urljoin(base_url.rstrip("/") + "/", spec.path.lstrip("/")),
                    timeout_seconds,
                    headers,
                    spec.name,
                ),
                scheduled_specs,
            )
        )
    elapsed_seconds = time.perf_counter() - started
    summary: dict[str, object] = summarize_samples(
        samples, concurrency, elapsed_seconds
    )
    summary["operations"] = {
        spec.name: summarize_samples(
            [sample for sample in samples if sample.operation == spec.name],
            concurrency,
            elapsed_seconds,
        )
        for spec in request_specs
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


def load_request_specs(path: str) -> list[RequestSpec]:
    with open(path, encoding="utf-8") as scenario_file:
        scenario = json.load(scenario_file)
    raw_requests = scenario.get("requests")
    if not isinstance(raw_requests, list) or not raw_requests:
        raise ValueError("scenario must contain a non-empty 'requests' list")

    request_specs = [
        RequestSpec(
            name=request["name"],
            path=request["path"],
            weight=request.get("weight", 1),
        )
        for request in raw_requests
    ]
    if len({spec.name for spec in request_specs}) != len(request_specs):
        raise ValueError("scenario request names must be unique")
    if any(not spec.name.strip() or not spec.path.strip() for spec in request_specs):
        raise ValueError("scenario request names and paths must not be blank")
    return request_specs


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
    parser.add_argument(
        "--scenario",
        help="JSON file containing weighted request names and paths",
    )
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
    headers = parse_headers(args.header)
    if args.scenario:
        summary, samples = run_workload(
            args.base_url,
            load_request_specs(args.scenario),
            args.requests,
            args.concurrency,
            args.timeout_seconds,
            headers,
        )
        target = args.base_url
    else:
        target = urljoin(args.base_url.rstrip("/") + "/", args.path.lstrip("/"))
        summary, samples = run_load(
            target,
            args.requests,
            args.concurrency,
            args.timeout_seconds,
            headers,
        )
    output = {
        "target": target,
        "summary": summary,
        "errors": [asdict(sample) for sample in samples if sample.error is not None][
            :10
        ],
    }
    print(json.dumps(output, indent=2, sort_keys=True))
    summaries_to_check = [summary]
    operations = summary.get("operations")
    if isinstance(operations, dict):
        summaries_to_check.extend(operations.values())
    return int(
        any(
            result["error_rate"] > args.max_error_rate
            or result["latency_p95_ms"] > args.max_p95_ms
            for result in summaries_to_check
        )
    )


if __name__ == "__main__":
    raise SystemExit(main())
