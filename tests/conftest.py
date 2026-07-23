from __future__ import annotations

import math
import os
import uuid
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import pytest

from support.api import EventLedgerClient
from support.faults import FaultConfigurationError, FaultController

TRUE_VALUES = frozenset({"1", "true", "yes", "on"})


@dataclass(frozen=True)
class IntegrationSettings:
    base_url: str
    api_key: str
    source_account_id: str
    destination_account_id: str
    currency: str
    amount: float
    request_timeout: float
    recovery_timeout: float


def pytest_addoption(parser: pytest.Parser) -> None:
    group = parser.getgroup("eventledger")
    group.addoption(
        "--run-integration",
        action="store_true",
        help="run tests that call a live EventLedger API",
    )
    group.addoption(
        "--run-fault-tests",
        action="store_true",
        help="run opt-in tests that stop configured local services",
    )
    group.addoption(
        "--base-url",
        default=os.getenv("EVENTLEDGER_BASE_URL", "http://localhost:8080"),
        help="EventLedger API base URL",
    )


def pytest_collection_modifyitems(
    config: pytest.Config,
    items: list[pytest.Item],
) -> None:
    run_integration = config.getoption("--run-integration") or _environment_flag(
        "EVENTLEDGER_RUN_INTEGRATION"
    )
    run_faults = config.getoption("--run-fault-tests") or _environment_flag(
        "EVENTLEDGER_RUN_FAULT_TESTS"
    )

    integration_skip = pytest.mark.skip(
        reason="pass --run-integration to call a live EventLedger API"
    )
    fault_skip = pytest.mark.skip(
        reason="pass --run-fault-tests and configure fault commands"
    )

    for item in items:
        if "integration" in item.keywords and not run_integration:
            item.add_marker(integration_skip)
        if "fault" in item.keywords and not run_faults:
            item.add_marker(fault_skip)


@pytest.fixture(scope="session")
def settings(pytestconfig: pytest.Config) -> IntegrationSettings:
    base_url = str(pytestconfig.getoption("--base-url")).rstrip("/")
    if not base_url.startswith(("http://", "https://")):
        pytest.fail("--base-url must use http:// or https://")
    api_key = os.getenv("EVENTLEDGER_API_KEY", "local-development-key")
    if len(api_key) < 16:
        pytest.fail("EVENTLEDGER_API_KEY must contain at least 16 characters")

    source_account_id = os.getenv(
        "EVENTLEDGER_SOURCE_ACCOUNT_ID",
        "00000000-0000-0000-0000-000000000001",
    )
    destination_account_id = os.getenv(
        "EVENTLEDGER_DESTINATION_ACCOUNT_ID",
        "00000000-0000-0000-0000-000000000002",
    )
    if source_account_id == destination_account_id:
        pytest.fail("source and destination account IDs must be different")
    try:
        uuid.UUID(source_account_id)
        uuid.UUID(destination_account_id)
    except ValueError as error:
        pytest.fail(f"account IDs must be UUIDs: {error}")

    currency = os.getenv("EVENTLEDGER_CURRENCY", "EUR").upper()
    is_ascii_currency = len(currency) == 3 and all(
        "A" <= character <= "Z" for character in currency
    )
    if not is_ascii_currency:
        pytest.fail("EVENTLEDGER_CURRENCY must be a three-letter code")
    amount = _positive_float("EVENTLEDGER_PAYMENT_AMOUNT", "1.00")
    request_timeout = _positive_float(
        "EVENTLEDGER_REQUEST_TIMEOUT_SECONDS",
        "5",
    )
    recovery_timeout = _positive_float(
        "EVENTLEDGER_RECOVERY_TIMEOUT_SECONDS",
        "45",
    )

    return IntegrationSettings(
        base_url=base_url,
        api_key=api_key,
        source_account_id=source_account_id,
        destination_account_id=destination_account_id,
        currency=currency,
        amount=amount,
        request_timeout=request_timeout,
        recovery_timeout=recovery_timeout,
    )


@pytest.fixture(scope="session")
def api(settings: IntegrationSettings) -> EventLedgerClient:
    client = EventLedgerClient(
        settings.base_url,
        settings.api_key,
        settings.request_timeout,
    )
    try:
        client.wait_until_ready(settings.recovery_timeout)
    except TimeoutError as error:
        pytest.fail(str(error))
    return client


@pytest.fixture
def payment_payload(settings: IntegrationSettings) -> dict[str, Any]:
    return {
        "sourceAccountId": settings.source_account_id,
        "destinationAccountId": settings.destination_account_id,
        "amount": settings.amount,
        "currency": settings.currency,
        "reference": f"pytest-{uuid.uuid4()}",
    }


@pytest.fixture
def idempotency_key() -> str:
    return str(uuid.uuid4())


@pytest.fixture(scope="session")
def fault_controller() -> FaultController:
    timeout = _positive_float("EVENTLEDGER_FAULT_COMMAND_TIMEOUT_SECONDS", "90")
    if timeout > 600:
        pytest.fail("fault command timeout must not exceed 600 seconds")
    controller = FaultController(Path(__file__).resolve().parents[1], timeout)
    return controller


def require_faults(
    controller: FaultController,
    *actions: str,
) -> None:
    try:
        controller.require(*actions)
    except FaultConfigurationError as error:
        pytest.skip(str(error))


def _environment_flag(name: str) -> bool:
    return os.getenv(name, "").strip().lower() in TRUE_VALUES


def _positive_float(name: str, default: str) -> float:
    raw_value = os.getenv(name, default)
    try:
        value = float(raw_value)
    except ValueError as error:
        pytest.fail(f"{name} must be numeric, got {raw_value!r}")
    if not math.isfinite(value) or value <= 0:
        pytest.fail(f"{name} must be a finite number greater than zero")
    return value
