# Testing BTC and RGB payments with the example app

This runbook drives the [example app](README.md) on an Android emulator to test,
end to end:

- a BTC send between two wallets;
- an RGB receive from an issuer wallet, on a witness invoice;
- an RGB send between two wallets, signed on the device by the SDK.

It covers two environments. The **local regtest stack** is self-contained,
needs no funds or credentials, and lets you mine blocks and read gateway
internals, so use it for repeatable runs and for debugging. A **deployed signet
gateway** tests the real deployment, but needs an operator token and funds on
that chain.

The steps match the Swift SDK's runbook, so a run on one platform can be
compared line by line with the other.

## What you need

- An emulator (or device) visible to `adb`, with the app installed:
  `./gradlew :example-app:installDebug` (JDK 17, `ANDROID_HOME` set).
- Python 3, for [`drive.py`](scripts/drive.py), and Node 20+, for
  [`issuer.mjs`](scripts/issuer.mjs) and [`tip.mjs`](scripts/tip.mjs).
- For the local stack only, a checkout of the lightsdk-gateway repository,
  built with `pnpm install && pnpm build`. It holds the gateway and its regtest
  stack (`regtest/`), which runs in Docker with the compose plugin. The stack
  builds rgb-node-api from source on its first start, and some of rgb-node-api's
  git dependencies are private, so your ssh-agent needs a key with read access
  to the UTEXO-Protocol repositories.
- An emulator clock within 300 seconds of real time. Invoice creation checks the
  gateway's expiry against the device clock. Compare `adb shell date +%s` with
  `date +%s`; on an emulator image without Google Play, fix it with
  `adb root && adb shell "date @$(date +%s)"`.

Run the commands below from the repository root. Keep tokens and mnemonics in a
working directory outside the repository:

```sh
W=${TMPDIR:-/tmp}/utexo-payment-test; mkdir -p "$W"
export LIGHTSDK_GATEWAY_REPO=<path to the lightsdk-gateway checkout>
```

`gateway.sh` and `issuer.mjs` read `LIGHTSDK_GATEWAY_REPO` too. Without it they
look for the checkout next to this repository, under the repository's GitHub
name.

## The helpers

| Script | What it does |
| --- | --- |
| [`drive.py`](scripts/drive.py) | Restarts the app with intent extras, which connects and runs the named actions in order, then prints what the app logged. |
| [`issuer.mjs`](scripts/issuer.mjs) | Drives an RGB issuer and sender — a wallet on rgb-node-api whose keys the script holds and signs with (`setup`, `send-rgb`, `refresh`, and `address`, `create-utxos`, `issue` on other chains), and regtest (`mine`, `fund`). |
| [`gateway.sh`](scripts/gateway.sh) | Starts a local lightsdk-gateway against the regtest stack; `--debug` logs every wallet refresh result. |
| [`gateway-debug.mjs`](scripts/gateway-debug.mjs) | The `--debug` wrapper. |
| [`tip.mjs`](scripts/tip.mjs) | Serves the regtest chain tip as `GET /blocks/tip/height`, read from the stack's electrs, for the app's trusted chain context. |

Each `drive.py` run is one app session. `--wallet` names the wallet, and the app
keeps one Keystore seed per wallet ID. A wallet ID is bound to the first gateway
user it registers with, and reusing it with another user fails with
`wallet.invalid_state`. So use fresh wallet IDs whenever you create new gateway
users, as the `RUN` variable below does.

## Local regtest run

### 1. Start the regtest stack

```sh
"$LIGHTSDK_GATEWAY_REPO"/regtest/regtest.sh start
```

This starts bitcoind, electrs (`tcp://127.0.0.1:50001`), the RGB proxy and
rgb-node-api (`:34000`) on a fresh chain, deleting any earlier regtest state. The
first start builds the rgb-node-api image, which takes several minutes.

Only electrs and rgb-node-api are published on the host. If `50001` or `34000` is
taken, for example by another regtest stack, start with `REGTEST_ELECTRUM_PORT` /
`REGTEST_RGB_NODE_API_PORT` set, and pass the same ports to the gateway and
`tip.mjs` (`ELECTRUM_URL`, `RGB_NODE_API_URL`). rgb-node-api reaches bitcoind,
electrs and the proxy over the compose network, which is why invoices name the
proxy `rpc://proxy:3000/json-rpc`.

### 2. Set up the issuer and issue an asset

