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
- Built-in key derivation, seed retrieval, and API-key retrieval retain ownership
  until a dispatched result reaches its caller. Cancellation or file/lock-close
  failures destroy undelivered closeable results. Custom providers must preserve
  the same ownership rule; returning a secret through bare `withContext` can lose
  its cleanup owner on prompt cancellation.
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
- `UTEXOWallet.create` supplies the authenticated durable journal directly.
  Its exclusive lifetime file lease prevents simultaneous sessions for the
  same wallet; shutdown keeps the lease, destroy releases it. AES-GCM AAD binds
  application/wallet/network/service origin, and encrypted state binds signer
  identity plus gateway account. An origin change fails before loading API
  credentials. Keystore commit-anchor aliases and an independent
  initialized marker reject old committed snapshots and deletion of committed
  data. A write fsyncs its candidate before creating the pending anchor, then
  atomically renames/fsyncs the directory before retiring the previous anchor.
  Bootstrap interruptions recover authenticated candidates or reuse the
  original unused wrapping key; keys are never silently regenerated.
- Seed/journal/API credential files use owner-only permissions and reject existing
  symlinks, nonregular files, hardlinks, and files owned by another UID. These
  controls and Keystore markers do not defend against a compromised app process,
  a rooted system that can manipulate Keystore, or full device-state rollback.
  Journal storage never automatically deletes idempotency history; reaching
  its 64 MiB / 100,000-record bound fails before the next network transition.
- Seed envelopes are bounded to 4 KiB before allocation, read to the declared
  length and checked for unexpected trailing bytes. A failed seed or journal
  write never authorizes an automatic key replacement, history reset or retrying
  a possibly broadcast payment.
- Definitive TERMINAL records compact to versioned receipts without PSBT bytes.
  Wallet/key/request/operation identity, local validation and final result remain
  immutable. Nonterminal evidence is never compacted or evicted. Old authenticated
  snapshots upgrade through the same atomic, rollback-protected write protocol;
  older readers cannot read v2 and must fail closed. App-owned pending requests
  and keys remain required for recovery; there is no seed-only reconstruction.
- The ordinary API takes a rotating `WalletAPIKeyProvider` and offers an
  Android Keystore encrypted credential store. The provider is consulted per
  request; returned credentials are closed promptly. Credential changes must
  still resolve to the journal's bound gateway account. JVM HTTP headers require
  an immutable String, so erasure of every temporary header copy is not promised.
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

Every `sendBtc` input must resolve to the vanilla BIP86 external or change
account. The Kotlin policy mapper cannot opt into colored inputs, and the Rust
signer independently rejects them even if a future caller or binding attempts
to weaken that policy.

"Vanilla" here proves the input key's derivation account, not the absence of
undisclosed RGB allocations. Full RGB transition/consignment validation is out
of the initial lightweight scope. The gateway is trusted for RGB state and must
preserve native exclusion of RGB-tracked and reserved vanilla outputs from BTC
selection. RN's pinned native implementation provides that pattern; the exact
deployed gateway package and regression coverage still need qualification.
This does not call for an invented cryptographic proof of missing allocations,
and local account/transaction authorization remains mandatory.

The PR #174 prepare/complete mapping is disabled by default. Its explicit
`allowUnverifiedPsbtProfile` opt-in is for development interoperability only
until canonical backend fixtures, the exact PSBT profile, and idempotency
durability are settled. The gate runs before new or nonterminal journal
advancement and is rechecked immediately before both gateway write requests.
An already-expired prepared/signed checkpoint may be truthfully terminalized
before that gate. Terminal replay and status-only SUBMITTING recovery remain
available with the gate off; neither path prepares or submits another
transaction.

The separately selected internal `utexo.psbt.rgb-lib.v1` path keeps its own
profile domain and requires an independently trusted maximum block height;
the candidate PSBT is never allowed to choose that bound. It authorizes the
specific Bitcoin effects of BTC send, create-UTXOs, and RGB send, with a fee
target plus independent hard caps. This does not assert full RGB validity or
make gateway routes production-ready. Legacy signed/checkpoint interpretation
is not silently changed by adding this profile.

Current local policy applies before the first submission even if the signature
was retained across a restart. The original preparation is revalidated without
re-signing, and all transaction facts must match retained validation. Tightening
policy can reject the first submission; it cannot prevent status-only recovery
of an already-SUBMITTING operation or replay of a definitive terminal result.

An unlocked native session holds signing keys in process memory until shutdown
or destruction. Keystore/StrongBox protects at-rest wrapping and retrieval, not
hardware-isolated secp256k1 signing or biometric approval for every signature.
Apps own their session/background/device-lock policy and must await shutdown
when authenticated access ends. A changed creation-time seed-store policy is
not an implicit custody upgrade or authorization to delete old material.

The gateway operation-status adapter accepts completed BTC success only when
the returned operation/intent/expiry and transaction ID match retained local
journal evidence and the original caller request. Pending/expired states are
not proof of no broadcast, even when `txid` is null: the read bypasses the
wallet queue and durable bookkeeping follows broadcasting. Likewise a gateway
completion's `410 OP_EXPIRED` is ambiguous, not a safe terminal failure.
Completion 3xx/4xx responses, including both operation-kind/idempotency conflicts
and unknown, malformed or authentication failures,
are likewise not authoritative no-broadcast evidence. No arbitrary 4xx response
is compacted into a definitive rejection receipt; status-only recovery preserves
the submitting checkpoint until a verified final outcome exists.
Ambiguous gateway completion receives one read-only reconciliation; it never
causes automatic prepare/sign/submit. Malformed, missing, contradictory,
unavailable, or canceled recovery cannot release or replace the checkpoint.
The endpoint does not yet carry complete create-UTXO or RGB public results.

Backend preparation is never authorization. Echoed server intent is compared
to a canonical hash produced from the original Kotlin request.

Success and error JSON bodies are structurally bounded before recursive decoding
(64 nested containers and 100,000 lexical tokens, within the HTTP body ceiling).
The shared native interface is initialized once with UniFFI contract/checksum
validation before all handwritten key/signing entry points. This rejects an
incompatible library; a compatible malicious library can forge those values,
so ABI checks do not establish authenticity or replace release signature checks.

## Known boundary

Lightweight v1 does not perform full RGB contract/state-transition validation.
The native invoice parser validates canonical encoding, checksums, supported
network/schema/assignment/transport syntax and exact raw intent fields. It does
not validate a contract history or prove seed ownership of the beneficiary.
`onchainReceive` relies on gateway-held receive state: witness derivation evidence
and blind secret recovery are not supplied by the current DTO. An explicit null
response expiry is preserved only when the invoice itself has a valid expiry
matching the requested/default duration. No endpoint is contacted by parsing.
No UI or product text should describe its RGB result as trustless. A malicious
backend can potentially redirect allocations from selected colored inputs,
including RGB change, even when the Bitcoin transaction satisfies local policy.

Lightning is outside the current implementation. If later hosted by a shared
backend node, its balances are custodial/server-authorized until an explicitly
different architecture is implemented and reviewed.
