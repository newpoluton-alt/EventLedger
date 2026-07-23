from __future__ import annotations

import pytest

from support.api import (
    EventLedgerClient,
    assert_create_succeeded,
    extract_reconciliation_id,
    json_object,
)

pytestmark = pytest.mark.integration


def test_reconciliation_run_can_be_started_and_queried(
    api: EventLedgerClient,
) -> None:
    started = api.start_reconciliation()
    assert_create_succeeded(started)
    run_id = extract_reconciliation_id(json_object(started))

    fetched = api.get_reconciliation(run_id)
    assert fetched.status_code == 200, fetched.text[:500]
    assert extract_reconciliation_id(json_object(fetched)) == run_id
