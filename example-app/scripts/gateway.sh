#!/usr/bin/env bash
#
# Start a local rln-gateway against rgb-lightning-node's regtest stack.
#
#   gateway.sh            run the gateway as built (dist/main.js)
#   gateway.sh --debug    same, but log every wallet refresh result (gateway-debug.mjs)
#
# The operator token is generated once into $GATEWAY_DATA_DIR/operator_token.
# Every gateway setting below can be overridden from the environment, e.g. to
# point a debug gateway at a deployed environment's indexer and proxy.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
workspace="$(cd "$here/../../.." && pwd)"
gateway_dir="${RLN_GATEWAY_DIR:-$workspace/rln-gateway/packages/gateway}"
data_dir="${GATEWAY_DATA_DIR:-${TMPDIR:-/tmp}/utexo-payment-test/gateway}"

[ -f "$gateway_dir/dist/main.js" ] || {
    echo "no $gateway_dir/dist/main.js; run 'pnpm install && pnpm build' in rln-gateway" >&2
    exit 1
}
mkdir -p "$data_dir/wallets"
token_file="$data_dir/operator_token"
if [ ! -s "$token_file" ]; then
    (umask 077; head -c 24 /dev/urandom | od -An -tx1 | tr -d ' \n' > "$token_file")
fi

entry="dist/main.js"
if [ "${1:-}" = "--debug" ]; then
    entry="$here/gateway-debug.mjs"
fi
echo "operator token: $token_file" >&2
echo "data dir:       $data_dir" >&2

cd "$gateway_dir"
exec env \
    GATEWAY_PORT="${GATEWAY_PORT:-8490}" \
    RLN_URL="${RLN_URL:-http://127.0.0.1:3202}" \
    RLN_ADMIN_TOKEN="${RLN_ADMIN_TOKEN:-}" \
    ESPLORA_URL="${ESPLORA_URL:-http://127.0.0.1:3002}" \
    RGB_PROXY_URL="${RGB_PROXY_URL:-rpc://127.0.0.1:3000/json-rpc}" \
    GATEWAY_WALLET_INDEXER_URL="${GATEWAY_WALLET_INDEXER_URL:-http://127.0.0.1:3002}" \
    GATEWAY_BITCOIN_NETWORK="${GATEWAY_BITCOIN_NETWORK:-Regtest}" \
    GATEWAY_SQLITE_PATH="$data_dir/gateway.sqlite" \
    GATEWAY_WALLETS_DIR="$data_dir/wallets" \
    GATEWAY_OPERATOR_TOKEN="$(cat "$token_file")" \
    RLN_GATEWAY_DIST="$gateway_dir/dist" \
    node "$entry"