```sh
example-app/scripts/issuer.mjs setup
# issuer 1a2b3c4d
# asset rgb:…         <- keep this as ASSET
```

`setup` creates a throwaway issuer wallet on rgb-node-api (its mnemonic is kept in
`$TMPDIR/utexo-payment-test/issuer.json`), funds it with 1 BTC, creates colored
UTXOs and issues 1,000 units of an NIA asset (`--ticker`, `--name` and
`--supply` change it). Put the printed asset ID in `ASSET`.

### 3. Start the gateway and the tip server, and create two users

```sh
example-app/scripts/gateway.sh --debug > "$W/gateway.log" 2>&1 &
example-app/scripts/tip.mjs > "$W/tip.log" 2>&1 &
until curl -sf http://127.0.0.1:8490/v1/health > /dev/null; do sleep 1; done
for u in a b; do
    curl -s -X POST http://127.0.0.1:8490/v1/users \
        -H "x-operator-token: $(cat "$W/gateway/operator_token")" > "$W/user-$u.json"
done
RUN=$(date +%s)
drive() {
    who=$1; shift
    example-app/scripts/drive.py --gateway http://10.0.2.2:8490 --network regtest \
        --tip-url http://10.0.2.2:3003/blocks/tip/height \
        --token-file "$W/user-$who.json" --wallet "loc-$RUN-$who" \
        --mnemonic-out "$W/mnemonic-$who" "$@"
}
```

The emulator reaches the host's loopback as `10.0.2.2`. The trusted chain tip
comes from the stack's electrs through `tip.mjs`, not from the gateway.

### 4. BTC payment

```sh
drive a address                    # address: bcrt1p…   <- A
example-app/scripts/issuer.mjs fund <A> 0.01
drive a sync,balance               # vanilla settled=1000000
drive b address                    # address: bcrt1p…   <- B
drive a sendbtc recipient=<B> amount=50000
                                   # sent: txid …
example-app/scripts/issuer.mjs mine 1
drive b sync,balance               # vanilla settled=50000
```

The device validates the gateway-prepared transaction against the request and
the trusted tip, signs it, and the gateway broadcasts it.

### 5. RGB receive: issuer → A, witness invoice

```sh
drive a witnessinvoice             # invoice: rgb:~/~/~/bcrt:wvout:…   <- A_INVOICE
example-app/scripts/issuer.mjs send-rgb "$ASSET" 500 '<A_INVOICE>'
drive a sync,transfers,assets      # ReceiveWitness WaitingBroadcast; future=500
example-app/scripts/issuer.mjs refresh   # the issuer sees A's ACK and broadcasts
example-app/scripts/issuer.mjs mine 1
drive a sync,assets                # settled=500 spendable=500
```

Quote invoices: they contain `?` and `&`. For a witness invoice, `send-rgb` adds
1,000 sats for the output it creates for the receiver.

Every RGB payment takes three turns, because the sender (the issuer and the SDK
alike, with `donation: false`) broadcasts only after the receiver ACKs:

1. The receiver syncs: it fetches the consignment, validates it and ACKs. Its
   transfer then reads `WaitingBroadcast`.
2. The sender refreshes: it sees the ACK and broadcasts.
3. After a block, the receiver's next sync settles the transfer.

The txid that `send-rgb` and `rgbsend` print is not on chain until step 2.

rgb-node-api also refreshes every wallet it has loaded every 10 seconds on its
own, so steps 2 and 3 can happen between your commands. The app's invoices ask
for zero confirmations, so a transfer may already read `Settled` where the
comments show `WaitingBroadcast`, and a sender may broadcast before you sync it.

### 6. RGB send: A → B, signed by the SDK

```sh
drive a utxos                      # created 4 colored UTXO(s) in …
example-app/scripts/issuer.mjs mine 1
drive b witnessinvoice             # invoice: rgb:~/~/~/bcrt:wvout:…   <- B_INVOICE
drive a rgbsend recipient='<B_INVOICE>' amount=100 assetId="$ASSET"
                                   # sent: txid … batchTransferIdx=…
drive b sync,transfers             # ReceiveWitness WaitingBroadcast, amounts=100
drive a sync,transfers             # Send WaitingConfirmations, amounts=400 (A's change),
                                   # listed after step 5's settled receive
example-app/scripts/issuer.mjs mine 1
drive b sync,assets                # settled=100
drive a sync,assets                # settled=400
```

