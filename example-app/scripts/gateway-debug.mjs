// Runs the rln-gateway entrypoint unchanged, except that every wallet refresh
// logs the per-transfer result rgb-node-api returns. The gateway discards that
// result, so without this a receive that fails (or never finds its
// consignment) is invisible. Started by `gateway.sh --debug`, which sets
// GATEWAY_DIST to the gateway's dist directory.
import { pathToFileURL } from 'node:url';
import { join } from 'node:path';

const dist = process.env.GATEWAY_DIST;
if (!dist) throw new Error('GATEWAY_DIST must point at rln-gateway/packages/gateway/dist');
const load = (path) => import(pathToFileURL(join(dist, path)).href);

const { RgbNodeApiWalletBackend } = await load('wallets/rgbnodeapi.js');
const open = RgbNodeApiWalletBackend.prototype.open;
RgbNodeApiWalletBackend.prototype.open = async function (identity) {
  const handle = await open.call(this, identity);
  handle.refresh = async function () {
    // `call` is the handle's own POST /wallet/<route>; it is private only to TypeScript.
    const result = await this.call('refresh', 'refresh');
    // {"<batchTransferIdx>": {"updated_status": ..., "failure": ...}}
    console.error(JSON.stringify({ debug: 'refresh', userId: identity.userId, result }));
  };
  return handle;
};

await load('main.js');
