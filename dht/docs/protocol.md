# Boson Kademlia Protocol

This document describes the RPC protocol used in the Boson Kademlia DHT network. The protocol is designed for security, efficiency, and interoperability.

---

## Transport

Messages are exchanged over **UDP**. The default port is `39001`. Each node can participate on both IPv4 and IPv6 simultaneously.

The wire serialization is **CBOR** (binary). A JSON encoding of the same schema is also supported for debugging and testing. All field names are identical between the two formats; data types differ where noted below.

Minimum valid message size: **10 bytes**.

---

## Message Envelope

Every message is a top-level map/object with the following fields.

| Key | Name | JSON Type | CBOR Type | Required | Description |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **`y`** | Type & Method | `Number` | `Integer` | Yes | Composite field encoding message type and RPC method (see below). |
| **`t`** | Transaction ID | `Number` | `Integer` | Yes | Non-zero unsigned integer used to match requests with responses. |
| **`q`** | Request Body | `Object` | `Map` | Conditional | Present only in **Request** messages. |
| **`r`** | Response Body | `Object` | `Map` | Conditional | Present only in **Response** messages. |
| **`e`** | Error Body | `Object` | `Map` | Conditional | Present only in **Error** messages. |
| **`v`** | Version | `Number` | `Integer` | No | Node software version. Omitted when zero. |

> **Note:** The `y` field **must** appear before `q`, `r`, or `e` in the encoded stream. The deserializer uses `y` to select the body class before reading the body field.

The sender's node ID is **not** included in the wire message. It is resolved from the network context (source IP/port matched to the routing table) on the receiving side.

### Message Type & Method Encoding (`y`)

The `y` field packs message type and RPC method into a single integer using a bitmask.

| Bits | Mask | Field | Values |
| :--- | :--- | :--- | :--- |
| 4–0 | `0x1F` | **Method** | `PING(1)`, `FIND_NODE(2)`, `ANNOUNCE_PEER(3)`, `FIND_PEER(4)`, `STORE_VALUE(5)`, `FIND_VALUE(6)` |
| 7–5 | `0xE0` | **Type** | `ERROR(0x00)`, `REQUEST(0x20)`, `RESPONSE(0x40)` |

**Example computation:**
- `FIND_NODE` request: `0x02 | 0x20 = 0x22` (34)
- `FIND_NODE` response: `0x02 | 0x40 = 0x42` (66)
- `FIND_NODE` error: `0x02 | 0x00 = 0x02` (2)

---

## Data Representations

### Binary fields
- **JSON**: URL-safe Base64, no padding.
- **CBOR**: Raw byte strings.

### Identifiers (`Id`)
A 256-bit value derived from an Ed25519 public key.
- **JSON**: Base58-encoded string.
- **CBOR**: 32-byte raw binary.

### NodeInfo
Encoded as a compact **3-element array** `[id, host, port]`, not a map.

| Index | Field | JSON | CBOR |
| :--- | :--- | :--- | :--- |
| 0 | Node ID | Base58 string | 32-byte binary |
| 1 | Host | IP address string (IPv4 or IPv6) or hostname | Raw binary IP address |
| 2 | Port | Number | Number |

Example (JSON): `["HZXXs9LTfNQjrDKvvexRhuMk8TTJhYCfrHwaj3jUzuhZ", "155.138.245.211", 39001]`

### PeerInfo
Encoded as a **map/object**. The peer ID (`id`) may be omitted by the serializer when it is already known from context (e.g., inside an `ANNOUNCE_PEER` request where `k` carries it).

| Key | Name | Type | Required | Description |
| :--- | :--- | :--- | :--- | :--- |
| `id` | Peer ID | `Id` | Conditional | Public key of the service peer. Omitted when the receiver already knows it. |
| `seq` | Sequence | `Number` | No | Version number. Omitted when zero. |
| `o` | Node ID | `Id` | No | ID of the DHT node hosting the peer (authenticated peers only). |
| `os` | Node Signature | `Binary` | No | Node's Ed25519 signature over `SHA-256(id, o, f, seq)`. Required if `o` is present. |
| `sig` | Peer Signature | `Binary` | Yes | Owner's Ed25519 signature over the peer record. |
| `f` | Fingerprint | `Number` | No | Unique `long` fingerprint for this peer instance. Omitted when zero. |
| `e` | Endpoint | `String` | Yes | Service endpoint URI (e.g., `https://...`). |
| `ex` | Extra Data | `Binary` | No | Opaque extension bytes. |

