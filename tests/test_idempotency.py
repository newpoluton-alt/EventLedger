from __future__ import annotations

from concurrent.futures import ThreadPoolExecutor
from decimal import Decimal
from threading import Barrier
from typing import Any

import pytest

from conftest import IntegrationSettings
from support.api import (
    EventLedgerClient,
    assert_create_succeeded,
    eventually,
    extract_balance,
    extract_payment_id,
    json_object,
)

pytestmark = pytest.mark.integration


def test_duplicate_requests_return_the_original_payment(
    api: EventLedgerClient,
    payment_payload: dict[str, Any],
    idempotency_key: str,
) -> None:
    first = api.post_payment(payment_payload, idempotency_key)
    second = api.post_payment(payment_payload, idempotency_key)

    assert_create_succeeded(first)
    assert_create_succeeded(second)
    first_id = extract_payment_id(json_object(first))
    second_id = extract_payment_id(json_object(second))
    assert second_id == first_id

    fetched = api.get_payment(first_id)
    assert fetched.status_code == 200, fetched.text[:500]
    assert extract_payment_id(json_object(fetched)) == first_id


def test_concurrent_replays_create_one_logical_payment(
    api: EventLedgerClient,
    payment_payload: dict[str, Any],
    idempotency_key: str,
) -> None:
    replay_count = 12
    start_together = Barrier(replay_count)

    def submit_replay() -> tuple[int, str]:
        start_together.wait(timeout=5)
        response = api.post_payment(payment_payload, idempotency_key)
        assert_create_succeeded(response)
        return response.status_code, extract_payment_id(json_object(response))

    with ThreadPoolExecutor(max_workers=replay_count) as executor:
        results = list(executor.map(lambda _: submit_replay(), range(replay_count)))

    payment_ids = {payment_id for _, payment_id in results}
    assert len(payment_ids) == 1, f"replays returned different IDs: {results}"


def test_reusing_a_key_for_a_different_command_is_rejected(
    api: EventLedgerClient,
    payment_payload: dict[str, Any],
    idempotency_key: str,
) -> None:
    first = api.post_payment(payment_payload, idempotency_key)
    assert_create_succeeded(first)

    changed_payload = dict(payment_payload)
    changed_payload["reference"] = f"{payment_payload['reference']}-changed"
    conflict = api.post_payment(changed_payload, idempotency_key)

    assert conflict.status_code == 409, conflict.text[:500]


def test_duplicate_delivery_moves_money_exactly_once(
    api: EventLedgerClient,
    settings: IntegrationSettings,
    payment_payload: dict[str, Any],
    idempotency_key: str,
) -> None:
    source_before_response = api.get_balance(settings.source_account_id)
    destination_before_response = api.get_balance(settings.destination_account_id)
    assert source_before_response.status_code == 200, source_before_response.text[:500]
    assert destination_before_response.status_code == 200, (
        destination_before_response.text[:500]
    )
    source_before = extract_balance(json_object(source_before_response))
    destination_before = extract_balance(json_object(destination_before_response))

    first = api.post_payment(payment_payload, idempotency_key)
    duplicate = api.post_payment(payment_payload, idempotency_key)
    assert_create_succeeded(first)
    assert_create_succeeded(duplicate)
    assert extract_payment_id(json_object(first)) == extract_payment_id(
        json_object(duplicate)
    )

    expected_amount = Decimal(str(payment_payload["amount"]))

    def assert_balances_changed_once() -> None:
        source_after_response = api.get_balance(settings.source_account_id)
        destination_after_response = api.get_balance(
            settings.destination_account_id
        )
        assert source_after_response.status_code == 200
        assert destination_after_response.status_code == 200
        source_after = extract_balance(json_object(source_after_response))
        destination_after = extract_balance(json_object(destination_after_response))
        assert source_before - source_after == expected_amount
        assert destination_after - destination_before == expected_amount
        assert source_before + destination_before == source_after + destination_after

    eventually(assert_balances_changed_once, timeout=15)
