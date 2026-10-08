# Boson strings

Boson defines its own machine-readable strings in one form, the **Boson string**, so that applications,
services and libraries share one standard for them. Standard formats are used as they are and never
wrapped: W3C DIDs and VCs, URLs, content types, existing file formats.

The Java implementation is `io.bosonnetwork.utils.BosonString`; the web front end follows the same rules
in `director/frontend/packages/passkey`.

## Grammar

```
boson-string = "boson:" namespace ":" version *( ":" field )
namespace    = %x61-7A *( %x61-7A / DIGIT / "-" )   ; a lowercase letter, then lowercase letters, digits or "-"
version      = %x31-39 *DIGIT                        ; 1, 2, ... with no leading zeros
field        = 1*( %x21-7E except "?" and "#" )      ; visible ASCII
```

- **Every namespace is versioned.** Each version of a namespace fixes how many fields it has and what
  each field may contain. A new version is an incompatible change; a variant is a new version, never an
  optional field.
- **Only the last field may contain `:`.** Readers split with a limit, so a URL can be the last field.
- **No empty fields, no `?`, no `#`.** A Boson string is an opaque URI (scheme `boson`): it has no
  query or fragment, and Android's URI query methods throw on opaque URIs.
- **ASCII and case-sensitive**, with no percent-encoding.
- **Readers trim surrounding whitespace** and accept nothing else; writers write the canonical form.
- **Field encodings:** Boson ids in Base58; other bytes in base64url without padding; integers in
  decimal.
- **No secrets.** Any app may claim the `boson:` URI scheme, so a phone may hand a scanned Boson string
  to an app the user didn't mean. Private keys are never written as Boson strings.

## Two kinds of namespace

| Kind | What it is | Rule |
|---|---|---|
| **Format** | Text people or programs exchange: QR codes, pasted codes, certificate fields. | Parsed from input; a new version is an incompatible parse. |
| **Label** | A constant fed to a hash, a PRF or a key derivation, separating cryptographic domains. | Never parsed from input; a new version means new keys. Label namespaces are named by their role (`prf`, `kdf`, `sig`), so a format can never look like a label. |

## Registry

### Formats

| Namespace | Version 1 | Fields | Defined in |
|---|---|---|---|
| `signin` | `boson:signin:1:<requestId>` | the sign-in request id, Base58 | `SignInRequest` (Java), `signInCode` (web) |
| `pair` | `boson:pair:1:<registrationId>:<key>` | the registration id, Base58; the 32-byte X25519 key the approver seals the user key to, base64url without padding (43 characters) | `PairingCode` (Java), `pairingCode` (web) |
| `supernode` | `boson:supernode:1:<nodeId>:<url>` | the node id, Base58; the Director's http(s) URL without user info, query, fragment or trailing slash | `SuperNodeCode` (Java), `superNodeCode` (web) |
| `enroll` | `boson:enroll:1:<requestId>:<nodeId>:<url>` | an administrator's enrollment request id, Base58; the node id, Base58; the Director's http(s) URL as apps reach it, without user info, query, fragment or trailing slash | `EnrollmentCode` (Java) |
| `certbind` | `boson:certbind:1:<publicKey>:<signature>` | the identity's Ed25519 public key, Base58; its signature over `boson:certbind:1` (ASCII) followed by the certificate's SubjectPublicKeyInfo DER, base64url without padding | `CertUtil` |

`certbind` is carried in a self-signed certificate's `issuerAltName` extension as a URI GeneralName.

### Labels