> The node signature `os` covers the fingerprint `f` and the sequence number `seq`, so it attests one specific version of one specific peer instance and must be reissued by the node on every update. Signing only `(id, o)` would make it a constant, and so a permanent bearer credential: whoever held the peer private key could staple one node signature onto every later version, forever, without the node taking part again.
>
> Neither signature uses an application-supplied nonce. Ed25519 derives its per-signature randomness internally, so a nonce would add nothing.

### Value
Values can be **immutable**, **mutable**, or **encrypted** (mutable + recipient).

| Type | Required fields | Description |
| :--- | :--- | :--- |
| Immutable | `v` only | ID = SHA-256(`v`). Content is fixed. |
| Mutable | `k`, `seq`, `sig`, `v` | ID = `k`. Owner updates by incrementing `seq`. |
| Encrypted | `k`, `rec`, `n`, `seq`, `sig`, `v` | Mutable value whose payload is encrypted for the recipient `rec`. |

> The nonce `n` is the CryptoBox nonce and exists only for encrypted values - it is present exactly when `rec` is. Signed values carry none: Ed25519 derives its per-signature randomness internally, so an application-supplied nonce would add nothing. The nonce is covered by `sig`, since an unauthenticated nonce would let an attacker garble the recipient's decryption.

Value fields as they appear on the wire:

| Key | Name | Type | Description |
| :--- | :--- | :--- | :--- |
| `k` | Public Key | `Id` | Owner's public key (mutable/encrypted only). |
| `rec` | Recipient | `Id` | Recipient's public key (encrypted only). |
| `n` | Nonce | `Binary` | 24-byte CryptoBox nonce (encrypted only). |
| `seq` | Sequence | `Number` | Version number (mutable/encrypted only). |
| `sig` | Signature | `Binary` | Owner's Ed25519 signature (mutable/encrypted only). |
| `v` | Data | `Binary` | The value payload (all types). |

#### Sequence number semantics (mutable values and peers)

- **Monotonic only.** A stored record's content is replaced **only** by an update whose `seq` is strictly greater than the stored `seq`. The sequence number can never be lowered or reset - this is what prevents a replayed older (still validly signed) value from reverting current content. Owners update by incrementing `seq`; immutable values use `seq = 0` and are never content-updated (their ID is the content hash).
- **Equal `seq` is first-write-wins.** A store at the *same* `seq` with different content is accepted by the token/signature checks but does **not** replace the stored content (re-signing at an unchanged `seq` is a no-op for content). An honest owner never signs two payloads at one `seq`.
- **Republish keeps records alive regardless of `seq`.** Any valid store/announce for an existing record refreshes its announced timestamp (resetting its expiration), even when `seq` is unchanged or lower. This is the Kademlia keep-alive path; it can only extend the life of valid data, never alter it.
- The `seq` space (a non-negative integer) is large enough that resetting is unnecessary in practice.

---

## Write Tokens

STORE_VALUE and ANNOUNCE_PEER require a valid **write token** obtained from a prior lookup. Tokens are short-lived and opaque integers generated by the receiving node.

**A token is a non-zero 32-bit integer, and this is normative.** Zero is reserved to mean "no token": an
implementation MUST NOT issue it, and MUST reject it if one arrives in a STORE_VALUE or ANNOUNCE_PEER. The
reservation is what lets a single integer carry both the token and the fact that there is one, so a client
may hold it in a plain fixed-width field rather than an optional. An issuer whose derivation happens to
produce zero must map it onto some other value, not send it. A responder that sends zero anyway is read as
having sent no token, and the requester will not attempt the write.

A token is acquired from a `FIND_NODE` response, and only from there: set bit 2 (`wantToken`) in the
request's `w`, and the responder returns `tok` alongside the closest nodes. `FIND_VALUE` and `FIND_PEER`
responses carry no token. A client that intends to write therefore issues `FIND_NODE` with `wantToken` for
the target before `STORE_VALUE` or `ANNOUNCE_PEER`, whatever lookup it used to find the target.

