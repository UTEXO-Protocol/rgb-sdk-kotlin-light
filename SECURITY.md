# Security model

Report security issues privately to the UTEXO security contact. Do not open a
public issue containing wallet identifiers, xpubs, invoices, PSBTs, or keys.

## Secrets

- Mnemonics, seeds, and private keys never go to `WalletBackend`.
- `WalletKeyProvider` should decrypt a seed only after Android Keystore-backed
  user authentication. A mnemonic itself generally cannot live inside
  StrongBox; encrypt it with a random data key and wrap that key in Keystore.
- `SecretMnemonic`, `SecretSeed`, and `SecretExtendedPrivateKey` make defensive
  copies, redact `toString`, and clear retained arrays on close. JVM/ART garbage
  collection and UniFFI String conversion mean complete zeroization of every
  historical copy cannot be guaranteed.
- `AndroidKeystoreSeedStore` uses Android Keystore AES-256-GCM. Its ciphertext
  lives under `noBackupFilesDir`; authenticated AAD binds the package, wallet,
  network, schema, and key policy. Provisioning is create-only and file writes
  use same-directory atomic rename plus file/directory fsync.
- Permanent key invalidation or a corrupt envelope requires an explicit,
  irreversible `resetForRecovery` authorized by the application after fresh
  user authentication and backup confirmation. The SDK never resets or
  reprovisions key material automatically.
- StrongBox fallback is never implicit. `REQUIRED` fails when unavailable;
  `PREFERRED_WITH_KEYSTORE_FALLBACK` is the explicit opt-in to fallback.
- Production `OperationJournal` implementations must use authenticated
  encryption (confidentiality and integrity) for prepared/signed transaction
  payloads and atomically commit each phase. The checkpoint codec checksum only
  detects accidental corruption; it does not authenticate hostile storage.
- Public SDK errors intentionally discard arbitrary backend, storage, and
  native exception causes so crash reports cannot retain secret response data.
- Do not log raw backend bodies, PSBTs, complete invoices, xpubs, auth tokens,
  payment preimages, seeds, or unlock credentials.

## Local approval boundary

The signer is default-deny. For the supported `sendBtc` profile it checks the
caller recipient and exact amount, network, ownership/derivation metadata for
every input, BIP86 key-path-only scripts, vanilla change, PSBT shape and size,
transaction version/locktime/sequence, sighash type, duplicate inputs, dust,
absolute fee, fee rate, output count, and unknown/proprietary fields before it
creates any signature.

Backend preparation is never authorization. Echoed server intent is compared
to a canonical hash produced from the original Kotlin request.

## Known boundary

Lightweight v1 does not perform full RGB contract/state-transition validation.
No UI or product text should describe its RGB result as trustless. A malicious
backend can potentially redirect allocations from selected colored inputs,
including RGB change, even when the Bitcoin transaction satisfies local policy.

Lightning is outside the current implementation. If later hosted by a shared
backend node, its balances are custodial/server-authorized until an explicitly
different architecture is implemented and reviewed.
