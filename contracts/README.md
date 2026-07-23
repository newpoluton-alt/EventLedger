# Consumer Pact

`eventledger-portfolio-client-eventledger-api.json` is a Pact V3 consumer
contract for the five public operations used by the black-box test client:

- create a payment with an `Idempotency-Key`;
- query a payment;
- query an account balance;
- start a reconciliation run;
- query a reconciliation run.

The consumer is `EventLedger Portfolio Client` and the provider is
`EventLedger API`. Dynamic identifiers, timestamps, numeric balances, and
versions use matching rules; stable demo-account IDs and command fields remain
exact so accidental API drift is visible.

Contract requests include `X-API-Key: pact-integration-api-key`; configure the
same test-only key when running provider verification.

Provider verification needs these states:

- `demo accounts exist with sufficient EUR balance`;
- `payment exists` with a `paymentId` parameter;
- `demo customer account exists`;
- `ledger is balanced`;
- `completed reconciliation run exists` with a `runId` parameter.

Successful reconciliation examples omit `errorMessage`, because the API omits
null-valued fields. A failed-run consumer interaction should be added if a
client begins depending on that optional field.

Validate the JSON and all adjacent test assets with:

```bash
./scripts/validate-test-assets.sh
```