---

## Message Limits

The limits in this section are **normative**. Each one is decidable from a single message, using nothing but the bytes just parsed - no knowledge of the sender's routing table, configuration, or history. That is what makes them enforceable: a receiver can conclude that a sender violated one, rather than guess.

A sender **MUST NOT** emit a message that exceeds any of them. A receiver **MAY** treat a violation as misbehavior by the sender, because no conforming implementation can produce one.

| Limit | Value | Applies to |
| :--- | :--- | :--- |
| Maximum UDP payload | 1400 bytes (IPv4) / 1200 bytes (IPv6) | Every message |
| Minimum message size | 10 bytes | Every message |
| Node entries per address family | 16 | `n4`, `n6` in any response |
| Node entries per source unit | 8 | `n4`, `n6` in any response |
| Peer entries per response | 8 | `p` in a `FIND_PEER` response |

### Source units

Several limits count *sources* rather than addresses, because an address is only a meaningful unit of accountability if the sender had to acquire it. A single IPv4 address is such a unit. A single IPv6 address is not: the smallest allocation an IPv6 subscriber or VPS tenant receives is a routed `/64`, which is 1.8x10^19 addresses the holder genuinely receives at, so counting per 128-bit address would give one sender an unlimited supply of fresh budgets.

A **source unit** is therefore:

- **IPv4**: the full 32-bit address.
- **IPv6**: the `/64` prefix - the leading 64 bits, with the remainder zeroed.
- **IPv4-mapped IPv6** (`::ffff:0:0/96`): treated as the IPv4 address it carries, never masked to a `/64`.

This definition is part of the protocol and not an implementation detail. Two implementations that grouped IPv6 differently - one per address, one per `/64` - would each read conforming responses from the other as violations.

### Node entries

A response carrying node entries **MUST NOT** include more than **16** entries per address family, and **MUST NOT** include more than **8** entries whose addresses fall in one source unit.

The count limit bounds the datagram. The source limit bounds something the count cannot: node IDs are free to generate, so sixteen distinct IDs may all sit behind one machine, and without this a single sender could supply an entire lookup's worth of candidates.

A receiver **MUST NOT** use any node entry from a response that violates either limit. Rejecting the message whole rather than trimming it to the limit is required so that violation is not a cheap way to have some part of an oversized list accepted.

Only globally routable unicast addresses are counted against the source limit. Loopback, link-local and private-range addresses are free to obtain and so measure nothing; a receiver ignores them for this purpose (they are subject to its own admission policy instead).

### Peer entries

A `FIND_PEER` response **MUST NOT** carry more than **8** peer entries, whatever count the request asked for in `e`. Unlike node entries, a peer entry is variable-length - the endpoint URI and `ex` are unbounded in principle - so this count alone does not bound the response size; the datagram limit above governs, and a responder returns fewer entries when they would not fit.

A receiver **MAY** discard entries beyond the limit rather than rejecting the message.

### What is not protocol

Receivers apply their own acceptance policies on top of these limits, and senders **MUST NOT** infer anything from them:

- **A receiver may use fewer entries than it was sent.** It is free to accept only a share of any one response - for example to stop a single answer from displacing everything it already knows. Being within the limits does not mean every entry is used.
- **`k` is a local parameter.** It is not negotiated, not carried on the wire, and a sender cannot know the receiver's value.
- **Routing table admission is local and unobservable.** How a node decides what to keep, including any diversity budget it enforces on its own table, is its own affair. Only what a node *says* is constrained here.

These are deliberately excluded: a rule a receiver cannot verify from the message is a rule that cannot be enforced without accusing honest peers.

### Applicability

These limits are stated as of Boson 3.1. Implementations predating this document may exceed them while otherwise interoperating correctly. A receiver that acts on violations **SHOULD** do so through a mechanism that tolerates them occasionally - rate-limited, time-bounded suppression - rather than permanent exclusion.

---

## RPC Methods

### PING (1)
Verifies node liveness.

