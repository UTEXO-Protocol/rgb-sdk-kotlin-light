# `example-app` — a reference integration

A single-screen Android app that exercises the UTEXO SDK against a
`minimal-gateway` deployment. It exists to be read: every SDK call lives in one
file, [`MainActivity.kt`](src/main/kotlin/com/utexo/rgb/sdk/example/MainActivity.kt),
with no architecture or DI in the way.

It is not a shipping wallet. It prints a mnemonic to the screen and enables
cleartext HTTP to the emulator host — both fine for signet, neither acceptable in
production.

## Run it

```sh
./gradlew :example-app:installDebug
```

Then launch it, paste a gateway URL and a bearer token, and press **Connect**.
The defaults target UTEXO's signet environment: `Network.SIGNET`, with the
trusted chain tip read from `https://esplora-api.utexo.com/blocks/tip/height`.

A token is minted once per user by the operator:

```sh
curl -X POST https://your-gateway.example/v1/users \
     -H "x-operator-token: $GATEWAY_OPERATOR_TOKEN"
# 201 {"userId":"u_…","token":"utxg_…","createdAt":…}
```

For a gateway on your own machine use `http://10.0.2.2:<port>`. Against
`rgb-lightning-node`'s regtest stack, also pass `-e network regtest` and
`-e tipUrl http://10.0.2.2:3002/blocks/tip/height`.

For scripted runs, every field is also an intent extra, and `run` connects and
then runs the named actions in order, logging each result line to logcat under
the `UtexoExample` tag:

```sh
adb shell am start -S -n com.utexo.rgb.sdk.example/.MainActivity \
  -e gatewayUrl https://your-gateway.example \
  -e bearerToken utxg_… \
  -e walletId example                       `# one Keystore seed per wallet ID` \
  -e provisionedMnemonic word_word_word_…   `# optional: restore instead of generate` \
  -e recipient tb1p…  -e amount 50000 \
  -e run sync,balance,sendbtc
adb logcat -s UtexoExample:I
```

`provisionedMnemonic` is underscore-separated because `am start -e` does not carry
spaces through reliably. Quote an RGB invoice passed as `recipient`: it contains
`?` and `&`.

## What the actions show

| Action           | What it demonstrates                                                         |
| ---------------- | ---------------------------------------------------------------------------- |
| **Connect**      | first-run seed generation (or restore) → `create` → `init` → `unlock`        |
| `address`        | `getAddress()` — a fresh vanilla address, re-derived locally before use       |
| `balance`        | `getBtcBalance()`, split vanilla / colored, settled / spendable              |
| `sync`           | `syncWallet()` — Bitcoin sync plus RGB refresh; settles incoming RGB          |
| `unspents`       | `listUnspents()`, with `exists` reported by the gateway or null               |
| `utxos`          | `createUtxos()` — colored UTXOs to hold RGB allocations                       |
| `sendbtc`        | `sendBtc()` to *recipient* for *amount* sats, signed on the device            |
| `assets`         | `listAssets()` with settled / spendable / future balances                     |
| `transfers`      | `listTransfers()` for every asset, or only for *asset ID*                     |
| `witnessinvoice` | `witnessReceive()` — the sender creates the receiving output                  |
| `blindinvoice`   | `blindReceive()` — receives onto one of this wallet's colored UTXOs           |
| `rgbsend`        | `onchainSend()` of *amount* to the RGB invoice in *recipient*                 |

## Three things the example is really here to teach

**Provisioning is one-shot.** The seed must be in the Keystore *before* the wallet
is created; `unlock()` fails with `wallet.key_access_failed` if it was never
provisioned. The store also refuses to overwrite an existing seed, and that
refusal is how a later run discovers it already has one — replacing a seed would
silently orphan the funds held under the old one.

**The first call against a new wallet may fail, and that is not an outage.**
Observed repeatedly: immediately after `init()`/`unlock()` on a freshly
registered wallet, the first `getBtcBalance()` intermittently fails with a
retryable `backend.unavailable`, and the very next identical call succeeds. The
gateway is not returning an error in these cases — the failure carries no
`correlationId`, so it never reached the status mapping. Raising the timeouts (as
this example does, well above the 30s read / 45s call defaults) makes it rarer
but does not eliminate it, so the cause is not just slowness; `backendCall` maps
any non-`SdkException` to `backend.unavailable`, which hides whatever it really
is. Treat `backend.unavailable` as what it says it is — retryable — and retry it.

**Branch on `code`, never on the message.** `SdkErrorCode` is the stable contract.
Messages, codes and `details` are all documented as redacted and safe to log, so
the example logs the code, the `correlationId` an operator can grep for, and
`details` — and nothing else.

## Before testing a payment

**Spending needs a trusted chain tip.** `chainContextProvider` must read the tip
height from an indexer for the chain the gateway runs on, never from the gateway.
UTEXO's signet is not Bitcoin's public signet even though its invoices say `sb`;
a public-signet tip is far behind it, and the signer rejects every spend.

**RGB payments take three turns.** The receiver syncs to fetch, validate and
ACK the consignment. The sender then syncs to see the ACK and broadcast. After a
confirmation, the receiver's next sync settles the transfer.

**RGB needs colored UTXOs.** A sender funds the fee and any witness output from
colored UTXOs, not its vanilla balance (`rgbsend` adds 1,000 sats for a `wvout:`
recipient). A blind invoice receives onto a free colored UTXO. Run `utxos` first.

[PAYMENT_TESTING.md](PAYMENT_TESTING.md) walks through complete BTC and RGB
payment runs, locally on regtest and against a deployed gateway.