| Label | Use | Defined in |
|---|---|---|
| `boson:prf:1:passkey-device` | Its SHA-256 is the WebAuthn PRF input of a web passkey that unlocks a device key. | `PRF_SALT` (web) |
| `boson:kdf:1:ed25519:device-key` | HKDF-SHA256 info: the Ed25519 device key from that PRF output (empty salt, 32 bytes). | `deriveDeviceKey` (web) |
| `boson:prf:1:passkey-backup` | Its SHA-256 is the PRF input of the Boson Identity passkey backup. | `PasskeyBackupFormat` (Boson Identity) |
| `boson:kdf:1:aes256gcm:user-key-wrap` | HKDF-SHA256 info: the AES-256-GCM key that wraps the user key in that backup (empty salt, 32 bytes). | `PasskeyBackupFormat` (Boson Identity) |
| `boson:kdf:1:registration-pow` | The key-derivation context of the key that signs registration proof-of-work challenges. | `RegistrationPow` (Director) |
| `boson:certbind:1` | Prefixed to what a `certbind` signature covers. | `CertUtil` |
| `boson:sig:1:enroll-claim` | Prefixed to what the user key and the device key each sign to claim an enrollment request: then the node id, the request id and the signer's id (32 bytes each), then the claim's nonce. | `EnrollmentCode` (Java), `EnrollmentRequest` (Director) |

## Forms read but no longer written

Readers accept these so that apps and nodes from Boson 3.1 keep working with newer ones.

| Form | Read as | Read by |
|---|---|---|
| `bosonpair:1:<registrationId>:<key>` | `pair` version 1 | `PairingCode.parse` |
| JSON `{"url": ..., "id": ...}`, `id` optional | `supernode` version 1 (a code without an id has no text form of its own) | `SuperNodeCode.parse` |
| `boson:ed25519:<publicKey>:<signature>` in `issuerAltName`, signed over the bare SPKI | a binding of the first form | `HybridTrustManager`, only when the certificate carries no `certbind` |

Self-signed certificates carry both bindings: `certbind` first, then the first form, so that clients from
Boson 3.1 still verify them.

## Not Boson strings

These keep their own formats, on purpose:

| String | Form | Why |
|---|---|---|
| Ids | Base58; also `0x<hex>` and `did:boson:<base58>` | `did:boson:` is a W3C DID; a user's id QR code is `did:boson:<base58>`. |
| Avatar and media | `bnr://<nodeId>/api/v1/client/avatar/<userId>`, `.../media/<userId>` | A hierarchical URI resolved through the node's DHT peer. |
| Ion Store objects | `ions://<servicePeerId>/<objectId>` | A hierarchical URI. |
| DID and VC contexts, the profile credential | `https://bosonnetwork.io/ns/did/v1`, `https://bosonnetwork.io/ns/credentials/v1`; credential `profile` of type `BosonProfile` | W3C standards. |
| Channel invites | content type `application/invite-ticket` | A content type. |
| Service type ids | `io.bosonnetwork.ionstore`, `.webgateway`, `.activeproxy`, `.photonmessaging` | Reverse-domain names. |
| App sign-in returns | `https://bosonnetwork.io/identity/auth`, `https://bosonnetwork.io/photon/auth` (the result in the fragment); `io.bosonnetwork.photon://auth` for Photon on nodes from before 3.2 | URLs (App Links). |
| User key backup | the 64-byte private key in Base58; the key file (`Boson User Identity Key` / `privateKey:` / `publicKey:`) | A secret, written down and typed back; never a Boson string. |
| Proof-of-work personalization | `BosonPoW`, then n and k as 32-bit little-endian integers | BLAKE2b's fixed 16-byte personalization field. |
| Service keys | key-derivation context `<serviceType>` or `<serviceType>_<instance>` | Changing it changes every service's peer id. |
| OAuth session ids | key-derivation context `oauth-session:<provider>` NUL `<providerUserId>` | Shipped; the NUL keeps any two provider and user pairs apart. |
| Photon's avatar cache key | `director-avatar://<userId>[?v=<n>]` | Internal to Photon, never exchanged. |
| `boson://localhost:65532` | a placeholder endpoint of the allow-all test context | Not an address. |

## Adding a namespace

1. Choose a name for what the string is (a format) or for its role (a label).
2. Define version 1: the fields in order, each one's characters and encoding, and which field may
   contain `:` (only the last).
3. Add it to the registry above, and implement it with `BosonString` (Java) or alongside the web helpers.
4. A change that old readers can't parse, or that changes derived keys, is a new version; keep reading
   the old one for as long as apps or nodes that write it are in use.