- **Request (`q`)**: *(empty - no fields)*
- **Response (`r`)**: *(empty - no fields)*

---

### FIND_NODE (2)
Iterative lookup returning the K closest nodes to a target ID.

**Request (`q`):**

| Key | Name | Type | Required | Description |
| :--- | :--- | :--- | :--- | :--- |
| `t` | Target | `Id` | Yes | The 256-bit identifier to look up. |
| `w` | Want | `Number` | Yes | Bitmask: bit 0 = want IPv4 nodes, bit 1 = want IPv6 nodes, bit 2 = want a write token. |

**Response (`r`):**

| Key | Name | Type | Required | Description |
| :--- | :--- | :--- | :--- | :--- |
| `n4` | Nodes (IPv4) | `Array<NodeInfo>` | No | Closest IPv4 nodes, bounded by [Message Limits](#message-limits). Omitted if empty. |
| `n6` | Nodes (IPv6) | `Array<NodeInfo>` | No | Closest IPv6 nodes, bounded by [Message Limits](#message-limits). Omitted if empty. |
| `tok` | Token | `Number` | No | Write token. Included only when `w` bit 2 was set. |

---

### FIND_VALUE (6)
Retrieves a stored value by its ID. Returns the value when found, or the closest nodes otherwise.

**Request (`q`):**

| Key | Name | Type | Required | Description |
| :--- | :--- | :--- | :--- | :--- |
| `t` | Target | `Id` | Yes | ID of the value to look up. |
| `w` | Want | `Number` | Yes | Same bitmask as `FIND_NODE`. |
| `cas` | Expected Seq | `Number` | No | If present, only return the value if its stored `seq` is greater than this number. |

**Response (`r`):**

When the value is **found**, the response contains value fields. When the value is **not found**, it contains closest nodes instead. No write token is carried either way - see [Write Tokens](#write-tokens).

| Key | Name | Type | Condition | Description |
| :--- | :--- | :--- | :--- | :--- |
| `n4` | Nodes (IPv4) | `Array<NodeInfo>` | Value not found | Closest IPv4 nodes, bounded by [Message Limits](#message-limits). |
| `n6` | Nodes (IPv6) | `Array<NodeInfo>` | Value not found | Closest IPv6 nodes, bounded by [Message Limits](#message-limits). |
| `k` | Public Key | `Id` | Mutable/encrypted | Owner's public key. |
| `rec` | Recipient | `Id` | Encrypted | Recipient's public key. |
| `n` | Nonce | `Binary` | Encrypted | 24-byte CryptoBox nonce. |
| `seq` | Sequence | `Number` | Mutable/encrypted | Version number. Omitted when zero. |
| `sig` | Signature | `Binary` | Mutable/encrypted | Owner's Ed25519 signature. |
| `v` | Data | `Binary` | Value found | The value payload. |

---

### STORE_VALUE (5)
Publishes a value to a node. Requires a write token from a prior `FIND_NODE` that asked for one.

**Request (`q`):**

| Key | Name | Type | Required | Description |
| :--- | :--- | :--- | :--- | :--- |
| `tok` | Token | `Number` | Yes | Write token from a prior `FIND_NODE` that set `wantToken`. |
| `cas` | Expected Seq | `Number` | No | Atomic update: only store if the currently stored `seq` equals this value. |
| `k` | Public Key | `Id` | Mutable/encrypted | Owner's public key. |
| `rec` | Recipient | `Id` | Encrypted | Recipient's public key. |
| `n` | Nonce | `Binary` | Encrypted | 24-byte CryptoBox nonce. |
| `seq` | Sequence | `Number` | Mutable/encrypted | New version number. Omitted when zero. |
| `sig` | Signature | `Binary` | Mutable/encrypted | Owner's Ed25519 signature. |
| `v` | Data | `Binary` | Yes | The value payload. |

**Response (`r`):** *(empty - no fields)*

---

### FIND_PEER (4)
Discovers service endpoints registered under a service ID. Returns matching peers when found, or closest nodes otherwise.

**Request (`q`):**

| Key | Name | Type | Required | Description |
| :--- | :--- | :--- | :--- | :--- |
| `t` | Target | `Id` | Yes | Service identifier. |
| `w` | Want | `Number` | Yes | Same bitmask as `FIND_NODE`. |
| `cas` | Expected Seq | `Number` | No | If present, only return peers if their stored `seq` is greater than this number. |
| `e` | Count | `Number` | No | Desired number of peer results. The responder returns at most 8 whatever this asks for. |

**Response (`r`):**

| Key | Name | Type | Condition | Description |
| :--- | :--- | :--- | :--- | :--- |
| `n4` | Nodes (IPv4) | `Array<NodeInfo>` | Peers not found | Closest IPv4 nodes, bounded by [Message Limits](#message-limits). |
| `n6` | Nodes (IPv6) | `Array<NodeInfo>` | Peers not found | Closest IPv6 nodes, bounded by [Message Limits](#message-limits). |
| `p` | Peers | `Array<PeerInfo>` | Peers found | Matching service peer records, at most 8; see [Message Limits](#message-limits). |

---

### ANNOUNCE_PEER (3)
Registers a service endpoint with a node. Requires a write token from a prior `FIND_NODE` that asked for one.

**Request (`q`)** - field order on wire: `tok`, `cas`, `k`, `n`, `seq`, `o`, `os`, `sig`, `f`, `e`, `ex`:

| Key | Name | Type | Required | Description |
| :--- | :--- | :--- | :--- | :--- |
| `tok` | Token | `Number` | Yes | Write token from a prior `FIND_NODE` that set `wantToken`. |
| `cas` | Expected Seq | `Number` | No | Atomic update: only store if the currently stored `seq` equals this value. |
| `k` | Peer ID | `Id` | Yes | Public key of the service peer (the peer owner's key). |
| `seq` | Sequence | `Number` | No | Current sequence number. Omitted when zero. |
| `o` | Node ID | `Id` | No | ID of the DHT node hosting the peer (authenticated mode only). |
| `os` | Node Signature | `Binary` | No | The hosting node's Ed25519 signature over `SHA-256(k, o, f, seq)`. Required if `o` is present. |
| `sig` | Peer Signature | `Binary` | Yes | Peer owner's Ed25519 signature over the peer record. |
| `f` | Fingerprint | `Number` | Yes | Unique `long` fingerprint distinguishing peer instances with the same `k`. |
| `e` | Endpoint | `String` | Yes | Service URI (e.g., `https://example.com:8080`). |
| `ex` | Extra Data | `Binary` | No | Opaque extension bytes. |

**Response (`r`):** *(empty - no fields)*

---

## Errors

Error messages use `y` type bits = `0x00`. The body is carried in the `e` envelope field.

### Error Body (`e`)

| Key | Name | Type | Required | Description |
| :--- | :--- | :--- | :--- | :--- |
| `c` | Code | `Number` | Yes | Numeric error code. |
| `m` | Message | `String` | No | Human-readable description. |

### Error Codes

| Code | Name | Description |
| :--- | :--- | :--- |
| **201** | Generic Error | General unclassified failure. |
| **202** | Server Error | Internal server-side failure. |
| **203** | Protocol Error | Malformed packet, invalid arguments, or bad token. |
| **204** | Method Unknown | RPC method not supported by this node. |
| **205** | Message Too Big | Packet exceeds the allowed size limit. |
| **206** | Invalid Signature | Ed25519 signature verification failed. |
| **207** | Salt Too Big | The salt value exceeds the allowed length. |
| **301** | CAS Fail | The `cas` check failed: stored sequence number does not match the expected value. |
| **302** | Sequence Not Monotonic | The new sequence number is not greater than the currently stored one. |
| **303** | Immutable Substitution Fail | Attempted to replace an immutable value with a different one. |
| **400** | Invalid Token | The write token is missing, incorrect, or expired. |
| **401** | Invalid Value | The value is malformed or fails validation. |
| **402** | Invalid Peer | The peer record is malformed or fails validation. |

---

## Security Considerations

### Trust model

The DHT is trustless. A node does not trust another node, and nothing in this protocol asks it to.
Security is layered, and each layer is written on the assumption that the ones before it have already
failed for some peer.

**1. Identity and transport.** Every node is an Ed25519 keypair, and its node id *is* the public key.
Every request and response is sealed with authenticated encryption, keyed by the X25519 conversion of
that keypair. Two properties follow, and most of what comes after rests on them:

- A message that decrypts is from the node it claims to be from. An attacker can spoof an *address*; it
  cannot spoof an *identity* whose private key it does not hold.
- A request can only be read by the node it was addressed to, so its transaction id is unknown to anyone
  else. An off-path attacker cannot answer a request it cannot read.

**2. Content.** A receiver validates what a response carries before acting on it. Values and peer records
are self-certifying - signed by their publisher and verifiable by anyone - so a responder may withhold a
record but cannot substitute one. A response carrying a record that fails validation is discarded whole,
not repaired.

**3. Contacts.** Node entries are different in kind from values: a contact is a claim about a third party
that the receiver has no way to verify. They are never fully trusted. In particular, no single response
may fill a routing table or dominate the direction of a lookup.

**4. Table composition.** Beyond any single response, a node bounds how much of its routing table one
source unit may occupy, so that holding many addresses does not by itself mean holding many table slots.

**5. Behaviour.** Abusive rates are throttled, and a peer that does what no correct implementation could
do is suppressed. That bar is deliberately high: suppression is for provable misbehavior, not for a peer
that merely answers differently than expected. See [Applicability](#applicability).

### What this protocol guarantees

- **Sender authenticity.** A received message is from the identity it names, or it does not decrypt.
- **Integrity and confidentiality in transit.** Neither an on-path nor an off-path party can read or
  alter a message, or forge a response to a request it did not carry.
- **Content authenticity.** A value or peer record that validates was published by the key it names,
  whatever route it arrived by and whichever node served it.
- **Write locality.** A write token is issued to one requester at one address for one target, and is
  short-lived, so a write must be preceded by a round trip that the writer actually received.

### What it does not guarantee

**Node identity is free, and this is deliberate.** A node id is an Ed25519 public key and nothing more,
so generating identities costs only key generation. An adversary willing to spend offline CPU can
therefore produce identities that land arbitrarily close to a chosen target, and Kademlia stores at and
reads from the nodes closest to a key by construction. An adversary who can *choose* to be closest to a
key can keep that key from being found - a targeted eclipse.

This protocol imposes no cost on identity generation, and the omission is a decision rather than an
oversight. Node identity is self-sovereign: a node chooses its own identity and needs no permission from
anyone to exist. A puzzle on id generation would tax every honest node, permanently, to raise a determined
adversary's cost by a bounded factor - and that factor is capped by what the weakest honest device can
afford to pay once, which is not enough to deter an adversary who has chosen a target and is willing to
rent hardware.

Implementers should understand exactly how far the consequences run:

- **An eclipse censors; it never forges.** Because content is self-certifying, an adversary in the closest
  set can withhold a record but cannot substitute a different one. This is an availability property, not
  an integrity one.
- **Silence is a valid answer.** "I do not have it" is exactly what an honest node closest to a key it has
  never been told about says. No validation rule separates the two cases, which is why the layers above do
  not cover this one: the adversary need never do anything a correct implementation could not do.
- **A writer can tell, and should look.** A publisher learns the outcome per target rather than in
  aggregate, so a write that reached nobody, or only some, is reported rather than silently assumed. An
  application that needs certainty of publication should check that outcome instead of treating the
  absence of an error as success.
- **The exposure is per target.** The work is spent against one key and buys nothing against any other, so
  this is a way to attack a chosen key, not a way to attack the network.

**Contact lists cannot be verified.** A node list is what the responder chose to say. Nothing here lets a
receiver distinguish an adversary's list from a well-connected honest node's, because near a target the
two are the same list. Bounding one response's influence limits the damage; it does not detect the case.

**Address claims are only as good as the round trip.** An address in a contact list is a claim.
A write token is the only thing in this protocol that demonstrates a peer receives at the address it
gave, and it demonstrates that only for the peer that asked.

**Availability under a targeted attack is not promised.** The guarantees above are about authenticity and
integrity. An adversary spending real resources against one key can deny access to it. An application
whose correctness depends on a record remaining reachable should anchor that record somewhere it does not
depend on DHT convergence alone.
