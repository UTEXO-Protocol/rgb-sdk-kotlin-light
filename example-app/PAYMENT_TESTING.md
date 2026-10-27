# Testing BTC and RGB payments with the example app

This runbook drives the [example app](README.md) on an Android emulator to test,
end to end:

- a BTC send between two wallets;
- an RGB receive from an issuer node, on a witness invoice;
- an RGB send between two wallets, signed on the device by the SDK.

It covers two environments. The **local regtest stack** is self-contained,
needs no funds or credentials, and lets you mine blocks and read gateway
internals, so use it for repeatable runs and for debugging. A **deployed signet
gateway** tests the real deployment, but needs an operator token and funds on
that chain.

## What you need

- An emulator (or device) visible to `adb`, with the app installed:
  `./gradlew :example-app:installDebug` (JDK 17, `ANDROID_HOME` set).
- Python 3, for the helpers in [`scripts/`](scripts).
- For the local stack only, next to this repository:
  - `../rgb-lightning-node`, built with `cargo build`, plus Docker with the
    compose plugin;
  - `../rln-gateway`, built with `pnpm install && pnpm build` (Node 20+).
- An emulator clock within 300 seconds of real time. Invoice creation checks the
  gateway's expiry against the device clock. Compare `adb shell date +%s` with
  `date +%s`; on an emulator image without Google Play, fix it with
  `adb root && adb shell "date @$(date +%s)"`.

Run the commands below from the repository root. Keep tokens and mnemonics in a
working directory outside the repository:

```sh
W=${TMPDIR:-/tmp}/utexo-payment-test; mkdir -p "$W"
```

## The helpers

| Script | What it does |
| --- | --- |
| [`drive.py`](scripts/drive.py) | Restarts the app with intent extras, which connects and runs the named actions in order, then prints what the app logged. |
| [`rln.py`](scripts/rln.py) | Drives an RGB Lightning Node (RLN) as issuer and sender (`setup`, `send-rgb`, `refresh`), and regtest (`mine`, `fund`). |
| [`gateway.sh`](scripts/gateway.sh) | Starts a local `rln-gateway` against the regtest stack; `--debug` logs every wallet refresh result. |
| [`gateway-debug.mjs`](scripts/gateway-debug.mjs) | The `--debug` wrapper. |

Each `drive.py` run is one app session. `--wallet` names the wallet, and the app
keeps one Keystore seed per wallet ID. A wallet ID is bound to the first gateway
user it registers with, and reusing it with another user fails with
`wallet.invalid_state`. So use fresh wallet IDs whenever you create new gateway
users, as the `RUN` variable below does.

## Local regtest run

### 1. Start the regtest services

```sh
(cd ../rgb-lightning-node && ESPLORA=1 ./regtest.sh start)
```

This starts bitcoind, electrs (`:50001`), esplora (`:3002`) and the RGB proxy
(`:3000`). It also deletes `../rgb-lightning-node/data{core,index,ldk0,ldk1,ldk2,rgs}`
from any earlier regtest run.

### 2. Start the issuer node and issue an asset

```sh
mkdir -p "$W/rln"
../rgb-lightning-node/target/debug/rgb-lightning-node "$W/rln" \
    --daemon-listening-port 3202 --ldk-peer-listening-port 9902 \
    --network regtest --disable-authentication > "$W/rln.log" 2>&1 &
example-app/scripts/rln.py setup
# node 03…
# asset rgb:…         <- keep this as ASSET
```

`setup` initializes and unlocks the node, funds it with 1 BTC, creates colored
UTXOs and issues 1,000 units of an NIA asset (`--ticker`, `--name` and `--supply`
change it). Put the printed asset ID in `ASSET`.

### 3. Start the gateway and create two users

```sh
example-app/scripts/gateway.sh --debug > "$W/gateway.log" 2>&1 &
until curl -sf http://127.0.0.1:8490/v1/health > /dev/null; do sleep 1; done
for u in a b; do
    curl -s -X POST http://127.0.0.1:8490/v1/users \
        -H "x-operator-token: $(cat "$W/gateway/operator_token")" > "$W/user-$u.json"
done
RUN=$(date +%s)
drive() {
    who=$1; shift
    example-app/scripts/drive.py --gateway http://10.0.2.2:8490 --network regtest \
        --tip-url http://10.0.2.2:3002/blocks/tip/height \
        --token-file "$W/user-$who.json" --wallet "loc-$RUN-$who" \
        --mnemonic-out "$W/mnemonic-$who" "$@"
}
```

The emulator reaches the host's loopback as `10.0.2.2`. The trusted chain tip
comes from the local esplora, not from the gateway.

### 4. BTC payment

