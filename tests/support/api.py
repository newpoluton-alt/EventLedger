from __future__ import annotations

import time
from decimal import Decimal, InvalidOperation
from typing import Any, Callable
from urllib.parse import quote

import requests

SUCCESSFUL_CREATE_CODES = frozenset({200, 201, 202})


class EventLedgerClient:
    """Small, thread-safe HTTP client for the public EventLedger API."""

    def __init__(
        self,
        base_url: str,
        api_key: str,
        timeout: float = 5.0,
    ) -> None:
        self.base_url = base_url.rstrip("/")
        self.api_key = api_key
        self.timeout = timeout

    def _headers(self, **additional: str) -> dict[str, str]:
        return {
            "Accept": "application/json",
            "X-API-Key": self.api_key,
            **additional,
        }

    def post_payment(
        self,
        payload: dict[str, Any],
        idempotency_key: str,
        *,
        timeout: float | None = None,
    ) -> requests.Response:
        return requests.post(
            f"{self.base_url}/api/v1/payments",
            json=payload,
            headers=self._headers(**{"Idempotency-Key": idempotency_key}),
            timeout=timeout or self.timeout,
        )

    def get_payment(self, payment_id: str) -> requests.Response:
        return requests.get(
            f"{self.base_url}/api/v1/payments/{quote(payment_id, safe='')}",
            headers=self._headers(),
            timeout=self.timeout,
        )

    def get_balance(self, account_id: str) -> requests.Response:
        return requests.get(
            f"{self.base_url}/api/v1/accounts/{quote(account_id, safe='')}/balance",
            headers=self._headers(),
            timeout=self.timeout,
        )

    def start_reconciliation(self) -> requests.Response:
        return requests.post(
            f"{self.base_url}/api/v1/reconciliation/runs",
            headers=self._headers(),
            timeout=self.timeout,
        )

    def get_reconciliation(self, run_id: str) -> requests.Response:
        return requests.get(
            f"{self.base_url}/api/v1/reconciliation/runs/{quote(run_id, safe='')}",
            headers=self._headers(),
            timeout=self.timeout,
        )

    def wait_until_ready(self, timeout: float = 30.0) -> None:
        deadline = time.monotonic() + timeout
        last_error = "no response"
        health_paths = ("/actuator/health/readiness", "/actuator/health")

        while time.monotonic() < deadline:
            for path in health_paths:
                try:
                    response = requests.get(
                        f"{self.base_url}{path}",
                        headers={"Accept": "application/json"},
                        timeout=min(self.timeout, 2.0),
                    )
                    if response.status_code == 404:
                        continue
                    if response.status_code == 200:
                        return
                    last_error = (
                        f"{path} returned HTTP {response.status_code}: "
                        f"{response.text[:300]}"
                    )
                except requests.RequestException as error:
                    last_error = f"{type(error).__name__}: {error}"
            time.sleep(0.25)

        raise TimeoutError(
            f"EventLedger was not ready at {self.base_url} within "
            f"{timeout:.1f}s ({last_error})"
        )

def assert_create_succeeded(response: requests.Response) -> None:
    assert response.status_code in SUCCESSFUL_CREATE_CODES, (
        f"expected HTTP {sorted(SUCCESSFUL_CREATE_CODES)}, got "
        f"{response.status_code}: {response.text[:500]}"
    )


def json_object(response: requests.Response) -> dict[str, Any]:
    try:
        payload = response.json()
    except ValueError as error:
        raise AssertionError(
            f"expected a JSON response, got: {response.text[:500]}"
        ) from error
    assert isinstance(payload, dict), f"expected a JSON object, got {type(payload)}"
    return payload


def extract_payment_id(payload: dict[str, Any]) -> str:
    return _extract_identifier(payload, ("id", "paymentId"), "payment")


def extract_reconciliation_id(payload: dict[str, Any]) -> str:
    return _extract_identifier(
        payload,
        ("id", "runId", "reconciliationRunId"),
        "reconciliation run",
    )


def extract_balance(payload: dict[str, Any]) -> Decimal:
    candidates: list[Any] = [
        payload.get("balance"),
        payload.get("currentBalance"),
        payload.get("availableBalance"),
    ]
    nested = payload.get("account")
    if isinstance(nested, dict):
        candidates.extend(
            [
                nested.get("balance"),
                nested.get("currentBalance"),
                nested.get("availableBalance"),
            ]
        )

    for value in candidates:
        if value is None or isinstance(value, bool):
            continue
        try:
            return Decimal(str(value))
        except InvalidOperation:
            continue
    raise AssertionError(f"balance field is missing or invalid: {payload}")


def eventually(
    assertion: Callable[[], None],
    *,
    timeout: float = 10.0,
    interval: float = 0.25,
) -> None:
    """Retry an assertion until it passes or its deadline is reached."""

    deadline = time.monotonic() + timeout
    last_error: AssertionError | None = None
    while time.monotonic() < deadline:
        try:
            assertion()
            return
        except AssertionError as error:
            last_error = error
            time.sleep(interval)
    if last_error is not None:
        raise last_error
    raise AssertionError("eventual assertion did not run")


def _extract_identifier(
    payload: dict[str, Any],
    keys: tuple[str, ...],
    label: str,
) -> str:
    containers = [payload]
    for nested_key in ("payment", "data", "result"):
        nested = payload.get(nested_key)
        if isinstance(nested, dict):
            containers.append(nested)

    for container in containers:
        for key in keys:
            value = container.get(key)
            if isinstance(value, str) and value:
                return value
    raise AssertionError(f"{label} identifier is missing: {payload}")
