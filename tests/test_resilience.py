from __future__ import annotations

import os
import time
from concurrent.futures import ThreadPoolExecutor
from typing import Any

import pytest
import requests

from conftest import IntegrationSettings, require_faults
from support.api import (
    SUCCESSFUL_CREATE_CODES,
    EventLedgerClient,
    assert_create_succeeded,
    extract_payment_id,
    json_object,
)
from support.faults import FaultController

pytestmark = [pytest.mark.integration, pytest.mark.fault]


def test_retry_after_process_crash_does_not_duplicate_payment(
    api: EventLedgerClient,
    settings: IntegrationSettings,
    fault_controller: FaultController,
    payment_payload: dict[str, Any],
    idempotency_key: str,
) -> None:
    require_faults(fault_controller, "app_crash", "app_start")
    raw_crash_delay = os.getenv("EVENTLEDGER_CRASH_DELAY_SECONDS", "0.05")
    try:
        crash_delay = float(raw_crash_delay)
    except ValueError:
        pytest.fail(
            "EVENTLEDGER_CRASH_DELAY_SECONDS must be numeric, "
            f"got {raw_crash_delay!r}"
        )
    if not 0 <= crash_delay <= 5:
        pytest.fail("EVENTLEDGER_CRASH_DELAY_SECONDS must be between 0 and 5")
    initial_response: requests.Response | None = None
    initial_error: Exception | None = None

    executor = ThreadPoolExecutor(max_workers=1)
    in_flight = executor.submit(
        api.post_payment,
        payment_payload,
        idempotency_key,
        timeout=min(settings.request_timeout, 3.0),
    )
    time.sleep(crash_delay)
    try:
        fault_controller.run("app_crash")
        try:
            initial_response = in_flight.result(timeout=settings.request_timeout + 2)
        except Exception as error:
            initial_error = error
    finally:
        fault_controller.run("app_start")
        executor.shutdown(wait=True, cancel_futures=True)

    api.wait_until_ready(settings.recovery_timeout)
    recovered = _retry_payment_until_success(
        api,
        payment_payload,
        idempotency_key,
        settings.recovery_timeout,
    )
    replay = api.post_payment(payment_payload, idempotency_key)
    assert_create_succeeded(recovered)
    assert_create_succeeded(replay)
    recovered_id = extract_payment_id(json_object(recovered))
    assert extract_payment_id(json_object(replay)) == recovered_id

    if initial_response is not None and initial_response.status_code < 500:
        assert_create_succeeded(initial_response)
        assert extract_payment_id(json_object(initial_response)) == recovered_id
    elif initial_error is not None:
        assert isinstance(
            initial_error,
            (requests.RequestException, TimeoutError),
        ), f"unexpected in-flight error: {initial_error!r}"


def test_payment_remains_idempotent_across_kafka_outage(
    api: EventLedgerClient,
    settings: IntegrationSettings,
    fault_controller: FaultController,
    payment_payload: dict[str, Any],
    idempotency_key: str,
) -> None:
    require_faults(fault_controller, "kafka_stop", "kafka_start")
    response: requests.Response | None = None
    try:
        fault_controller.run("kafka_stop")
        response = api.post_payment(payment_payload, idempotency_key)
        assert_create_succeeded(response)
    finally:
        fault_controller.run("kafka_start")

    api.wait_until_ready(settings.recovery_timeout)
    assert response is not None
    accepted_id = extract_payment_id(json_object(response))
    replay = api.post_payment(payment_payload, idempotency_key)
    assert_create_succeeded(replay)
    assert extract_payment_id(json_object(replay)) == accepted_id

    fetched = api.get_payment(accepted_id)
    assert fetched.status_code == 200, fetched.text[:500]
    assert extract_payment_id(json_object(fetched)) == accepted_id


def test_retry_recovers_after_temporary_postgres_outage(
    api: EventLedgerClient,
    settings: IntegrationSettings,
    fault_controller: FaultController,
    payment_payload: dict[str, Any],
    idempotency_key: str,
) -> None:
    require_faults(fault_controller, "postgres_stop", "postgres_start")
    outage_response: requests.Response | None = None
    outage_error: requests.RequestException | None = None

    try:
        fault_controller.run("postgres_stop")
        _wait_until_database_is_unavailable(
            api,
            settings.source_account_id,
            min(settings.recovery_timeout, 20.0),
        )
        try:
            outage_response = api.post_payment(payment_payload, idempotency_key)
        except requests.RequestException as error:
            outage_error = error
    finally:
        fault_controller.run("postgres_start")

    if outage_response is not None:
        assert outage_response.status_code >= 500, outage_response.text[:500]
    else:
        assert outage_error is not None

    api.wait_until_ready(settings.recovery_timeout)
    recovered = _retry_payment_until_success(
        api,
        payment_payload,
        idempotency_key,
        settings.recovery_timeout,
    )
    replay = api.post_payment(payment_payload, idempotency_key)
    assert_create_succeeded(recovered)
    assert_create_succeeded(replay)
    assert extract_payment_id(json_object(recovered)) == extract_payment_id(
        json_object(replay)
    )


def _wait_until_database_is_unavailable(
    api: EventLedgerClient,
    account_id: str,
    timeout: float,
) -> None:
    """Probe a database-backed route rather than relying on readiness groups."""

    deadline = time.monotonic() + timeout
    last_status: int | None = None
    while time.monotonic() < deadline:
        try:
            response = api.get_balance(account_id)
            last_status = response.status_code
            if response.status_code >= 500:
                return
        except requests.RequestException:
            return
        time.sleep(0.25)

    pytest.fail(
        "the balance route did not observe the configured PostgreSQL outage "
        f"within {timeout:.1f}s (last HTTP status: {last_status})"
    )


def _retry_payment_until_success(
    api: EventLedgerClient,
    payload: dict[str, Any],
    idempotency_key: str,
    timeout: float,
) -> requests.Response:
    deadline = time.monotonic() + timeout
    last_failure = "no response"
    while time.monotonic() < deadline:
        try:
            response = api.post_payment(payload, idempotency_key)
            if response.status_code in SUCCESSFUL_CREATE_CODES:
                return response
            if response.status_code < 500:
                pytest.fail(
                    f"recovery retry returned HTTP {response.status_code}: "
                    f"{response.text[:500]}"
                )
            last_failure = f"HTTP {response.status_code}: {response.text[:300]}"
        except requests.RequestException as error:
            last_failure = f"{type(error).__name__}: {error}"
        time.sleep(0.25)

    pytest.fail(
        f"payment API did not recover within {timeout:.1f}s ({last_failure})"
    )
