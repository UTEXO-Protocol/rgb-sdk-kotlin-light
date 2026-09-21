# Durable consumer payment recovery

Fresh payment submission remains disabled in the ordinary factory. This contract
describes the integration needed before enabling it, and the existing same-request
status-only recovery behavior. It is not permission to enable the provisional
profile, and it does not make an unfrozen backend operation contract durable.

The SDK's encrypted journal protects signing/submission evidence. It intentionally
does not expose PSBTs or enumerate application payment intents. The application
must retain its own exact request, keyed by a stable application payment ID, before
calling `sendBtc`. This is separate from key custody and from the SDK journal.

## Application record

Store the following in one committed app-database transaction:

| Field | Encoding / requirement |
| --- | --- |
| Record schema and application payment ID | Versioned; unique ID chosen once for the user's payment intent |
| Wallet ID, logical network, service URL and app-owned account reference | Match the same wallet/account on reopen; do not silently switch accounts |
| Address | Exact `request.address.value` |
| Amount | Exact integer `request.amount.value` in satoshis |
| Fee | Exact integer `request.feeRate.milliSatsPerVbyte`, not formatted text or floating point |
| `skipSync` | Original Boolean, including an explicit stored default |
| Idempotency key | Exact `request.idempotencyKey.value`; generate only on new payment creation |
| Application outcome | Pending until authoritative result is durably recorded; retain the same ID/key in success history |

Do not store a mnemonic, seed, API key, prepared PSBT or signed PSBT in this record.
Payment metadata is still private: apply the app's encryption/access-control,
backup, schema-migration and retention policies. A lost/corrupt pending record is
a recovery problem, not authority to create a replacement payment.

The account reference is the application's own authenticated account ID, not a
gateway user ID fetched through infrastructure APIs. The ordinary facade does not
expose the gateway user ID; the SDK privately authenticates and binds that identity
to its encrypted journal. Applications do not need a raw gateway or SPI call.

Use a unique constraint on the application payment ID and transactionally
insert-or-load the original record. Concurrent UI jobs must never create two
requests for the same user-approved payment. Commit the record before SDK dispatch;
if persistence fails, do not call the SDK. Once dispatch may have occurred, do not
edit that record's amount, fee, recipient, key, wallet or network.

Reconstruct the request from the committed fields, explicitly supplying all values:

```kotlin
val request = SendBtcRequest(
    address = BitcoinAddress.of(record.address),
    amount = Satoshis.of(record.amountSats),
    feeRate = FeeRate.milliSatsPerVbyte(record.feeMilliSatsPerVbyte),
    skipSync = record.skipSync,
    idempotencyKey = IdempotencyKey.of(record.idempotencyKey),
)
val txid = wallet.sendBtc(request)
// Commit the txid to this SAME application payment record before reporting success.
```

`record` above represents the app's durable database row, not an SDK-defined type.
Never implement retry by reconstructing a request with omitted defaults. A fresh
random idempotency key denotes a new logical payment even if the amount is equal.

## Restart and uncertain outcomes

Reopen the same wallet/storage, initialize and unlock, then load the application's
pending record and call `sendBtc` with its original request. An existing SUBMITTING
checkpoint performs status-only recovery; it never prepares, signs or submits
again. Matching retained transaction identity is required before returning success.
Pending/unavailable/mismatched status remains `OperationAmbiguous` with the original
operation identity. Back off or surface pending status; do not replace the payment.

If the app crashes after the SDK saves success but before the app saves its result,
repeating the same request returns the durable SDK receipt without another status
lookup or submission. Preserve that behavior when replacing the package with the
future full SDK. Seed-only restoration is not a substitute for either app records,
the SDK journal, or server-owned RGB state.

## Executable evidence and limits

`EncryptedOperationJournalTest.consumer durably retains exact request before send
and reconstructs it for two restarts` writes a distinct authenticated app-intent
record before dispatch, exercises a lost submit response, destroys/recreates the
wallet and encrypted journal, reloads all request fields from storage, recovers by
status only, then repeats after a simulated app-result commit gap using the compact
terminal receipt. It also rejects changed intent with the same key before new work.
No in-memory request instance is reused for recovery; fee milli-units remain exact.

This is a host-side workflow regression using test key storage, fake signer and
fake backend. It is not an Android process-kill/power-loss test, a production app
database implementation, real gateway acceptance or a funded transaction. Separate
gateway adapter tests exercise completed, pending, unavailable and mismatched
status with the production adapter; physical-device/app-database and real gateway
restart qualification remain release gates.
