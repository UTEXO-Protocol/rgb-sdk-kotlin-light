#!/usr/bin/env bash
#
# Start a local lightsdk-gateway against its regtest stack (regtest/ in the
# gateway repository: bitcoind, electrs, esplora, the RGB proxy and rgb-node-api).
#
#   gateway.sh            run the gateway as built (dist/main.js)
#
# The operator token is generated once into $GATEWAY_DATA_DIR/operator_token.
# Every gateway setting below can be overridden from the environment, e.g. to
# point the gateway at another rgb-node-api, indexer or proxy.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
workspace="$(cd "$here/../../.." && pwd)"
gateway_repo="${LIGHTSDK_GATEWAY_REPO:-$workspace/rln-gateway}"
gateway_dir="$gateway_repo/packages/gateway"
data_dir="${GATEWAY_DATA_DIR:-${TMPDIR:-/tmp}/utexo-payment-test/gateway}"

if [ "${1:-}" = "--debug" ]; then
    # The gateway no longer runs rgb-lib in-process, so there is no refresh
    # result to hook. rgb-node-api logs it instead; see PAYMENT_TESTING.md.
    echo "--debug was removed: read rgb-node-api's log instead (see PAYMENT_TESTING.md)" >&2
    exit 2
fi
[ -f "$gateway_dir/dist/main.js" ] || {
    echo "no $gateway_dir/dist/main.js; run 'pnpm install && pnpm build' in $gateway_repo" >&2
    exit 1
}
mkdir -p "$data_dir"
token_file="$data_dir/operator_token"
if [ ! -s "$token_file" ]; then
    (umask 077; head -c 24 /dev/urandom | od -An -tx1 | tr -d ' \n' > "$token_file")
fi

echo "operator token: $token_file" >&2
echo "data dir:       $data_dir" >&2

cd "$gateway_dir"
# RGB_PROXY_URL must be the proxy exactly as rgb-node-api names it in invoices:
# on the regtest stack rgb-node-api reaches it as `proxy` on the compose network.
exec env \
    GATEWAY_PORT="${GATEWAY_PORT:-8490}" \
    RGB_NODE_API_URL="${RGB_NODE_API_URL:-http://127.0.0.1:34000}" \
    ESPLORA_URL="${ESPLORA_URL:-http://127.0.0.1:3002}" \
    RGB_PROXY_URL="${RGB_PROXY_URL:-rpc://proxy:3000/json-rpc}" \
    GATEWAY_BITCOIN_NETWORK="${GATEWAY_BITCOIN_NETWORK:-Regtest}" \
    GATEWAY_SQLITE_PATH="$data_dir/gateway.sqlite" \
    GATEWAY_OPERATOR_TOKEN="$(cat "$token_file")" \
    node dist/main.js
