#!/usr/bin/env python3
"""Drive a test RGB Lightning Node (RLN) as the asset issuer and sender.

Commands:
  setup [--ticker T --name N --supply S]   init + unlock a fresh regtest node, fund it,
                                           create colored UTXOs, issue an NIA asset
  send-rgb ASSET_ID AMOUNT INVOICE         send on chain to an RGB invoice; adds
                                           witness sats when the invoice needs them
  refresh                                  refresh the node's RGB transfers
  balance [ASSET_ID]                       BTC balance, or one asset's balance
  call PATH [JSON]                         raw RLN call (GET without JSON, else POST)
  mine N                                   regtest: mine N blocks
  fund ADDRESS BTC                         regtest: send BTC from the miner, mine 1

Environment:
  RLN_URL       node API (default http://127.0.0.1:3202)
  RLN_REPO      rgb-lightning-node checkout holding regtest.sh
                (default: ../rgb-lightning-node next to this repository)
  RLN_PASSWORD  node password used by `setup` (default local-password)

`setup` assumes the regtest stack from `ESPLORA=1 ./regtest.sh start` and a node
started with `--network regtest --disable-authentication`; see
example-app/PAYMENT_TESTING.md.
"""
import argparse
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

RLN_URL = os.environ.get("RLN_URL", "http://127.0.0.1:3202")
RLN_REPO = Path(os.environ.get("RLN_REPO", Path(__file__).resolve().parents[3] / "rgb-lightning-node"))
PASSWORD = os.environ.get("RLN_PASSWORD", "local-password")
WITNESS_SATS = 1_000
FEE_RATE = 2


def call(path: str, body=None):
    data = None if body is None else json.dumps(body).encode()
    request = urllib.request.Request(RLN_URL + path, data=data, method="GET" if body is None else "POST",
                                     headers={"content-type": "application/json"})
    try:
        with urllib.request.urlopen(request, timeout=600) as response:
            text = response.read().decode()
    except urllib.error.HTTPError as error:
        raise SystemExit(f"RLN {path} -> {error.code}: {error.read().decode()}")
    return json.loads(text) if text else None


def regtest(*args: str) -> str:
    return subprocess.run(["./regtest.sh", *args], cwd=RLN_REPO, check=True,
                          capture_output=True, text=True).stdout.strip()


def wait_for(description: str, probe, timeout: float = 120, interval: float = 1):
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            result = probe()
            if result:
                return result
        except (SystemExit, OSError):
            pass
        time.sleep(interval)
    raise SystemExit(f"timed out waiting for {description}")


def http_up() -> bool:
    try:
        urllib.request.urlopen(RLN_URL + "/nodeinfo", timeout=5)
    except urllib.error.HTTPError:
        return True  # a fresh node answers 403 until it is initialized
    except OSError:
        return False
    return True


def setup(ticker: str, name: str, supply: int) -> None:
    wait_for("RLN HTTP", http_up)
    call("/init", {"password": PASSWORD})
    call("/unlock", {
        "password": PASSWORD,
        "ldk_chain_sync": {"mode": "BlockSync", "config": {
            "bitcoind_rpc_username": "user", "bitcoind_rpc_password": "password",
            "bitcoind_rpc_host": "localhost", "bitcoind_rpc_port": 18443}},
        "indexer_url": "127.0.0.1:50001",
        "proxy_endpoint": "rpc://127.0.0.1:3000/json-rpc",
        "announce_addresses": [],
    })
    print("node", wait_for("unlock", lambda: call("/nodeinfo")["pubkey"]))
    regtest("sendtoaddress", call("/address", {})["address"], "1")
    regtest("mine", "1")
    wait_for("funds", lambda: call("/btcbalance", {"skip_sync": False})["vanilla"]["settled"] >= 100_000_000)
    call("/createutxos", {"up_to": False, "num": 4, "size": 32_500, "fee_rate": FEE_RATE, "skip_sync": False})
    regtest("mine", "1")
    issued = call("/issueassetnia", {"ticker": ticker, "name": name, "precision": 0, "amounts": [supply]})
    print("asset", issued["asset"]["asset_id"])


def send_rgb(asset_id: str, amount: int, invoice: str) -> None:
    decoded = call("/decodergbinvoice", {"invoice": invoice})
    recipient = {
        "recipient_id": decoded["recipient_id"],
        "assignment": {"type": "Fungible", "value": amount},
        "transport_endpoints": decoded["transport_endpoints"],
    }
    if decoded["recipient_type"] == "Witness":
        # The sender creates the receiver's output for a witness invoice.
        recipient["witness_data"] = {"amount_sat": WITNESS_SATS, "blinding": None}
    sent = call("/sendrgb", {"donation": False, "fee_rate": FEE_RATE, "min_confirmations": 1,
                             "skip_sync": False, "recipient_map": {asset_id: [recipient]}})
    print("txid", sent["txid"])


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    commands = parser.add_subparsers(dest="command", required=True)
    p = commands.add_parser("setup")
    p.add_argument("--ticker", default="LOC")
    p.add_argument("--name", default="Local Asset")
    p.add_argument("--supply", type=int, default=1000)
    p = commands.add_parser("send-rgb")
    p.add_argument("asset_id")
    p.add_argument("amount", type=int)
    p.add_argument("invoice")
    commands.add_parser("refresh")
    p = commands.add_parser("balance")
    p.add_argument("asset_id", nargs="?")
    p = commands.add_parser("call")
    p.add_argument("path")
    p.add_argument("body", nargs="?")
    p = commands.add_parser("mine")
    p.add_argument("blocks")
    p = commands.add_parser("fund")
    p.add_argument("address")
    p.add_argument("btc")
    args = parser.parse_args()

    if args.command == "setup":
        setup(args.ticker, args.name, args.supply)
    elif args.command == "send-rgb":
        send_rgb(args.asset_id, args.amount, args.invoice)
    elif args.command == "refresh":
        print(json.dumps(call("/refreshtransfers", {"filter": [], "skip_sync": False})))
    elif args.command == "balance":
        if args.asset_id:
            print(json.dumps(call("/assetbalance", {"asset_id": args.asset_id})))
        else:
            print(json.dumps(call("/btcbalance", {"skip_sync": False})))
    elif args.command == "call":
        print(json.dumps(call(args.path, json.loads(args.body) if args.body else None), indent=1))
    elif args.command == "mine":
        regtest("mine", args.blocks)
    elif args.command == "fund":
        print(regtest("sendtoaddress", args.address, args.btc))
        regtest("mine", "1")


if __name__ == "__main__":
    main()
