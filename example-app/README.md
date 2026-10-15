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

A token is minted once per user by the operator:

```sh
curl -X POST https://your-gateway.example/v1/users \
     -H "x-operator-token: $GATEWAY_OPERATOR_TOKEN"
# 201 {"userId":"u_…","token":"utxg_…","createdAt":…}
```

For a gateway on your own machine use `http://10.0.2.2:<port>`.

For scripted runs, every field is also an intent extra:

```sh
adb shell am start -n com.utexo.rgb.sdk.example/.MainActivity \
  -e gatewayUrl https://your-gateway.example \
  -e bearerToken utxg_… \
  -e provisionedMnemonic word_word_word_…   `# optional: restore instead of generate` \
  -e autoConnect true
```

`provisionedMnemonic` is underscore-separated because `am start -e` does not carry
spaces through reliably.

## What the buttons show

| Button       | What it demonstrates                                                       |
| ------------ | -------------------------------------------------------------------------- |
| **Connect**  | first-run seed generation (or restore) → `create` → `init` → `unlock`      |
| **Address**  | the `reserveAddress` gap, and the workaround — see below                    |
| **Balance**  | `getBtcBalance()`, split vanilla / colored, settled / spendable            |
| **Sync**     | `syncWallet()` — one call covering Bitcoin sync plus RGB refresh            |
| **Unspents** | `listUnspents()`, including the authoritative `exists` flag                 |
| **Send**     | why `sendBtc` is gated off in the application API                           |

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

## Two capabilities that are deliberately not wired up

**`getAddress()` throws `configuration.unsupported`.** The gateway backend does not
implement `reserveAddress` yet. Until it does, get a deposit address from
`GET /v1/wallet/address` and re-derive it from the `derivation` object that
response carries before showing it to a user. A `null` derivation means the
gateway could not attribute the address — that is unproven, not safe.

**`sendBtc()` throws `configuration.unsupported`.** `WalletServiceConfiguration`
pins `allowUnverifiedPsbtProfile = false`. Sending works, and has been run end to
end against a live gateway; what is gated is the entry point. To send, construct
`RgbLightningNodeGatewayBackend` yourself with the flag on — and qualify your
deployment's PSBT profile before you do.

Both gaps are reachable from the buttons on purpose, so they are discovered here
rather than in production.
