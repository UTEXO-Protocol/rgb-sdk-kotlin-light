#!/usr/bin/env python3
"""Run example-app actions on a connected emulator/device and print the app's log.

Launches MainActivity with intent extras (`am start -S`), lets it connect and run
the named actions in order, then prints the lines it logged under the
`UtexoExample` tag until it reports `script done`.

Examples:
  drive.py --token-file user-a.json --wallet gw-a address,balance
  drive.py --token-file user-a.json --wallet gw-a sendbtc recipient=tb1p... amount=50000
  drive.py --gateway http://10.0.2.2:8490 --network regtest \\
      --tip-url http://10.0.2.2:3003/blocks/tip/height \\
      --token-file local-a.json --wallet loc-a sync,assets

Fields passed as `name=value` become intent extras: recipient, amount, assetId.
A mnemonic the app prints on first run is written to --mnemonic-out (mode 0600)
and redacted from the output.
"""
import argparse
import json
import os
import shlex
import shutil
import subprocess
import sys
import time

ACTIVITY = "com.utexo.rgb.sdk.example/.MainActivity"
TAG = "UtexoExample"
MNEMONIC_PREFIX = "BACK THIS UP: "


def adb_path() -> str:
    sdks = [os.environ.get("ANDROID_HOME"), os.environ.get("ANDROID_SDK_ROOT"),
            os.path.expanduser("~/Android/Sdk"), os.path.expanduser("~/Library/Android/sdk")]
    for sdk in filter(None, sdks):
        candidate = os.path.join(sdk, "platform-tools", "adb")
        if os.path.exists(candidate):
            return candidate
    found = shutil.which("adb")
    if not found:
        sys.exit("adb not found: set ANDROID_HOME or put platform-tools on PATH")
    return found


def read_token(args: argparse.Namespace) -> str:
    if args.token_file:
        text = open(args.token_file).read().strip()
        # Accept either a bare token or the JSON that POST /v1/users returns.
        return json.loads(text)["token"] if text.startswith("{") else text
    token = os.environ.get("UTEXO_TOKEN")
    if not token:
        sys.exit("pass --token-file or set UTEXO_TOKEN")
    return token


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("actions", help="comma-separated actions, e.g. sync,balance")
    parser.add_argument("fields", nargs="*", help="name=value intent extras (recipient, amount, assetId)")
    gateway = os.environ.get("UTEXO_GATEWAY_URL")
    parser.add_argument("--gateway", default=gateway, required=gateway is None,
                        help="gateway base URL, e.g. https://your-gateway.example (default: $UTEXO_GATEWAY_URL)")
    parser.add_argument("--token-file", help="bare token, or the JSON returned by POST /v1/users")
    parser.add_argument("--wallet", required=True, help="wallet ID; one Keystore seed per ID")
    parser.add_argument("--network", help="signet (app default), regtest, testnet, mainnet")
    parser.add_argument("--tip-url", help="an HTTP /blocks/tip/height for the gateway's chain (tip.mjs on regtest)")
    parser.add_argument("--restore-from", help="file holding a mnemonic to restore on first connect")
    parser.add_argument("--mnemonic-out", help="where to save a newly generated mnemonic")
    parser.add_argument("--timeout", type=int, default=420, help="seconds to wait for the script to finish")
    args = parser.parse_args()

    extras = {"gatewayUrl": args.gateway, "bearerToken": read_token(args), "walletId": args.wallet, "run": args.actions}
    if args.network:
        extras["network"] = args.network
    if args.tip_url:
        extras["tipUrl"] = args.tip_url
    if args.restore_from:
        extras["provisionedMnemonic"] = "_".join(open(args.restore_from).read().split())
    for field in args.fields:
        name, sep, value = field.partition("=")
        if not sep:
            sys.exit(f"expected name=value, got {field!r}")
        extras[name] = value

    adb = adb_path()
    remote = f"am start -S -W -n {ACTIVITY} " + " ".join(f"-e {k} {shlex.quote(v)}" for k, v in extras.items())
    subprocess.run([adb, "logcat", "-c"], check=True)
    subprocess.run([adb, "shell", remote], check=True, stdout=subprocess.DEVNULL)

    deadline = time.time() + args.timeout
    lines: list[str] = []
    while True:
        out = subprocess.run([adb, "logcat", "-d", "-v", "raw", "-s", f"{TAG}:I"],
                             check=True, capture_output=True, text=True).stdout
        lines = [l for l in out.splitlines() if l and not l.startswith("---------")]
        if "script done" in lines:
            break
        if time.time() > deadline:
            lines.append(f"[drive.py] no 'script done' after {args.timeout}s")
            break
        time.sleep(3)

    for line in lines:
        if line.startswith(MNEMONIC_PREFIX):
            if args.mnemonic_out:
                fd = os.open(args.mnemonic_out, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
                with os.fdopen(fd, "w") as f:
                    f.write(line[len(MNEMONIC_PREFIX):])
                line = f"{MNEMONIC_PREFIX}[saved to {args.mnemonic_out}]"
            else:
                line = f"{MNEMONIC_PREFIX}[redacted; pass --mnemonic-out to keep it]"
        print(line)


if __name__ == "__main__":
    main()