```sh
drive a address                    # address: bcrt1p…   <- A
example-app/scripts/rln.py fund <A> 0.01
drive a sync,balance               # vanilla settled=1000000
drive b address                    # address: bcrt1p…   <- B
drive a sendbtc recipient=<B> amount=50000
                                   # sent: txid …
example-app/scripts/rln.py mine 1
drive b sync,balance               # vanilla settled=50000
```

The device validates the gateway-prepared transaction against the request and
the trusted tip, signs it, and the gateway broadcasts it.

### 5. RGB receive: issuer → A, witness invoice

```sh
drive a witnessinvoice             # invoice: rgb:~/~/~/bcrt:wvout:…   <- A_INVOICE
example-app/scripts/rln.py send-rgb "$ASSET" 500 '<A_INVOICE>'
drive a sync,transfers,assets      # ReceiveWitness WaitingConfirmations; future=500
example-app/scripts/rln.py refresh # issuer sees A's ACK and broadcasts
example-app/scripts/rln.py mine 1
drive a sync,assets                # settled=500 spendable=500
```

Quote invoices: they contain `?` and `&`. For a witness invoice, `send-rgb` adds
1,000 sats for the output it creates for the receiver.

Every RGB payment takes three turns, because the sender (RLN and the SDK alike,
with `donation: false`) broadcasts only after the receiver ACKs:

1. The receiver syncs: it fetches the consignment, validates it and ACKs.
2. The sender refreshes: it sees the ACK and broadcasts.
3. After a block, the receiver's next sync settles the transfer.

The txid that `send-rgb` and `rgbsend` print is not on chain until step 2.

### 6. RGB send: A → B, signed by the SDK

```sh
drive a utxos                      # created 5 colored UTXO(s) in …
example-app/scripts/rln.py mine 1
drive b witnessinvoice             # invoice: rgb:~/~/~/bcrt:wvout:…   <- B_INVOICE
drive a rgbsend recipient='<B_INVOICE>' amount=100 assetId="$ASSET"
                                   # sent: txid … batchTransferIdx=…
drive b sync,transfers             # ReceiveWitness WaitingConfirmations, amounts=100
drive a sync,transfers             # Send WaitingConfirmations, amounts=400 (A's change),
                                   # listed after step 5's settled receive
example-app/scripts/rln.py mine 1
drive b sync,assets                # settled=100
drive a sync,assets                # settled=400
```

An RGB send is funded from colored UTXOs only: its fee, B's 1,000-sat witness
output and A's change all come from them, not from A's vanilla balance. The
1,000-sat output A received the asset on is not enough, hence `utxos` first.
Sync B before A: B's sync ACKs, and A's sync is what broadcasts.

### 7. Stop

```sh
kill %1 %2                         # the RLN and the gateway started above
(cd ../rgb-lightning-node && ./regtest.sh stop)
```

## Deployed signet run

The app's defaults already target UTEXO's signet environment. The gateway is
`https://minimal-gateway.utexo.com`, the network is `Network.SIGNET`, and the
trusted tip comes from `https://esplora-api.utexo.com/blocks/tip/height`. UTEXO's
signet is its own chain, not Bitcoin's public signet, even though its invoices
use the `sb` tag. Look its transactions up at
`https://esplora-api.utexo.com/tx/<txid>`; mempool.space does not know them.

Create the users with the deployment's operator token. Then use the same `drive`
helper without the local flags:

```sh
for u in a b; do
    curl -s -X POST https://minimal-gateway.utexo.com/v1/users \
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

- nothing mines blocks, so wait for a confirmation where the local run mines one;
- A must be funded by someone holding BTC and an asset on UTEXO's signet: BTC to
  `drive a address`, and the asset to `drive a witnessinvoice`, sent with
  `witness_data` for the receiving output;
- invoices end in `endpoints=rpcs://rgb-proxy.utexo.com/json-rpc`;
- UTEXO's proxy ACKs a consignment as soon as the sender uploads it, so the
  sender can broadcast on its next refresh without waiting for the receiver; the
  receiver still has to sync to accept the transfer.

To act as the sender yourself once A has BTC, run a second RLN on that chain,
fund it with `sendbtc` from A, and give it its own `RLN_URL`:

```sh
mkdir -p "$W/rln-signet"
../rgb-lightning-node/target/debug/rgb-lightning-node "$W/rln-signet" \
    --daemon-listening-port 3203 --ldk-peer-listening-port 9903 \
    --network signet --disable-authentication > "$W/rln-signet.log" 2>&1 &
export RLN_URL=http://127.0.0.1:3203
example-app/scripts/rln.py call /init '{"password":"local-password"}'
example-app/scripts/rln.py call /unlock '{"password":"local-password",
    "ldk_chain_sync":{"mode":"TransactionSync","config":{"indexer_url":"https://esplora-api.utexo.com"}},
    "indexer_url":"https://esplora-api.utexo.com",
    "proxy_endpoint":"rpcs://rgb-proxy.utexo.com/json-rpc","announce_addresses":[]}'
example-app/scripts/rln.py call /address '{}'
# fund it from A (drive a sendbtc …), wait for a confirmation, then:
example-app/scripts/rln.py call /createutxos '{"up_to":false,"num":4,"size":20000,"fee_rate":2,"skip_sync":false}'
# wait until `rln.py balance` shows colored settled=80000, then:
example-app/scripts/rln.py call /issueassetnia '{"ticker":"TST","name":"Test","precision":0,"amounts":[1000]}'
example-app/scripts/rln.py send-rgb <asset> 500 '<A_INVOICE>'
example-app/scripts/rln.py refresh
```

