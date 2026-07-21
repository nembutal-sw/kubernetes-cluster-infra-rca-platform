"""Resource statistics used by Agent soak evaluation."""

from __future__ import annotations

import math
from typing import Any


def percentile(values: list[float], percentile_value: float) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    rank = (len(ordered) - 1) * percentile_value
    lower = math.floor(rank)
    upper = math.ceil(rank)
    if lower == upper:
        return ordered[lower]
    return ordered[lower] + (ordered[upper] - ordered[lower]) * (rank - lower)


def resource_trend(checkpoints: list[dict[str, Any]], key: str) -> dict[str, Any] | None:
    values = [
        item["process"][key]
        for item in checkpoints
        if isinstance(item.get("process"), dict) and key in item["process"]
    ]
    if not values:
        return None
    return {
        "sample_count": len(values),
        "initial": values[0],
        "final": values[-1],
        "minimum": min(values),
        "maximum": max(values),
        "growth": max(values) - values[0],
        "final_delta": values[-1] - values[0],
    }


def linear_slope_per_hour(samples: list[tuple[float, float]]) -> float | None:
    if len(samples) < 2:
        return None
    origin = samples[0][0]
    hours = [(timestamp - origin) / 3600 for timestamp, _ in samples]
    if any(current <= previous for previous, current in zip(hours, hours[1:])):
        return None
    mean_hour = sum(hours) / len(hours)
    mean_value = sum(value for _, value in samples) / len(samples)
    denominator = sum((hour - mean_hour) ** 2 for hour in hours)
    if denominator <= 0:
        return None
    return sum(
        (hour - mean_hour) * (value - mean_value)
        for hour, (_, value) in zip(hours, samples)
    ) / denominator


def consecutive_increase_count(values: list[float]) -> int:
    longest = 0
    current = 0
    for previous, value in zip(values, values[1:]):
        if value > previous:
            current += 1
            longest = max(longest, current)
        else:
            current = 0
    return longest


def rss_window_metrics(samples: list[tuple[float, float]]) -> dict[str, Any] | None:
    if not samples:
        return None
    values = [value for _, value in samples]
    return {
        "sample_count": len(samples),
        "initial": values[0],
        "final": values[-1],
        "minimum": min(values),
        "maximum": max(values),
        "range": max(values) - min(values),
        "final_delta": values[-1] - values[0],
        "slope_bytes_per_hour": linear_slope_per_hour(samples),
        "maximum_consecutive_increases": consecutive_increase_count(values),
    }


def rss_steady_state_metrics(
    checkpoints: list[dict[str, Any]],
    *,
    warmup_fraction: float,
    minimum_samples: int,
    interval_seconds: float,
) -> dict[str, Any] | None:
    raw_samples = []
    for index, item in enumerate(checkpoints):
        process = item.get("process")
        if not isinstance(process, dict) or "rss_bytes" not in process:
            continue
        sampled_at = process.get("sampled_at_monotonic")
        fallback_time = index * interval_seconds
        timestamp = float(sampled_at) if isinstance(sampled_at, (int, float)) else fallback_time
        raw_samples.append((timestamp, float(process["rss_bytes"])))
    if not raw_samples:
        return None
    if any(current[0] <= previous[0] for previous, current in zip(raw_samples, raw_samples[1:])):
        raw_samples = [(index * max(interval_seconds, 1.0), value) for index, (_, value) in enumerate(raw_samples)]
    warmup_samples = min(
        math.floor(len(raw_samples) * warmup_fraction),
        max(0, len(raw_samples) - minimum_samples),
    )
    steady_samples = raw_samples[warmup_samples:]
    result = rss_window_metrics(steady_samples)
    if result is None:
        return None
    result.update(
        {
            "warmup_fraction": warmup_fraction,
            "warmup_samples_excluded": warmup_samples,
            "minimum_samples_required": minimum_samples,
            "sufficient_samples": len(steady_samples) >= minimum_samples,
            "recent_windows": {
                "last_10": rss_window_metrics(raw_samples[-10:]),
                "last_30": rss_window_metrics(raw_samples[-30:]),
            },
        }
    )
    return result


def cpu_usage_metrics(checkpoints: list[dict[str, Any]]) -> dict[str, Any] | None:
    samples = []
    for item in checkpoints:
        process = item.get("process")
        if isinstance(process, dict) and "cpu_seconds" in process and "sampled_at_monotonic" in process:
            samples.append((float(process["sampled_at_monotonic"]), float(process["cpu_seconds"])))
    if len(samples) < 2:
        return None
    percentages = []
    for previous, current in zip(samples, samples[1:]):
        wall_delta = current[0] - previous[0]
        cpu_delta = current[1] - previous[1]
        if wall_delta <= 0 or cpu_delta < 0:
            continue
        percentages.append(cpu_delta / wall_delta * 100)
    if not percentages:
        return None
    return {
        "sample_count": len(percentages),
        "p50": percentile(percentages, 0.5),
        "p95": percentile(percentages, 0.95),
        "maximum": max(percentages),
    }
