#!/usr/bin/env node
// Serve the regtest chain tip over HTTP, for the example app's trusted chain
// context. The gateway's regtest stack publishes only an Electrum indexer
// (electrs), which the app does not speak, so this answers
// `GET /blocks/tip/height` with the height electrs reports. It reads the tip
// from the indexer the gateway uses, never from the gateway.
//
//   tip.mjs                listen on 127.0.0.1:$TIP_PORT
//
// Environment:
//   ELECTRUM_URL   the indexer, as the gateway takes it (default
//                  tcp://127.0.0.1:50001, the regtest stack's electrs)
//   TIP_PORT       port to listen on (default 3003)
//
// Regtest only: on a real chain, read the tip from an indexer you trust.
import { createServer } from 'node:http';
import { connect } from 'node:net';

const ELECTRUM_URL = process.env.ELECTRUM_URL ?? 'tcp://127.0.0.1:50001';
const PORT = Number(process.env.TIP_PORT ?? 3003);
const endpoint = /^tcp:\/\/([^:/]+):(\d+)$/.exec(ELECTRUM_URL);
if (endpoint === null) {
  console.error(`tip: ELECTRUM_URL must be tcp://host:port, got: ${ELECTRUM_URL}`);
  process.exit(2);
}
const [, host, port] = endpoint;

/** One Electrum `blockchain.headers.subscribe` round trip on a fresh connection. */
function tipHeight() {
  return new Promise((resolve, reject) => {
    const socket = connect({ host, port: Number(port) });
    let received = '';
    socket.setEncoding('utf8');
    socket.setTimeout(10_000, () => socket.destroy(new Error('electrum request timed out')));
    socket.on('error', reject);
    socket.on('connect', () => {
      socket.write(
        `${JSON.stringify({ jsonrpc: '2.0', id: 1, method: 'blockchain.headers.subscribe', params: [] })}\n`,
      );
    });
    socket.on('data', (chunk) => {
      received += chunk;
      const end = received.indexOf('\n');
      if (end === -1) return;
      socket.destroy();
      try {
        const height = JSON.parse(received.slice(0, end)).result?.height;
        if (!Number.isSafeInteger(height) || height < 0) throw new Error('no height in the reply');
        resolve(height);
      } catch (error) {
        reject(error);
      }
    });
  });
}

createServer(async (request, response) => {
  if (request.method !== 'GET' || request.url !== '/blocks/tip/height') {
    response.writeHead(404).end();
    return;
  }
  try {
    response.writeHead(200, { 'content-type': 'text/plain' }).end(String(await tipHeight()));
  } catch (error) {
    console.error(`tip: ${error.message}`);
    response.writeHead(502).end();
  }
}).listen(PORT, '127.0.0.1', () => {
  console.error(`tip: http://127.0.0.1:${PORT}/blocks/tip/height from ${ELECTRUM_URL}`);
});
