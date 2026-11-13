#!/usr/bin/env node
// Drive a test RGB issuer and sender through rgb-node-api.
//
// The issuer is a wallet on an rgb-node-api whose keys this script holds and
// signs with: it stands for the outside world, not for a gateway user. On the
// lightsdk-gateway regtest stack `setup` does everything; on another chain, point
// RGB_NODE_API_URL at an rgb-node-api for that chain and use `address`,
// `create-utxos` and `issue` instead.
//
// Commands:
//   setup [--ticker T --name N --supply S]   regtest: fund a fresh issuer wallet, then
//                                            create-utxos and issue
//   address                                  a new address to fund the issuer on
//   create-utxos [--num N --size SATS]       create colored UTXOs (default 4 × 32,500)
//   issue [--ticker T --name N --supply S]   issue an NIA asset (default USDT, 1,000)
//   send-rgb ASSET_ID AMOUNT INVOICE         send on chain to an RGB invoice; adds
//                                            witness sats when the invoice needs them
//   refresh                                  refresh the issuer's RGB transfers
//   balance [ASSET_ID]                       BTC balance, or one asset's balance
//   call ROUTE [JSON]                        raw rgb-node-api call: POST /wallet/ROUTE
//   mine N                                   regtest: mine N blocks
//   fund ADDRESS BTC                         regtest: send BTC from the miner, mine 1
//
// Environment:
//   LIGHTSDK_GATEWAY_REPO  checkout of the gateway repository, built with
//                          `pnpm install && pnpm build` (default: its checkout next
//                          to this repository, under its GitHub name); provides
//                          regtest/regtest.sh and the signing and invoice-decoding
//                          libraries used here
//   RGB_NODE_API_URL       the issuer's rgb-node-api (default http://127.0.0.1:34000,
//                          the regtest stack's)
//   ISSUER_STATE           file holding the issuer's throwaway mnemonic
//                          (default $TMPDIR/utexo-payment-test/issuer.json)
//
// See example-app/PAYMENT_TESTING.md.
import { execFileSync } from 'node:child_process';
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const GATEWAY_REPO = resolve(
  process.env.LIGHTSDK_GATEWAY_REPO ?? join(HERE, '..', '..', '..', 'rln-gateway'),
);
const NODE_API = process.env.RGB_NODE_API_URL ?? 'http://127.0.0.1:34000';
const STATE =
  process.env.ISSUER_STATE ?? join(process.env.TMPDIR ?? '/tmp', 'utexo-payment-test', 'issuer.json');
const REGTEST = join(GATEWAY_REPO, 'regtest', 'regtest.sh');
const WITNESS_SATS = 1_000;
const FEE_RATE = 2;
// Regtest serializes extended keys with the testnet version bytes.
const TESTNET_VERSIONS = { private: 0x04358394, public: 0x043587cf };

function die(message) {
  console.error(`issuer: ${message}`);
  process.exit(1);
}

/**
 * The invoice's RGB proxy protocol 1.0 recipient fields, which the client SDK's
 * invoice decoder skips. rgb-node-api needs `pk` to encrypt the consignment to
 * the recipient. A pre-1.0 invoice has none of them.
 */
function proxyV1Recipient(invoiceText, expiryTimestamp) {
  const query = new URLSearchParams(invoiceText.split('?')[1] ?? '');
  const publicKey = query.get('pk');
  const enc = query.get('enc');
  if (publicKey === null && enc === null) return {};
  const encryption = enc === null ? null : ({ 0: 'Unsupported', 1: 'Optional' }[enc] ?? die(`invalid enc: ${enc}`));
  return {
    ...(publicKey === null ? {} : { public_key: publicKey }),
    ...(expiryTimestamp === null ? {} : { expiration_timestamp: expiryTimestamp }),
    ...(encryption === null ? {} : { encryption }),
  };
}

const gatewayPackage = join(GATEWAY_REPO, 'packages', 'gateway', 'package.json');
const clientSdk = join(GATEWAY_REPO, 'packages', 'client-sdk', 'dist', 'index.js');
if (!existsSync(gatewayPackage) || !existsSync(clientSdk)) {
  die(`no built gateway repository at ${GATEWAY_REPO}; run 'pnpm install && pnpm build' there`);
}
const require = createRequire(gatewayPackage);
const { HDKey } = require('@scure/bip32');
const { generateMnemonic, mnemonicToSeedSync } = require('@scure/bip39');
const { wordlist } = require('@scure/bip39/wordlists/english.js');
const { base64 } = require('@scure/base');
const { Transaction } = require('@scure/btc-signer');
const { decodeRgbInvoice } = await import(pathToFileURL(clientSdk).href);

/** The issuer's mnemonic, generated on first use. A throwaway regtest key. */
function mnemonic() {
  if (!existsSync(STATE)) {
    mkdirSync(dirname(STATE), { recursive: true });
    writeFileSync(STATE, JSON.stringify({ mnemonic: generateMnemonic(wordlist) }), { mode: 0o600 });
  }
  return JSON.parse(readFileSync(STATE, 'utf8')).mnemonic;
}

/** BIP-86 account xpubs on both keychains, as rgb-lib derives them on regtest. */
function wallet() {
  const seed = mnemonicToSeedSync(mnemonic());
  const master = HDKey.fromMasterSeed(seed, TESTNET_VERSIONS);
  return {
    master,
    headers: {
      'xpub-van': master.derive("m/86'/1'/0'").publicExtendedKey,
      'xpub-col': master.derive("m/86'/827167'/0'").publicExtendedKey,
      'master-fingerprint': master.fingerprint.toString(16).padStart(8, '0'),
    },
  };
}

