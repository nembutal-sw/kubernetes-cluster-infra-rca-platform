#!/usr/bin/env python3
"""Validate blind RCA evidence/label separation without running the analyzer."""

from __future__ import annotations

import json
import re
import sys
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[1]
EVIDENCE_PATH = ROOT / "web-console/src/test/resources/analysis/blind-evaluation-evidence.json"
LABEL_PATH = ROOT / "web-console/src/test/resources/analysis/blind-evaluation-labels.json"
FORBIDDEN_INPUT_FIELDS = {
    "expected",
    "expected_signals",
    "allowed_signals",
    "forbidden_signals",
    "label",
    "labels",
    "root_cause",
    "description",
    "alert_name",
    "category",
    "class",
}
SECRET_MARKER = re.compile(
    r"authorization|bearer\s|api[_-]?key|agent[_-]?token|node[_-]?token|password|private[_-]?key",
    re.IGNORECASE,
)


def load(path: Path) -> tuple[str, dict[str, Any]]:
    raw = path.read_text(encoding="utf-8")
    return raw, json.loads(raw)


def visit(value: Any, path: str, failures: list[str], text_values: set[str]) -> None:
    if isinstance(value, dict):
        for key, child in value.items():
            if key.lower() in FORBIDDEN_INPUT_FIELDS:
                failures.append(f"blind evidence contains forbidden field {path}.{key}")
            visit(child, f"{path}.{key}", failures, text_values)
    elif isinstance(value, list):
        for index, child in enumerate(value):
            visit(child, f"{path}[{index}]", failures, text_values)
    elif isinstance(value, str):
        text_values.add(value)


def main() -> int:
    failures: list[str] = []
    raw_evidence, evidence_set = load(EVIDENCE_PATH)
    raw_labels, label_set = load(LABEL_PATH)
    cases = evidence_set.get("cases", [])
    labels = label_set.get("labels", [])

    if evidence_set.get("schema_version") != "rca-blind-evidence-set/v1":
        failures.append("unexpected blind evidence schema version")
    if label_set.get("schema_version") != "rca-blind-label-set/v1":
        failures.append("unexpected blind label schema version")
    if evidence_set.get("provenance", {}).get("contains_raw_customer_data") is not False:
        failures.append("blind evidence must declare contains_raw_customer_data=false")
    if label_set.get("provenance", {}).get("contains_raw_customer_data") is not False:
        failures.append("blind labels must declare contains_raw_customer_data=false")
    if SECRET_MARKER.search(raw_evidence) or SECRET_MARKER.search(raw_labels):
        failures.append("blind corpus contains a secret marker")
    if len(cases) < 18:
        failures.append("blind evidence requires at least 18 cases")

    evidence_ids: list[str] = []
    platforms: set[str] = set()
    runtimes: set[str] = set()
    text_values: set[str] = set()
    for index, case in enumerate(cases):
        case_id = str(case.get("case_id", ""))
        evidence_ids.append(case_id)
        if not re.fullmatch(r"blind-[0-9]{3}", case_id):
            failures.append(f"invalid blind case ID at index {index}: {case_id}")
        if not isinstance(case.get("collectors"), dict) or not case["collectors"]:
            failures.append(f"{case_id} collectors must be a non-empty object")
        platforms.add(str(case.get("platform", "")))
        runtimes.add(str(case.get("runtime", "")))
        visit(case, f"cases[{index}]", failures, text_values)
    if len(evidence_ids) != len(set(evidence_ids)):
        failures.append("blind evidence case IDs must be unique")
    if len(platforms) < 8:
        failures.append("blind evidence must cover at least eight platform shapes")
    if not {"containerd", "crio", "embedded-containerd"}.issubset(runtimes):
        failures.append("blind evidence is missing a required runtime shape")

    label_ids: list[str] = []
    classes: set[str] = set()
    expected_signals: set[str] = set()
    for label in labels:
        case_id = str(label.get("case_id", ""))
        label_ids.append(case_id)
        classes.add(str(label.get("class", "")))
        if "collectors" in label:
            failures.append(f"{case_id} label must not contain collectors")
        for field in ("expected_signals", "allowed_signals", "forbidden_signals"):
            if not isinstance(label.get(field), list):
                failures.append(f"{case_id} label field {field} must be an array")
        expected_signals.update(str(value) for value in label.get("expected_signals", []))
    if len(label_ids) != len(set(label_ids)):
        failures.append("blind label case IDs must be unique")
    if set(label_ids) != set(evidence_ids):
        failures.append("blind evidence and label case IDs must match exactly")
    required_classes = {"negative", "boundary", "single_fault", "compound_fault", "degraded_evidence"}
    if not required_classes.issubset(classes):
        failures.append("blind labels are missing required evaluation classes")
    leaked_values = sorted(expected_signals.intersection(text_values))
    if leaked_values:
        failures.append(f"exact signal labels leaked into evidence values: {', '.join(leaked_values)}")

    result = {
        "status": "failed" if failures else "passed",
        "schema_version": "rca-blind-corpus-contract/v1",
        "case_count": len(cases),
        "label_count": len(labels),
        "platform_count": len(platforms),
        "failures": failures,
    }
    print(json.dumps(result, indent=2))
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
