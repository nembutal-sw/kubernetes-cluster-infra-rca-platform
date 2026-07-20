import importlib.util
import json
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "scripts" / "llm-burn-in-report.py"


def load_module():
    spec = importlib.util.spec_from_file_location("llm_burn_in_report", SCRIPT)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    return module


def write_sample(
    root: Path,
    *,
    scenario: str,
    latency_ms: int,
    unsafe: bool = False,
) -> Path:
    directory = root / scenario
    directory.mkdir(parents=True)
    result_path = directory / "llm-staging-smoke-result.json"
    result_path.write_text(
        json.dumps(
            {
                "status": "passed",
                "started_at": "2026-07-21T00:00:00Z",
                "scenario": scenario,
                "report_id": f"report-{scenario}",
                "llm": {"provider": "gemini", "model": "gemini-3.1-flash-lite"},
                "llm_analysis": {
                    "status": "completed",
                    "latency_ms": latency_ms,
                    "usage": {
                        "usage_available": True,
                        "input_tokens": 100,
                        "output_tokens": 25,
                        "total_tokens": 125,
                        "estimated_cost_usd": 0,
                    },
                },
                "errors": [],
            }
        ),
        encoding="utf-8",
    )
    (directory / f"report-report-{scenario}.json").write_text(
        json.dumps(
            {
                "recommended_actions": [
                    {
                        "source": "llm",
                        "automation_allowed": unsafe,
                        "execution_plan": {"executable": unsafe},
                    }
                ]
            }
        ),
        encoding="utf-8",
    )
    return result_path


def test_insufficient_samples_retain_current_threshold(tmp_path: Path) -> None:
    burn_in = load_module()
    results = [
        write_sample(tmp_path, scenario="disk-pressure", latency_ms=2900),
        write_sample(tmp_path, scenario="node-not-ready", latency_ms=3100),
    ]

    report = burn_in.aggregate(
        results,
        minimum_samples=20,
        minimum_scenarios=5,
        current_p95_ms=60000,
    )

    assert report["status"] == "passed"
    assert report["readiness"] == "insufficient_samples"
    assert report["latency_ms"]["p95"] == 3100
    assert report["recommendation"]["decision"] == "retain_current_threshold"
    assert report["safety"]["unsafe_llm_action_count"] == 0


def test_ready_samples_support_existing_threshold(tmp_path: Path) -> None:
    burn_in = load_module()
    results = [
        write_sample(tmp_path, scenario="inode-exhaustion", latency_ms=2500),
        write_sample(tmp_path, scenario="network-link-flap", latency_ms=3500),
    ]

    report = burn_in.aggregate(
        results,
        minimum_samples=2,
        minimum_scenarios=2,
        current_p95_ms=60000,
    )

    assert report["readiness"] == "ready"
    assert report["recommendation"]["decision"] == "current_threshold_supported"
    assert report["usage"]["total_tokens"] == 250


def test_unsafe_llm_action_fails_burn_in(tmp_path: Path) -> None:
    burn_in = load_module()
    result = write_sample(
        tmp_path,
        scenario="inode-exhaustion",
        latency_ms=2500,
        unsafe=True,
    )

    report = burn_in.aggregate(
        [result],
        minimum_samples=1,
        minimum_scenarios=1,
        current_p95_ms=60000,
    )

    assert report["status"] == "failed"
    assert report["readiness"] == "failed"
    assert report["recommendation"]["decision"] == "investigate_failures"


def test_discover_results_deduplicates_files_and_directories(tmp_path: Path) -> None:
    burn_in = load_module()
    result = write_sample(tmp_path, scenario="disk-pressure", latency_ms=2900)

    discovered = burn_in.discover_results([str(tmp_path), str(result)])

    assert discovered == [result.resolve()]
