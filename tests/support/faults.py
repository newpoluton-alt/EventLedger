from __future__ import annotations

import json
import os
import subprocess
from pathlib import Path

COMMAND_VARIABLES = {
    "app_crash": "EVENTLEDGER_APP_CRASH_COMMAND",
    "app_start": "EVENTLEDGER_APP_START_COMMAND",
    "kafka_stop": "EVENTLEDGER_KAFKA_STOP_COMMAND",
    "kafka_start": "EVENTLEDGER_KAFKA_START_COMMAND",
    "postgres_stop": "EVENTLEDGER_POSTGRES_STOP_COMMAND",
    "postgres_start": "EVENTLEDGER_POSTGRES_START_COMMAND",
}


class FaultConfigurationError(ValueError):
    """Raised when a fault command is absent or malformed."""


class FaultController:
    """Runs explicitly configured commands without a command shell."""

    def __init__(self, working_directory: Path, timeout: float = 90.0) -> None:
        self.working_directory = working_directory
        self.timeout = timeout

    def require(self, *actions: str) -> None:
        for action in actions:
            self.command(action)

    def command(self, action: str) -> list[str]:
        try:
            variable = COMMAND_VARIABLES[action]
        except KeyError as error:
            raise FaultConfigurationError(f"unknown fault action: {action}") from error

        raw_value = os.getenv(variable)
        if not raw_value:
            raise FaultConfigurationError(
                f"{variable} must be a JSON array, for example "
                f"'[\"docker\",\"compose\",\"stop\",\"kafka\"]'"
            )

        try:
            command = json.loads(raw_value)
        except json.JSONDecodeError as error:
            raise FaultConfigurationError(
                f"{variable} is not valid JSON: {error.msg}"
            ) from error

        if (
            not isinstance(command, list)
            or not command
            or any(
                not isinstance(argument, str)
                or not argument
                or "\x00" in argument
                for argument in command
            )
        ):
            raise FaultConfigurationError(
                f"{variable} must be a non-empty JSON array of non-empty strings"
            )
        return command

    def run(self, action: str) -> None:
        command = self.command(action)
        result = subprocess.run(
            command,
            cwd=self.working_directory,
            check=False,
            capture_output=True,
            text=True,
            timeout=self.timeout,
        )
        if result.returncode != 0:
            output = "\n".join(part for part in (result.stdout, result.stderr) if part)
            raise RuntimeError(
                f"{action} command exited with {result.returncode}: {output[-2000:]}"
            )