An RGB send is funded from colored UTXOs only: its fee, B's 1,000-sat witness
output and A's change all come from them, not from A's vanilla balance. The
1,000-sat output A received the asset on is not enough, hence `utxos` first. It
tops A up to five UTXOs with free allocation slots, and the output A received
on still has free slots, so it creates four. Sync B before A: B's sync ACKs,
and A's sync is what broadcasts.

### 7. Stop

```sh
kill %1 %2                         # the gateway and tip.mjs started above
"$LIGHTSDK_GATEWAY_REPO"/regtest/regtest.sh stop
```

## Deployed signet run

The app's defaults target UTEXO's stage gateway,
`https://stage.gateway.lightsdk.utexo.com`, which runs on **Bitcoin's public
signet** (`Network.SIGNET`, invoices tagged `sb`). The trusted tip comes from
`https://mempool.space/signet/api/blocks/tip/height`; look transactions up at
`https://mempool.space/signet/tx/<txid>`.

Create the users with the deployment's operator token. Then use the same `drive`
helper without the local flags:

```sh
for u in a b; do
    curl -s -X POST https://stage.gateway.lightsdk.utexo.com/v1/users \
        -H "x-operator-token: $GATEWAY_OPERATOR_TOKEN" > "$W/signet-user-$u.json"
done
RUN=$(date +%s)
drive() {
    who=$1; shift
    example-app/scripts/drive.py --token-file "$W/signet-user-$who.json" \
        --wallet "gw-$RUN-$who" --mnemonic-out "$W/signet-mnemonic-$who" "$@"
}
```

Steps 4–6 work the same way, except:

- nothing mines blocks, so wait for a confirmation (about 10 minutes) where the
  local run mines one;
- fund A from a public signet faucet (BTC to `drive a address`). 20,000 sats
  covers steps 4–6 with small amounts;
- the stage proxy, `rpcs://stage.rgb-proxy.utexo.com/json-rpc`, speaks RGB
  proxy protocol 1.0: invoices carry the recipient's Ed25519 key as `pk=…`, and
  a sender encrypts the consignment to it. `rgbsend` and `issuer.mjs send-rgb`
  pass the invoice's `pk`, `expiry` and `enc` on. The receiver ACKs with that
  key, so it must sync before the sender's refresh can broadcast;
- a proxy redeploy can drop its invoice reservations. Sending to an invoice made
  before one fails with `InvoiceNotReserved`; ask the receiver for a new invoice.

To act as the sender yourself once A has BTC, run your own rgb-node-api (main)
on that chain and drive an issuer wallet on it with `issuer.mjs`. RLN cannot be
the issuer: its rgb-lib speaks proxy protocol 0.2. rgb-node-api has no
authentication, so keep it on localhost. A configuration like this works; the
`btc_rpc_*` settings are required but rgb-node-api does not use them:

```toml
[api]
listen_address = "127.0.0.1"
port = 34100

[wallet]
data_dir = "./rgb-data-signet"
network = "Signet"
btc_rpc_address = "unused:0"
btc_rpc_user = "unused"
btc_rpc_password = "unused"
indexer_address = "https://mempool.space/signet/api"
proxy_address = ["rpcs://stage.rgb-proxy.utexo.com/json-rpc"]
```

Start it from an rgb-node-api checkout with `cargo run -- -c <that file>` (its
private git dependencies need read access to the UTEXO-Protocol repositories),
then:

```sh
export RGB_NODE_API_URL=http://127.0.0.1:34100 ISSUER_STATE="$W/issuer-signet.json"
example-app/scripts/issuer.mjs address       # address tb1p…
# fund it from A (drive a sendbtc …), wait for a confirmation, then:
example-app/scripts/issuer.mjs create-utxos --num 4 --size 1500  # created 4
# wait for a confirmation, then:
example-app/scripts/issuer.mjs issue --ticker TST --name Test --supply 1000
example-app/scripts/issuer.mjs send-rgb <asset> 500 '<A_INVOICE>'
example-app/scripts/issuer.mjs refresh
```

The issuer's mnemonic stays in `$W/issuer-signet.json`; it is a throwaway test
wallet.

## Troubleshooting

**A spend fails with `policy.rejected`.** The trusted chain tip is from a chain
other than the gateway's, or older than 300 seconds. A tip from another chain is
far off the gateway's height, and the signer rejects the prepared transaction.
For the stage gateway use the public-signet tip above; on regtest, check that
`tip.mjs` is running.