`/init` prints the node's mnemonic; it is a throwaway test node.

## Troubleshooting

**A spend fails with `policy.rejected`.** The trusted chain tip is from the
wrong chain or older than 300 seconds. On UTEXO's signet a public-signet tip is
hundreds of thousands of blocks behind, and the signer rejects the prepared
transaction.

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

**The issuer's `send-rgb` fails with `InsufficientAssets` right after an earlier
send.** Its change is not spendable until that transfer settles. Run
`rln.py refresh`, `rln.py mine 1`, then `rln.py refresh` again.

**An incoming RGB transfer stays `WaitingCounterparty`.** The receiver has not
accepted a consignment. Work through these:

1. Ask the proxy whether the sender posted one, using the recipient ID from the
   invoice (the `…:wvout:…` or `…:utxob:…` part):

   ```sh
   for m in consignment.get ack.get; do
       curl -s -X POST <proxy>/json-rpc -H 'content-type: application/json' \
           -d "{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"method\":\"$m\",\"params\":{\"recipient_id\":\"<id>\"}}"
       echo
   done
   ```

   UTEXO's proxy validates a consignment on upload and posts `ack: true` itself
   (`validated: true`). An ACK there therefore does **not** mean the receiver
   accepted it.
2. The gateway discards rgb-lib's refresh result, so the receiver's failure is
   invisible. On the local stack, `gateway.sh --debug` logs it. Search
   `"$W/gateway.log"` for `"debug":"refresh"`; an entry looks like
   `{"<batchTransferIdx>":{"updatedStatus":…,"failure":…}}`.
3. rgb-lib also writes a log at
   `<gateway data dir>/wallets/<userId>/<fingerprint>/log`. A
   `Skipping transport endpoint` line means the consignment fetch from the
   proxy returned nothing **or failed**. rgb-lib treats both the same way.

To debug a deployed environment's receive path, run `gateway.sh --debug` locally
against that environment's indexer and proxy. Restore a wallet from the same
mnemonic into it with `drive.py --restore-from`, using a new wallet ID:

```sh
GATEWAY_PORT=8491 GATEWAY_DATA_DIR="$W/mirror" GATEWAY_BITCOIN_NETWORK=Signet \
ESPLORA_URL=https://esplora-api.utexo.com GATEWAY_WALLET_INDEXER_URL=https://esplora-api.utexo.com \
RGB_PROXY_URL=rpcs://rgb-proxy.utexo.com/json-rpc \
    example-app/scripts/gateway.sh --debug > "$W/mirror.log" 2>&1 &
```

**Point `GATEWAY_WALLET_INDEXER_URL` at an esplora REST URL, not an
electrs-esplora Electrum port.** Blockstream/mempool electrs answers Electrum
verbose-transaction requests with `verbose transactions are currently
unsupported`. rgb-lib's Electrum resolver needs those requests, for its
connect-time chain check and for validating received consignments. A gateway
with such an indexer either fails to bring wallets online (`InvalidIndexer:
resolver is for another chain-network pair`) or, with a build that skips that
check, syncs BTC but never accepts an RGB receive.

**Known issue (2026-10-15).** `minimal-gateway.utexo.com` never accepted
incoming consignments; its transfers stayed `WaitingCounterparty`. A local
gateway with the same code, rgb-lib (`0.3.0-beta.18`), network, indexer
(`https://esplora-api.utexo.com`) and proxy accepted its output of the same
transaction on the first sync, so the cause is in the deployed host's
environment. The leading suspect is a container without system CA certificates.
Node and the esplora client bundle their own roots, but rgb-lib's proxy client
trusts only the system store, so BTC works while every consignment fetch fails
silently. Other suspects are an `HTTPS_PROXY` setting, or the network path from
the host to `rgb-proxy.utexo.com`. Check inside the gateway container:

```sh
ls -la /etc/ssl/certs/ca-certificates.crt
env | grep -i -E '^(https?|no)_proxy|SSL_CERT'
grep -h 'Skipping transport\|Network error\|WARN\|ERRO' "$GATEWAY_WALLETS_DIR"/<userId>/*/log | tail
```
