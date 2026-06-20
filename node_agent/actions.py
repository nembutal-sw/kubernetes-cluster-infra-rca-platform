from __future__ import annotations

import os
import subprocess
from dataclasses import dataclass
from typing import Any


ALLOWED_SYSTEMD_UNITS = {"kubelet", "containerd", "crio", "docker"}


@dataclass(frozen=True)
class ActionResult:
    status: str
    exit_code: int | None
    stdout: str
    stderr: str
    error_message: str | None = None


class ApprovedActionExecutor:
    def __init__(self, enabled: bool = False) -> None:
        self.enabled = enabled

    def execute(self, execution: dict[str, Any]) -> ActionResult:
        if not self.enabled:
            return ActionResult("failed", None, "", "", "approved action execution is disabled")
        command_key = str(execution.get("command_key") or "")
        parameters = execution.get("parameters")
        if not isinstance(parameters, dict):
            parameters = {}
        timeout = max(1, min(int(execution.get("timeout_seconds") or 30), 900))
        try:
            command = self._command(command_key, parameters)
            completed = subprocess.run(
                command,
                shell=False,
                capture_output=True,
                text=True,
                timeout=timeout,
                check=False,
                env={"PATH": "/usr/sbin:/usr/bin:/sbin:/bin", "LANG": "C.UTF-8"},
            )
            return ActionResult(
                "completed" if completed.returncode == 0 else "failed",
                completed.returncode,
                completed.stdout[-65535:],
                completed.stderr[-65535:],
                None if completed.returncode == 0 else "approved command returned a non-zero exit code",
            )
        except (ValueError, OSError, subprocess.TimeoutExpired) as exc:
            return ActionResult("failed", None, "", "", str(exc)[:4000])

    def _command(self, command_key: str, parameters: dict[str, Any]) -> list[str]:
        if command_key == "restart_systemd_unit":
            unit = str(parameters.get("unit") or "")
            if unit not in ALLOWED_SYSTEMD_UNITS:
                raise ValueError("systemd unit is not allowlisted")
            return self._host_command("systemctl", "restart", unit)
        if command_key == "restart_detected_runtime":
            unit = self._detected_runtime()
            return self._host_command("systemctl", "restart", unit)
        raise ValueError("command key is not allowlisted")

    def _detected_runtime(self) -> str:
        for unit in ("containerd", "crio", "docker"):
            result = subprocess.run(
                self._host_command("systemctl", "is-active", unit),
                shell=False,
                capture_output=True,
                text=True,
                timeout=5,
                check=False,
            )
            if result.returncode == 0:
                return unit
        raise ValueError("no supported active container runtime was detected")

    @staticmethod
    def _host_command(*command: str) -> list[str]:
        if os.path.exists("/proc/1/ns/mnt"):
            return [
                "nsenter", "--target", "1", "--mount", "--uts", "--ipc", "--net", "--pid",
                "--root=/proc/1/root", "--wd=/", "--",
                *command,
            ]
        return list(command)
