// Runs the rln-gateway entrypoint unchanged, except that every wallet refresh
// logs the per-transfer result rgb-lib returns. The gateway discards that
// result, so without this a receive that fails (or never finds its
// consignment) is invisible. Started by `gateway.sh --debug`, which sets
// RLN_GATEWAY_DIST to the gateway's dist directory.
import { pathToFileURL } from 'node:url';
import { join } from 'node:path';

const dist = process.env.RLN_GATEWAY_DIST;
if (!dist) throw new Error('RLN_GATEWAY_DIST must point at rln-gateway/packages/gateway/dist');
const load = (path) => import(pathToFileURL(join(dist, path)).href);

const { NativeWalletBackend } = await load('wallets/rgblib.js');
const open = NativeWalletBackend.prototype.open;
NativeWalletBackend.prototype.open = async function (identity) {
  const handle = await open.call(this, identity);
  handle.refresh = async function () {
    const result = this.call('refresh', () =>
      this.lib.rgblib_refresh(this.wallet, this.online, null, JSON.stringify([]), false),
    );
    // {"<batchTransferIdx>": {"updatedStatus": ..., "failure": ...}}
    console.error(JSON.stringify({ debug: 'refresh', userId: identity.userId, result }));
  };
  return handle;
};

await load('main.js');