async function call(route, body = {}) {
  const response = await fetch(`${NODE_API}/wallet/${route}`, {
    method: 'POST',
    headers: { 'content-type': 'application/json', ...wallet().headers },
    body: JSON.stringify(body),
  });
  const text = await response.text();
  if (!response.ok) die(`POST /wallet/${route} -> ${response.status}: ${text}`);
  return JSON.parse(text);
}

/** Key-path sign every input whose key origin is ours, then finalize. */
function sign(psbt) {
  const { master } = wallet();
  const tx = Transaction.fromPSBT(base64.decode(psbt), {
    allowUnknownInputs: true,
    allowUnknownOutputs: true,
  });
  for (let index = 0; index < tx.inputsLength; index += 1) {
    for (const [, origin] of tx.getInput(index).tapBip32Derivation ?? []) {
      if (origin.der.fingerprint !== master.fingerprint) continue;
      let key = master;
      for (const step of origin.der.path) key = key.deriveChild(step);
      tx.signIdx(key.privateKey, index);
    }
  }
  tx.finalize();
  return base64.encode(tx.toPSBT());
}

/** rgb-node-api's `<op>begin` → sign here → `<op>end`. */
async function beginSignEnd(op, body) {
  const { psbt } = await call(`${op}begin`, body);
  return call(`${op}end`, { signed_psbt: sign(psbt) });
}

function regtest(...args) {
  if (!existsSync(REGTEST)) die(`no ${REGTEST}`);
  return execFileSync(REGTEST, args, { encoding: 'utf8' }).trim();
}

async function waitFor(what, probe, timeoutMs = 90_000) {
  const deadline = Date.now() + timeoutMs;
  for (;;) {
    if (await probe()) return;
    if (Date.now() > deadline) die(`timed out waiting for ${what}`);
    await new Promise((done) => setTimeout(done, 2_000));
  }
}

function option(args, name, fallback) {
  const at = args.indexOf(`--${name}`);
  return at === -1 ? fallback : args[at + 1];
}

async function createUtxos(args) {
  const created = await beginSignEnd('createutxos', {
    up_to: false,
    num: Number(option(args, 'num', '4')),
    size: Number(option(args, 'size', '32500')),
    fee_rate: FEE_RATE,
  });
  return created.created;
}

async function issue(args) {
  const issued = await call('issueassetnia', {
    ticker: option(args, 'ticker', 'USDT'),
    name: option(args, 'name', 'Tether'),
    precision: 0,
    amounts: [Number(option(args, 'supply', '1000'))],
  });
  return issued.asset_id;
}

const [command, ...args] = process.argv.slice(2);
switch (command) {
  case 'setup': {
    const { address } = await call('address');
    regtest('sendtoaddress', address, '1');
    regtest('mine', '1');
    await waitFor('issuer funds', async () => (await call('btcbalance')).vanilla.settled >= 90_000_000);
    const created = await createUtxos([]);
    regtest('mine', '1');
    await waitFor('issuer colored UTXOs', async () => {
      await call('refresh');
      const unspents = await call('listunspents');
      return unspents.filter((u) => u.utxo.colorable && u.utxo.exists).length >= created;
    });
    const assetId = await issue(args);
    console.log('issuer', wallet().headers['master-fingerprint']);
    console.log('asset', assetId);
    break;
  }
  case 'address':
    console.log('address', (await call('address')).address);
    break;
  case 'create-utxos':
    console.log('created', await createUtxos(args));
    break;
  case 'issue':
    console.log('asset', await issue(args));
    break;
  case 'send-rgb': {
    const [assetId, amount, invoiceText] = args;
    if (invoiceText === undefined) die('usage: send-rgb ASSET_ID AMOUNT INVOICE');
    const invoice = decodeRgbInvoice(invoiceText);
    const sent = await beginSignEnd('send', {
      recipient_map: {
        [assetId]: [
          {
            recipient_id: invoice.recipientId,
            witness_data:
              invoice.beneficiaryKind === 'witness'
                ? { amount_sat: WITNESS_SATS, blinding: null }
                : null,
            amount: Number(amount),
            transport_endpoints: invoice.transportEndpoints,
            ...proxyV1Recipient(invoiceText, invoice.expiryTimestamp),
          },
        ],
      },
      donation: false,
      fee_rate: FEE_RATE,
      min_confirmations: 1,
      // Proxy protocol 1.0: the operation must expire at least 600 s before the
      // invoice, so its TX is only released while the recipient still waits for it.
      expiration_timestamp: Math.min(
        Math.floor(Date.now() / 1000) + 3_600,
        (invoice.expiryTimestamp ?? Infinity) - 600,
      ),
    });
    console.log('txid', sent.txid);
    break;
  }
  case 'refresh':
    console.log(JSON.stringify(await call('refresh')));
    break;
  case 'balance':
    console.log(
      JSON.stringify(
        args[0] === undefined
          ? await call('btcbalance')
          : await call('assetbalance', { asset_id: args[0] }),
      ),
    );
    break;
  case 'call':
    if (args[0] === undefined) die('usage: call ROUTE [JSON]');
    console.log(JSON.stringify(await call(args[0], args[1] ? JSON.parse(args[1]) : {}), null, 1));
    break;
  case 'mine':
    regtest('mine', args[0] ?? '1');
    break;
  case 'fund':
    if (args[1] === undefined) die('usage: fund ADDRESS BTC');
    console.log(regtest('sendtoaddress', args[0], args[1]));
    regtest('mine', '1');
    break;
  default:
    die(
      'usage: issuer.mjs setup|address|create-utxos|issue|send-rgb|refresh|balance|call|mine|fund' +
        ' (see the header)',
    );
}
