#!/usr/bin/env python3
"""Validate that Java-only and integrated Frontend Maven lifecycles stay separated."""

from __future__ import annotations

import json
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
POM = ROOT / "web-console" / "pom.xml"
NS = {"m": "http://maven.apache.org/POM/4.0.0"}


def text(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


def plugin_artifact_ids(parent: ET.Element | None) -> list[str]:
    if parent is None:
        return []
    return [
        value
        for plugin in parent.findall("m:plugin", NS)
        if (value := plugin.findtext("m:artifactId", default="", namespaces=NS))
    ]


def execution_ids(plugin: ET.Element) -> set[str]:
    return {
        execution.findtext("m:id", default="", namespaces=NS)
        for execution in plugin.findall("m:executions/m:execution", NS)
    }


def require(condition: bool, detail: str, failures: list[str]) -> None:
    if not condition:
        failures.append(detail)


def main() -> int:
    root = ET.parse(POM).getroot()
    failures: list[str] = []
    default_plugins = root.find("m:build/m:plugins", NS)
    default_ids = plugin_artifact_ids(default_plugins)
    require(
        "frontend-maven-plugin" not in default_ids,
        "frontend-maven-plugin must not be bound to the default Maven lifecycle",
        failures,
    )

    frontend_profile = next(
        (
            profile
            for profile in root.findall("m:profiles/m:profile", NS)
            if profile.findtext("m:id", default="", namespaces=NS) == "frontend"
        ),
        None,
    )
    require(frontend_profile is not None, "Maven profile 'frontend' is required", failures)
    if frontend_profile is not None:
        profile_plugins = frontend_profile.find("m:build/m:plugins", NS)
        profile_ids = plugin_artifact_ids(profile_plugins)
        require(
            "frontend-maven-plugin" in profile_ids,
            "frontend profile must contain frontend-maven-plugin",
            failures,
        )
        require(
            "maven-resources-plugin" in profile_ids,
            "frontend profile must copy built assets into the Spring Boot classpath",
            failures,
        )
        frontend_plugin = next(
            (
                plugin
                for plugin in profile_plugins.findall("m:plugin", NS)
                if plugin.findtext("m:artifactId", default="", namespaces=NS)
                == "frontend-maven-plugin"
            ),
            None,
        ) if profile_plugins is not None else None
        if frontend_plugin is not None:
            ids = execution_ids(frontend_plugin)
            require(
                {"install-node-and-npm", "npm-ci", "vite-build"}.issubset(ids),
                "frontend profile must install locked dependencies and build Vite assets",
                failures,
            )
            require(
                "frontend-test" not in ids,
                "Frontend tests must run in the independent npm lifecycle",
                failures,
            )

    ci = text(".github/workflows/ci.yml")
    dockerfile = text("Dockerfile.web-console")
    require(
        "run: mvn --batch-mode --no-transfer-progress verify" in ci,
        "web-console-test must exercise the Java-only Maven lifecycle",
        failures,
    )
    require(
        "-Pfrontend -DskipTests package" in ci,
        "console E2E package must use the integrated frontend profile",
        failures,
    )
    require(
        "npm test" in ci and "npm run build" in ci,
        "CI must test and build the Frontend independently",
        failures,
    )
    require(
        "mvn -B -ntp -Pfrontend dependency:go-offline" in dockerfile
        and "mvn -B -ntp -Pfrontend verify" in dockerfile,
        "Platform image build must prefetch and use the integrated frontend profile",
        failures,
    )

    result = {
        "status": "failed" if failures else "passed",
        "schema_version": "rca-build-lifecycle-check/v1",
        "failures": failures,
    }
    print(json.dumps(result, indent=2))
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