**`witnessinvoice`, `blindinvoice` or `rgbsend` fails with
`validation.invalid_argument: Invalid rgbInvoice`.** The SDK build predates proxy
protocol 1.0 invoices, whose `pk=` parameter its native invoice decoder rejects.
Use an SDK whose signer accepts `pk` and `enc`.

**`rgbsend` fails with `backend.rejected` (`INVALID_RECIPIENT_PUBLIC_KEY`).**
The gateway's rgb-node-api needs the invoice's recipient key and did not get a
valid one: the gateway predates `recipientPublicKey`, or the invoice is not a
protocol 1.0 invoice from that proxy.

**Invoice creation fails with `backend.protocol_mismatch` about expiry.** The
device and gateway clocks differ by more than 300 seconds. Fix the emulator
clock (see [What you need](#what-you-need)).

**The first call fails with `backend.unavailable`.** It is retryable; run the
same command again. See the example [README](README.md).

**`rgbsend` or `blindinvoice` fails with `backend.rejected`
(`INSUFFICIENT_FUNDS`) although the wallet has BTC.** Both need colored UTXOs.
An RGB send pays its fee and any witness output from them, and a blind invoice
receives onto a free one. Run `utxos`, wait for a confirmation (or mine one),
and retry.

**The issuer's `send-rgb` fails with `InsufficientAssignments` right after an
earlier send.** Its change is not spendable until that transfer settles. Run
`issuer.mjs refresh`, `issuer.mjs mine 1`, then `issuer.mjs refresh` again.

**An incoming RGB transfer stays `WaitingCounterparty`.** The receiver has not
accepted a consignment. Work through these:

1. On the local stack, `gateway.sh --debug` logs the result of every wallet
   refresh, which the gateway otherwise discards. Search `"$W/gateway.log"` for
   `"debug":"refresh"`; `result` maps each batch transfer index the refresh
   changed to its new status or failure. It is `{}` when nothing changed, which
   is common because rgb-node-api also refreshes every 10 seconds on its own.
2. rgb-lib writes its own log for every wallet. On the local stack it is inside
   the rgb-node-api container, one directory per master fingerprint:

   ```sh
   docker compose -f "$LIGHTSDK_GATEWAY_REPO"/regtest/compose.yaml exec rgb-node-api \
       sh -c 'tail -n 50 rgb-data/*/log'
   ```

   A `Skipping transport endpoint` line means the consignment fetch from the
   proxy returned nothing **or failed**. rgb-lib treats both the same way.
3. rgb-node-api's own log
   (`docker compose -f "$LIGHTSDK_GATEWAY_REPO"/regtest/compose.yaml logs rgb-node-api`)
   records refreshes that failed outright.

A protocol 1.0 proxy, such as stage's, stores only ACKs signed with the
invoice's `pk`, so an ACK there means the receiver accepted the transfer.

To debug a deployed environment's receive path, run rgb-node-api locally with
that environment's network, indexer (`indexer_address`) and proxy
(`proxy_address`), and `gateway.sh --debug` against it. Then restore a wallet
from the same mnemonic into it with `drive.py --restore-from`, using a new
wallet ID:

```sh
GATEWAY_PORT=8491 GATEWAY_DATA_DIR="$W/mirror" GATEWAY_BITCOIN_NETWORK=Signet \
RGB_NODE_API_URL=http://127.0.0.1:<its port> \
RGB_PROXY_URL=rpcs://stage.rgb-proxy.utexo.com/json-rpc \
    example-app/scripts/gateway.sh --debug > "$W/mirror.log" 2>&1 &
```

`RGB_PROXY_URL` must be exactly the `proxy_address` that rgb-node-api writes
into invoices, or sends between two of its wallets are rejected with
`TRANSPORT_ENDPOINT_NOT_ALLOWED`.

**Point rgb-node-api's `indexer_address` at a full Electrum server, such as the
regtest stack's electrs, or at a REST indexer API such as mempool.space's, not
at Blockstream's electrs fork.** That fork, which mempool.space also runs,
answers Electrum verbose-transaction requests with `verbose transactions are
currently unsupported`. rgb-lib's Electrum resolver needs those requests, for
its connect-time chain check and for validating received consignments. An
rgb-node-api with such an indexer either fails to bring wallets online
(`InvalidIndexer: resolver is for another chain-network pair`) or, with a build
that skips that check, syncs BTC but never accepts an RGB receive. The gateway's
`ELECTRUM_URL` has no such requirement: it asks only for fee estimates.
